package com.example.feature.commitments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.common.IdGenerator
import com.example.core.common.toDisplayMessage
import com.example.domain.filter.DomainNormalizationResult
import com.example.domain.filter.DomainNormalizer
import com.example.domain.filter.DomainValidationError
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentRulesValidator
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.RulesValidationError
import com.example.domain.lifecycle.RulesValidationResult
import com.example.domain.model.AllowedApp
import com.example.domain.model.AllowlistCategory
import com.example.domain.model.AppId
import com.example.domain.model.BlockedDomain
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DomainCategory
import com.example.domain.model.DomainName
import com.example.domain.model.DomainRuleType
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ScheduleWindow
import com.example.domain.repository.AccountabilityMetadataRepository
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.InstalledAppRepository
import com.example.domain.repository.InstalledAppsResult
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import android.content.Intent
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.health.EnforcementHealthSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleWindowDraft(
  val daysOfWeek: Set<DomainDayOfWeek> =
    setOf(
      DomainDayOfWeek.MONDAY,
      DomainDayOfWeek.TUESDAY,
      DomainDayOfWeek.WEDNESDAY,
      DomainDayOfWeek.THURSDAY,
      DomainDayOfWeek.FRIDAY,
    ),
  val startHour: Int = 22,
  val startMinute: Int = 0,
  val endHour: Int = 6,
  val endMinute: Int = 0,
) {
  val startTime: DomainTime
    get() = DomainTime(startHour, startMinute)

  val endTime: DomainTime
    get() = DomainTime(endHour, endMinute)
}

data class DraftEditorState(
  val existingDraft: Commitment.Draft? = null,
  val mode: RestrictionMode = RestrictionMode.HYBRID,
  val restrictedApps: Set<RestrictedApp> = emptySet(),
  val allowedApps: Set<AllowedApp> = emptySet(),
  val scheduleWindows: List<ScheduleWindow> = emptyList(),
  val dailyAllowanceMinutes: Int = 30,
  val windowDraft: ScheduleWindowDraft = ScheduleWindowDraft(),
  val installedApps: List<InstalledAppInfo> = emptyList(),
  val installedAppsLoaded: Boolean = false,
  val installedAppsError: String? = null,
  val restrictedSearchQuery: String = "",
  val allowlistSearchQuery: String = "",
  val customRestrictedLabel: String = "",
  val customRestrictedPackage: String = "",
  val customAllowedLabel: String = "",
  val customAllowedPackage: String = "",
  val customAllowedCategory: AllowlistCategory = AllowlistCategory.CUSTOM,
  val websiteFilteringEnabled: Boolean = false,
  val blockedDomains: Set<BlockedDomain> = emptySet(),
  val blockedDomainCategories: Set<DomainCategory> = emptySet(),
  val blockYouTubeShorts: Boolean = false,
  val accessibilityHealth: EnforcementHealthSnapshot = EnforcementHealthSnapshot(),
  val customDomainInput: String = "",
  val datasetMetadata: List<FilterDatasetMetadata> = DatasetSeedDefaults.defaultMetadata(),
  val datasetEvalEpochMillis: Long = 0L,
  val isPinConfigured: Boolean = false,
  val requirePinForEmergencyTermination: Boolean = false,
  val validationMessages: List<String> = emptyList(),
  val statusMessage: String? = null,
  val isSaving: Boolean = false,
)

sealed interface CommitmentSetupUiState {
  data object Loading : CommitmentSetupUiState

  data class Error(val message: String) : CommitmentSetupUiState

  data class ActiveLocked(val activeCommitment: Commitment.Active) : CommitmentSetupUiState

  data class Editing(val editor: DraftEditorState) : CommitmentSetupUiState
}

sealed interface CommitmentSetupEffect {
  data class NavigateToReview(val commitmentId: String) : CommitmentSetupEffect
}

@HiltViewModel
class CommitmentsViewModel
@Inject
constructor(
  private val commitmentRepository: CommitmentRepository,
  private val installedAppRepository: InstalledAppRepository,
  private val domainFilterRepository: DomainFilterRepository,
  private val accountabilityMetadataRepository: AccountabilityMetadataRepository,
  private val enforcementHealthMonitor: EnforcementHealthMonitor,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
) : ViewModel() {

  private val editorState = MutableStateFlow(DraftEditorState())
  private var hydratedDraftId: CommitmentId? = null

  private val _effects = Channel<CommitmentSetupEffect>(Channel.BUFFERED)
  val effects = _effects.receiveAsFlow()

  init {
    refreshInstalledApps()
    refreshHealth()
    viewModelScope.launch {
      runCatching { domainFilterRepository.ensureDefaultDatasetsInitialized() }
    }
  }

  fun refreshHealth() {
    enforcementHealthMonitor.refreshSystemStatus()
  }

  fun createAccessibilitySettingsIntent(): Intent =
    enforcementHealthMonitor.createAccessibilitySettingsIntent()

  fun refreshInstalledApps() {
    viewModelScope.launch {
      when (val result = installedAppRepository.getLaunchableApps()) {
        is InstalledAppsResult.Success -> {
          updateEditor {
            it.copy(
              installedApps = result.apps,
              installedAppsLoaded = true,
              installedAppsError = null,
            )
          }
        }
        is InstalledAppsResult.Failure -> {
          updateEditor {
            it.copy(
              installedApps = emptyList(),
              installedAppsLoaded = true,
              installedAppsError = result.message,
            )
          }
        }
      }
    }
  }

  val uiState: StateFlow<CommitmentSetupUiState> =
    combine(
        commitmentRepository.observeAllCommitments(),
        domainFilterRepository.observeDatasets(),
        accountabilityMetadataRepository.observeMetadata(),
        enforcementHealthMonitor.healthState,
        editorState,
      ) { commitmentsResult, datasetsResult, metadataResult, healthSnapshot, editor ->
        val datasets =
          when (datasetsResult) {
            is PersistenceResult.Success ->
              if (datasetsResult.data.isEmpty()) DatasetSeedDefaults.defaultMetadata()
              else datasetsResult.data
            is PersistenceResult.Failure -> editor.datasetMetadata
          }
        val pinConfigured =
          metadataResult is PersistenceResult.Success && metadataResult.data.isPinEnabled

        when (commitmentsResult) {
          is PersistenceResult.Failure ->
            CommitmentSetupUiState.Error(commitmentsResult.error.toDisplayMessage())
          is PersistenceResult.Success -> {
            val commitments = commitmentsResult.data
            val active =
              commitments.firstOrNull { it is Commitment.Active } as? Commitment.Active
            if (active != null) {
              CommitmentSetupUiState.ActiveLocked(active)
            } else {
              val latestDraft =
                commitments.firstOrNull { it is Commitment.Draft } as? Commitment.Draft
              if (latestDraft != null && hydratedDraftId != latestDraft.id) {
                hydratedDraftId = latestDraft.id
                val hydrated =
                  editor.copy(
                    existingDraft = latestDraft,
                    mode = latestDraft.rules.mode,
                    restrictedApps = latestDraft.rules.restrictedApps,
                    allowedApps = latestDraft.rules.allowedApps,
                    scheduleWindows = latestDraft.rules.scheduleWindows,
                    dailyAllowanceMinutes =
                      latestDraft.rules.dailyAllowance.inWholeMinutes.toInt().coerceIn(0, 1440),
                    websiteFilteringEnabled = latestDraft.rules.websiteFilteringEnabled,
                    blockedDomains = latestDraft.rules.blockedDomains,
                    blockedDomainCategories = latestDraft.rules.blockedDomainCategories,
                    blockYouTubeShorts = latestDraft.rules.blockYouTubeShorts,
                    accessibilityHealth = healthSnapshot,
                    datasetMetadata = datasets,
                    datasetEvalEpochMillis = timeProvider.currentWallClock().epochMillis,
                    isPinConfigured = pinConfigured,
                    requirePinForEmergencyTermination =
                      latestDraft.rules.requirePinForEmergencyTermination,
                    validationMessages = emptyList(),
                  )
                editorState.value = hydrated
                CommitmentSetupUiState.Editing(hydrated)
              } else if (latestDraft == null && hydratedDraftId != null) {
                hydratedDraftId = null
                val reset =
                  DraftEditorState(
                    installedApps = editor.installedApps,
                    installedAppsLoaded = editor.installedAppsLoaded,
                    installedAppsError = editor.installedAppsError,
                    accessibilityHealth = healthSnapshot,
                    datasetMetadata = datasets,
                    datasetEvalEpochMillis = timeProvider.currentWallClock().epochMillis,
                    isPinConfigured = pinConfigured,
                  )
                editorState.value = reset
                CommitmentSetupUiState.Editing(reset)
              } else {
                CommitmentSetupUiState.Editing(
                  editor.copy(
                    existingDraft = latestDraft,
                    accessibilityHealth = healthSnapshot,
                    datasetMetadata = datasets,
                    datasetEvalEpochMillis = timeProvider.currentWallClock().epochMillis,
                    isPinConfigured = pinConfigured,
                  )
                )
              }
            }
          }
        }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CommitmentSetupUiState.Loading,
      )

  fun selectMode(mode: RestrictionMode) {
    updateEditor { it.copy(mode = mode, validationMessages = emptyList(), statusMessage = null) }
  }

  fun onRestrictedSearchChange(query: String) {
    updateEditor { it.copy(restrictedSearchQuery = query) }
  }

  fun onAllowlistSearchChange(query: String) {
    updateEditor { it.copy(allowlistSearchQuery = query) }
  }

  fun toggleInstalledRestrictedApp(appInfo: InstalledAppInfo) {
    val restrictedApp = RestrictedApp(appId = appInfo.appId, displayLabel = appInfo.displayLabel)
    toggleSuggestedRestrictedApp(restrictedApp)
  }

  fun toggleInstalledAllowedApp(appInfo: InstalledAppInfo) {
    val category =
      if (appInfo.isSystemEssential) AllowlistCategory.EMERGENCY_PHONE else AllowlistCategory.CUSTOM
    val allowedApp =
      AllowedApp(appId = appInfo.appId, displayLabel = appInfo.displayLabel, category = category)
    toggleSuggestedAllowedApp(allowedApp)
  }

  fun toggleSuggestedRestrictedApp(app: RestrictedApp) {
    updateEditor { current ->
      val updated =
        if (current.restrictedApps.any { it.appId == app.appId }) {
          current.restrictedApps.filterNot { it.appId == app.appId }.toSet()
        } else {
          current.restrictedApps + app
        }
      current.copy(restrictedApps = updated, validationMessages = emptyList(), statusMessage = null)
    }
  }

  fun onCustomRestrictedLabelChange(value: String) {
    updateEditor { it.copy(customRestrictedLabel = value, statusMessage = null) }
  }

  fun onCustomRestrictedPackageChange(value: String) {
    updateEditor { it.copy(customRestrictedPackage = value, statusMessage = null) }
  }

  fun addCustomRestrictedApp() {
    val current = editorState.value
    val label = current.customRestrictedLabel.trim()
    val pkg = current.customRestrictedPackage.trim()
    if (label.isBlank() || pkg.isBlank() || pkg.contains(" ")) {
      updateEditor {
        it.copy(
          validationMessages =
            listOf("Enter both a display name and a valid package ID without spaces."),
          statusMessage = null,
        )
      }
      return
    }
    val app = RestrictedApp(appId = AppId(pkg), displayLabel = label)
    updateEditor {
      it.copy(
        restrictedApps = it.restrictedApps.filterNot { existing -> existing.appId == app.appId }.toSet() + app,
        customRestrictedLabel = "",
        customRestrictedPackage = "",
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun removeRestrictedApp(appId: AppId) {
    updateEditor { current ->
      current.copy(
        restrictedApps = current.restrictedApps.filterNot { it.appId == appId }.toSet(),
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun toggleSuggestedAllowedApp(app: AllowedApp) {
    updateEditor { current ->
      val updated =
        if (current.allowedApps.any { it.appId == app.appId }) {
          current.allowedApps.filterNot { it.appId == app.appId }.toSet()
        } else {
          current.allowedApps + app
        }
      current.copy(allowedApps = updated, validationMessages = emptyList(), statusMessage = null)
    }
  }

  fun onCustomAllowedLabelChange(value: String) {
    updateEditor { it.copy(customAllowedLabel = value, statusMessage = null) }
  }

  fun onCustomAllowedPackageChange(value: String) {
    updateEditor { it.copy(customAllowedPackage = value, statusMessage = null) }
  }

  fun onCustomAllowedCategoryChange(category: AllowlistCategory) {
    updateEditor { it.copy(customAllowedCategory = category, statusMessage = null) }
  }

  fun addCustomAllowedApp() {
    val current = editorState.value
    val label = current.customAllowedLabel.trim()
    val pkg = current.customAllowedPackage.trim()
    if (label.isBlank() || pkg.isBlank() || pkg.contains(" ")) {
      updateEditor {
        it.copy(
          validationMessages =
            listOf("Enter both a display name and a valid allowlist package ID without spaces."),
          statusMessage = null,
        )
      }
      return
    }
    val app =
      AllowedApp(
        appId = AppId(pkg),
        displayLabel = label,
        category = current.customAllowedCategory,
      )
    updateEditor {
      it.copy(
        allowedApps = it.allowedApps.filterNot { existing -> existing.appId == app.appId }.toSet() + app,
        customAllowedLabel = "",
        customAllowedPackage = "",
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun removeAllowedApp(appId: AppId) {
    updateEditor { current ->
      current.copy(
        allowedApps = current.allowedApps.filterNot { it.appId == appId }.toSet(),
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun toggleScheduleDay(day: DomainDayOfWeek) {
    updateEditor { current ->
      val currentDays = current.windowDraft.daysOfWeek
      val nextDays = if (day in currentDays) currentDays - day else currentDays + day
      current.copy(
        windowDraft = current.windowDraft.copy(daysOfWeek = nextDays),
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun adjustWindowStart(deltaMinutes: Int) {
    updateEditor { current ->
      val totalMinutes =
        (current.windowDraft.startHour * 60 + current.windowDraft.startMinute + deltaMinutes)
          .mod(24 * 60)
      current.copy(
        windowDraft =
          current.windowDraft.copy(
            startHour = totalMinutes / 60,
            startMinute = totalMinutes % 60,
          ),
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun adjustWindowEnd(deltaMinutes: Int) {
    updateEditor { current ->
      val totalMinutes =
        (current.windowDraft.endHour * 60 + current.windowDraft.endMinute + deltaMinutes)
          .mod(24 * 60)
      current.copy(
        windowDraft =
          current.windowDraft.copy(
            endHour = totalMinutes / 60,
            endMinute = totalMinutes % 60,
          ),
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun addScheduleWindow() {
    val current = editorState.value
    val candidate = current.windowDraft
    val errors =
      CommitmentRulesValidator.validateCandidateWindow(
        daysOfWeek = candidate.daysOfWeek,
        start = candidate.startTime,
        end = candidate.endTime,
        existingWindows = current.scheduleWindows,
      )
    if (errors.isNotEmpty()) {
      updateEditor {
        it.copy(validationMessages = errors.map(::mapValidationError), statusMessage = null)
      }
      return
    }
    val window =
      ScheduleWindow(
        daysOfWeek = candidate.daysOfWeek,
        start = candidate.startTime,
        end = candidate.endTime,
      )
    updateEditor {
      it.copy(
        scheduleWindows = it.scheduleWindows + window,
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun removeScheduleWindow(index: Int) {
    updateEditor { current ->
      if (index !in current.scheduleWindows.indices) current
      else {
        current.copy(
          scheduleWindows = current.scheduleWindows.filterIndexed { i, _ -> i != index },
          validationMessages = emptyList(),
          statusMessage = null,
        )
      }
    }
  }

  fun adjustDailyAllowance(deltaMinutes: Int) {
    updateEditor { current ->
      val updated = (current.dailyAllowanceMinutes + deltaMinutes).coerceIn(0, 24 * 60)
      current.copy(
        dailyAllowanceMinutes = updated,
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun setDailyAllowanceMinutes(minutes: Int) {
    updateEditor { current ->
      current.copy(
        dailyAllowanceMinutes = minutes.coerceIn(0, 24 * 60),
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun setWebsiteFilteringEnabled(enabled: Boolean) {
    updateEditor { current ->
      current.copy(
        websiteFilteringEnabled = enabled,
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun onCustomDomainInputChange(value: String) {
    updateEditor { it.copy(customDomainInput = value, statusMessage = null) }
  }

  fun addCustomDomainRule(ruleType: DomainRuleType) {
    val current = editorState.value
    when (val normalized = DomainNormalizer.normalize(current.customDomainInput)) {
      is DomainNormalizationResult.Invalid -> {
        val reason =
          when (normalized.error) {
            DomainValidationError.EMPTY -> "Enter a domain name such as social.example."
            DomainValidationError.CONTAINS_URL_OR_PATH ->
              "Enter a domain name only (without https://, paths, ports, or wildcards)."
            DomainValidationError.CONTAINS_WHITESPACE_OR_CONTROL ->
              "Domain names cannot contain spaces or control characters."
            DomainValidationError.IP_LITERAL_NOT_ALLOWED ->
              "Enter a domain hostname, not an IP address literal."
            DomainValidationError.INVALID_IDN -> "Unable to normalize internationalized domain name."
            DomainValidationError.SINGLE_LABEL_NOT_ALLOWED ->
              "Enter a valid multi-label domain (for example, example.com)."
            DomainValidationError.INVALID_LABEL_SYNTAX ->
              "Invalid domain label syntax."
            DomainValidationError.TOO_LONG -> "Domain exceeds maximum hostname length (253 chars)."
          }
        updateEditor { it.copy(validationMessages = listOf(reason), statusMessage = null) }
      }
      is DomainNormalizationResult.Valid -> {
        val host = normalized.normalizedHost
        val oppositeType =
          if (ruleType == DomainRuleType.BLOCK) DomainRuleType.ALLOW else DomainRuleType.BLOCK
        if (current.blockedDomains.any { it.domain.value == host && it.ruleType == oppositeType }) {
          updateEditor {
            it.copy(
              validationMessages =
                listOf(
                  "Domain $host already exists as a ${oppositeType.name.lowercase()} rule. Remove the conflicting entry first."
                ),
              statusMessage = null,
            )
          }
          return
        }
        val entry =
          BlockedDomain(
            domain = DomainName(host),
            displayName = host,
            category = DomainCategory.CUSTOM,
            ruleType = ruleType,
          )
        updateEditor {
          it.copy(
            websiteFilteringEnabled = true,
            blockedDomains =
              it.blockedDomains.filterNot { existing ->
                existing.domain.value == host && existing.ruleType == ruleType
              }.toSet() + entry,
            customDomainInput = "",
            validationMessages = emptyList(),
            statusMessage = null,
          )
        }
      }
    }
  }

  fun removeDomainRule(domain: DomainName, ruleType: DomainRuleType) {
    updateEditor { current ->
      current.copy(
        blockedDomains =
          current.blockedDomains.filterNot {
            it.domain == domain && it.ruleType == ruleType
          }.toSet(),
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun setRequirePinForEmergencyTermination(requirePin: Boolean) {
    updateEditor { current ->
      current.copy(
        requirePinForEmergencyTermination = requirePin,
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun setBlockYouTubeShorts(enabled: Boolean) {
    updateEditor { current ->
      current.copy(
        blockYouTubeShorts = enabled,
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun toggleDomainCategory(category: DomainCategory) {
    if (category == DomainCategory.CUSTOM) return
    updateEditor { current ->
      val next =
        if (category in current.blockedDomainCategories) {
          current.blockedDomainCategories - category
        } else {
          current.blockedDomainCategories + category
        }
      current.copy(
        websiteFilteringEnabled = if (next.isNotEmpty()) true else current.websiteFilteringEnabled,
        blockedDomainCategories = next,
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun saveDraft() {
    val current = editorState.value
    if (current.isSaving) return

    val rules = buildRules(current)
    val draft = buildOrUpdateDraft(current.existingDraft, rules, timeProvider.currentWallClock())
    if (draft == null) {
      updateEditor {
        it.copy(validationMessages = listOf("Active commitment rules are locked and cannot be modified."))
      }
      return
    }

    updateEditor { it.copy(isSaving = true, validationMessages = emptyList(), statusMessage = null) }
    viewModelScope.launch {
      when (val result = commitmentRepository.saveCommitment(draft)) {
        is PersistenceResult.Success -> {
          hydratedDraftId = draft.id
          updateEditor {
            it.copy(
              existingDraft = draft,
              isSaving = false,
              statusMessage = "Draft saved locally.",
            )
          }
        }
        is PersistenceResult.Failure -> {
          updateEditor {
            it.copy(
              isSaving = false,
              validationMessages = listOf(result.error.toDisplayMessage()),
            )
          }
        }
      }
    }
  }

  fun continueToReview() {
    val current = editorState.value
    if (current.isSaving) return

    if (current.requirePinForEmergencyTermination && !current.isPinConfigured) {
      updateEditor {
        it.copy(
          validationMessages =
            listOf(
              "Accountability PIN protection is enabled, but no local PIN is configured yet. Configure a PIN in Settings first or turn off PIN protection."
            ),
          statusMessage = null,
        )
      }
      return
    }

    val rules = buildRules(current)
    when (val validation = CommitmentRulesValidator.validateForActivation(rules)) {
      is RulesValidationResult.Invalid -> {
        updateEditor {
          it.copy(
            validationMessages = validation.errors.map(::mapValidationError),
            statusMessage = null,
          )
        }
        return
      }
      RulesValidationResult.Valid -> Unit
    }

    val draft = buildOrUpdateDraft(current.existingDraft, rules, timeProvider.currentWallClock())
    if (draft == null) {
      updateEditor {
        it.copy(validationMessages = listOf("Active commitment rules are locked and cannot be modified."))
      }
      return
    }

    updateEditor { it.copy(isSaving = true, validationMessages = emptyList(), statusMessage = null) }
    viewModelScope.launch {
      when (val result = commitmentRepository.saveCommitment(draft)) {
        is PersistenceResult.Success -> {
          hydratedDraftId = draft.id
          updateEditor { it.copy(existingDraft = draft, isSaving = false) }
          _effects.send(CommitmentSetupEffect.NavigateToReview(draft.id.value))
        }
        is PersistenceResult.Failure -> {
          updateEditor {
            it.copy(
              isSaving = false,
              validationMessages = listOf(result.error.toDisplayMessage()),
            )
          }
        }
      }
    }
  }

  private fun buildRules(state: DraftEditorState): CommitmentRules =
    CommitmentRules(
      mode = state.mode,
      restrictedApps = state.restrictedApps,
      allowedApps = state.allowedApps,
      scheduleWindows = state.scheduleWindows,
      dailyAllowance = state.dailyAllowanceMinutes.minutes,
      requirePinForEmergencyTermination = state.requirePinForEmergencyTermination,
      websiteFilteringEnabled = state.websiteFilteringEnabled,
      blockedDomains = state.blockedDomains,
      blockedDomainCategories = state.blockedDomainCategories,
      blockYouTubeShorts = state.blockYouTubeShorts,
    )

  private fun buildOrUpdateDraft(
    existingDraft: Commitment.Draft?,
    rules: CommitmentRules,
    now: WallClockPoint,
  ): Commitment.Draft? =
    if (existingDraft == null) {
      CommitmentStateMachine.createDraft(
        id = CommitmentId(idGenerator.nextId()),
        rules = rules,
        now = now,
      )
    } else {
      when (val updated = CommitmentStateMachine.updateDraftRules(existingDraft, rules)) {
        is CommitmentResult.Success -> updated.commitment
        is CommitmentResult.Failure -> null
      }
    }

  private fun updateEditor(transform: (DraftEditorState) -> DraftEditorState) {
    editorState.value = transform(editorState.value)
  }

  private fun mapValidationError(error: RulesValidationError): String =
    when (error) {
      RulesValidationError.RestrictedAppsRequired ->
        "Select at least one restricted app, enable YouTube Shorts restriction, or configure a website filter target before reviewing your commitment."
      is RulesValidationError.ConflictingAppSelections ->
        "These apps appear in both Restricted and Allowlist (${error.overlappingPackageNames.joinToString(", ")}). Remove the conflict before continuing."
      RulesValidationError.ScheduleWindowsRequiredForScheduledMode ->
        "Scheduled mode requires at least one schedule window."
      RulesValidationError.ScheduleWindowDaysRequired ->
        "Select at least one day of the week for the schedule window."
      RulesValidationError.ScheduleWindowZeroLength ->
        "Schedule start and end times cannot be identical."
      RulesValidationError.DuplicateScheduleWindow ->
        "This exact schedule window has already been added."
      RulesValidationError.DailyAllowanceOutOfRange ->
        "Daily allowance must be between 0 minutes and 24 hours."
      is RulesValidationError.InvalidBlockedDomains ->
        "Invalid domain entries (${error.rawDomains.joinToString(", ")}). Use hostnames without schemes, paths, ports, or IP literals."
      is RulesValidationError.ConflictingDomainRules ->
        "These domains appear in both Block and Allow exception lists (${error.overlappingDomains.joinToString(", ")}). Remove the conflict before continuing."
      RulesValidationError.WebsiteFilteringTargetsRequired ->
        "Website filtering is enabled, but no blocked domains or categories are selected."
    }
}
