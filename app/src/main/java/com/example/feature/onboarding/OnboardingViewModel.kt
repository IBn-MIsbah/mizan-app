package com.example.feature.onboarding

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.core.common.IdGenerator
import com.example.core.common.toDisplayMessage
import com.example.core.navigation.MizanRoute
import com.example.domain.filter.DomainNormalizationResult
import com.example.domain.filter.DomainNormalizer
import com.example.domain.filter.DomainValidationError
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentRulesValidator
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.CommitmentTransitionError
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
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ScheduleWindow
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.pin.AccountabilityPinRepository
import com.example.domain.repository.AccountabilityMetadataRepository
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.InstalledAppRepository
import com.example.domain.repository.InstalledAppsResult
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import com.example.feature.commitments.DraftEditorState
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.health.EnforcementHealthSnapshot
import com.example.platform.health.UsageAccessMonitor
import com.example.platform.health.UsageAccessSnapshot
import com.example.platform.vpn.VpnFilterMonitor
import com.example.platform.vpn.VpnFilterSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class OnboardingStage(val stepNumber: Int) {
  INTRODUCTION(1),
  PERMISSIONS(2),
  CONFIGURATION(3),
  REVIEW(4),
  AGREEMENT(5),
  ;

  fun previous(): OnboardingStage? =
    when (this) {
      INTRODUCTION -> null
      PERMISSIONS -> INTRODUCTION
      CONFIGURATION -> PERMISSIONS
      REVIEW -> CONFIGURATION
      AGREEMENT -> REVIEW
    }
}

sealed interface OnboardingUiState {
  data class Ready(
    val stage: OnboardingStage = OnboardingStage.INTRODUCTION,
    val revisitFromSettings: Boolean = false,
    val healthSnapshot: EnforcementHealthSnapshot = EnforcementHealthSnapshot(),
    val usageAccessSnapshot: UsageAccessSnapshot = UsageAccessSnapshot(),
    val vpnSnapshot: VpnFilterSnapshot = VpnFilterSnapshot(),
    val vpnPrepared: Boolean = false,
    val editor: DraftEditorState = DraftEditorState(),
    val activeCommitmentLocked: Boolean = false,
    val previewStart: WallClockPoint? = null,
    val previewEnd: WallClockPoint? = null,
    val agreedToExactRules: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
  ) : OnboardingUiState
}

sealed interface OnboardingEffect {
  data object NavigateToDashboard : OnboardingEffect
}

@HiltViewModel
class OnboardingViewModel
@Inject
constructor(
  private val userPreferencesRepository: UserPreferencesRepository,
  private val commitmentRepository: CommitmentRepository,
  private val installedAppRepository: InstalledAppRepository,
  private val domainFilterRepository: DomainFilterRepository,
  private val accountabilityMetadataRepository: AccountabilityMetadataRepository,
  private val accountabilityPinRepository: AccountabilityPinRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val enforcementHealthMonitor: EnforcementHealthMonitor,
  private val usageAccessMonitor: UsageAccessMonitor,
  private val vpnFilterMonitor: VpnFilterMonitor,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
  private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val revisitFromSettings: Boolean =
    runCatching { savedStateHandle.toRoute<MizanRoute.Onboarding>().revisitFromSettings }
      .getOrElse { savedStateHandle.get<Boolean>("revisitFromSettings") ?: false }

  private val initialStage: OnboardingStage =
    savedStateHandle
      .get<String>(KEY_ONBOARDING_STAGE)
      ?.let { raw -> runCatching { OnboardingStage.valueOf(raw) }.getOrNull() }
      ?: OnboardingStage.INTRODUCTION

  private val stageFlow = MutableStateFlow(initialStage)
  private val editorFlow = MutableStateFlow(DraftEditorState())
  private val transientFlow = MutableStateFlow(OnboardingTransientState())
  private var hydratedDraftId: CommitmentId? = null

  private val _effects = Channel<OnboardingEffect>(Channel.BUFFERED)
  val effects = _effects.receiveAsFlow()

  init {
    refreshInstalledApps()
    refreshPermissions()
    viewModelScope.launch {
      runCatching { domainFilterRepository.ensureDefaultDatasetsInitialized() }
    }
  }

  private val monitorsFlow =
    combine(
      enforcementHealthMonitor.healthState,
      usageAccessMonitor.usageAccessState,
      vpnFilterMonitor.vpnState,
    ) { health, usageAccess, vpn ->
      MonitorBundle(health = health, usageAccess = usageAccess, vpn = vpn)
    }

  private val repositoryDataFlow =
    combine(
      commitmentRepository.observeAllCommitments(),
      domainFilterRepository.observeDatasets(),
      accountabilityMetadataRepository.observeMetadata(),
    ) { commitmentsResult, datasetsResult, metadataResult ->
      RepositoryBundle(
        commitmentsResult = commitmentsResult,
        datasetsResult = datasetsResult,
        metadataResult = metadataResult,
      )
    }

  val uiState: StateFlow<OnboardingUiState> =
    combine(stageFlow, editorFlow, transientFlow, monitorsFlow, repositoryDataFlow) {
      stage,
      editor,
      transient,
      monitors,
      repoData ->
      val datasets =
        when (val ds = repoData.datasetsResult) {
          is PersistenceResult.Success ->
            if (ds.data.isEmpty()) DatasetSeedDefaults.defaultMetadata() else ds.data
          is PersistenceResult.Failure -> editor.datasetMetadata
        }
      val pinConfigured =
        repoData.metadataResult is PersistenceResult.Success && repoData.metadataResult.data.isPinEnabled
      val now = timeProvider.currentWallClock()

      val commitments = (repoData.commitmentsResult as? PersistenceResult.Success)?.data.orEmpty()
      val activeCommitment = commitments.firstOrNull { it is Commitment.Active }
      val latestDraft = commitments.firstOrNull { it is Commitment.Draft } as? Commitment.Draft

      val currentEditor =
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
              datasetMetadata = datasets,
              datasetEvalEpochMillis = now.epochMillis,
              isPinConfigured = pinConfigured,
              requirePinForEmergencyTermination = latestDraft.rules.requirePinForEmergencyTermination,
              validationMessages = emptyList(),
            )
          editorFlow.value = hydrated
          hydrated
        } else {
          editor.copy(
            existingDraft = latestDraft ?: editor.existingDraft,
            datasetMetadata = datasets,
            datasetEvalEpochMillis = now.epochMillis,
            isPinConfigured = pinConfigured,
          )
        }

      val isVpnPrepared =
        monitors.vpn.isPermissionPrepared ||
          monitors.vpn.isTunnelEstablished ||
          vpnFilterMonitor.prepareVpnIntent() == null

      OnboardingUiState.Ready(
        stage = stage,
        revisitFromSettings = revisitFromSettings,
        healthSnapshot = monitors.health,
        usageAccessSnapshot = monitors.usageAccess,
        vpnSnapshot = monitors.vpn,
        vpnPrepared = isVpnPrepared,
        editor = currentEditor,
        activeCommitmentLocked = activeCommitment != null,
        previewStart = now,
        previewEnd = now.plusElapsedDays(Commitment.INITIAL_DURATION_DAYS),
        agreedToExactRules = transient.agreedToExactRules,
        isSubmitting = transient.isSubmitting,
        errorMessage = transient.errorMessage,
      )
    }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue =
          OnboardingUiState.Ready(
            stage = initialStage,
            revisitFromSettings = revisitFromSettings,
          ),
      )

  fun refreshPermissions() {
    enforcementHealthMonitor.refreshSystemStatus()
    usageAccessMonitor.refreshSystemStatus()
    vpnFilterMonitor.refreshSystemStatus()
  }

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

  fun createAccessibilitySettingsIntent(): Intent =
    enforcementHealthMonitor.createAccessibilitySettingsIntent()

  fun createUsageAccessSettingsIntent(): Intent =
    usageAccessMonitor.createUsageAccessSettingsIntent()

  fun prepareVpnIntent(): Intent? = vpnFilterMonitor.prepareVpnIntent()

  fun onVpnPermissionResult(granted: Boolean) {
    viewModelScope.launch {
      if (granted) {
        userPreferencesRepository.setWebsiteFilteringConsented(true)
        vpnFilterMonitor.refreshSystemStatus()
      } else {
        vpnFilterMonitor.markPermissionDenied()
      }
    }
  }

  fun goToStage(stage: OnboardingStage) {
    savedStateHandle[KEY_ONBOARDING_STAGE] = stage.name
    stageFlow.value = stage
    transientFlow.value =
      transientFlow.value.copy(
        errorMessage = null,
        agreedToExactRules =
          if (stage == OnboardingStage.AGREEMENT) transientFlow.value.agreedToExactRules else false,
      )
  }

  fun goBackStage(): Boolean {
    val prev = stageFlow.value.previous() ?: return false
    goToStage(prev)
    return true
  }

  fun advanceFromIntroduction() {
    goToStage(OnboardingStage.PERMISSIONS)
  }

  fun advanceFromPermissions() {
    goToStage(OnboardingStage.CONFIGURATION)
  }

  fun advanceFromConfiguration() {
    val current = uiState.value as? OnboardingUiState.Ready ?: return
    if (current.isSubmitting) return
    if (current.activeCommitmentLocked) {
      transientFlow.value =
        transientFlow.value.copy(
          errorMessage = "An active commitment already exists. View it on Balance."
        )
      return
    }

    val editor = current.editor
    if (editor.requirePinForEmergencyTermination && !editor.isPinConfigured) {
      updateEditor {
        it.copy(
          validationMessages =
            listOf(
              "Accountability PIN protection is enabled, but no local PIN is configured yet. Configure one in Settings first or turn off PIN protection."
            ),
          statusMessage = null,
        )
      }
      return
    }

    val rules = buildRules(editor)
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

    val draft = buildOrUpdateDraft(editor.existingDraft, rules, timeProvider.currentWallClock())
    if (draft == null) {
      updateEditor {
        it.copy(validationMessages = listOf("Active commitment rules are locked."))
      }
      return
    }

    transientFlow.value = transientFlow.value.copy(isSubmitting = true, errorMessage = null)
    viewModelScope.launch {
      when (val saveResult = commitmentRepository.saveCommitment(draft)) {
        is PersistenceResult.Success -> {
          hydratedDraftId = draft.id
          updateEditor {
            it.copy(existingDraft = draft, validationMessages = emptyList(), statusMessage = null)
          }
          transientFlow.value = transientFlow.value.copy(isSubmitting = false, errorMessage = null)
          goToStage(OnboardingStage.REVIEW)
        }
        is PersistenceResult.Failure -> {
          transientFlow.value =
            transientFlow.value.copy(
              isSubmitting = false,
              errorMessage = saveResult.error.toDisplayMessage(),
            )
        }
      }
    }
  }

  fun advanceFromReview() {
    goToStage(OnboardingStage.AGREEMENT)
  }

  fun setAgreementChecked(agreed: Boolean) {
    transientFlow.value =
      transientFlow.value.copy(agreedToExactRules = agreed, errorMessage = null)
  }

  fun activateCommitment() {
    val current = uiState.value as? OnboardingUiState.Ready ?: return
    if (current.isSubmitting) return

    if (current.activeCommitmentLocked) {
      transientFlow.value =
        transientFlow.value.copy(
          errorMessage =
            "Another commitment is already active. Complete or terminate it before activating a new one."
        )
      return
    }

    val rules = buildRules(current.editor)
    if (CommitmentRulesValidator.validateForActivation(rules) !is RulesValidationResult.Valid) {
      transientFlow.value =
        transientFlow.value.copy(
          errorMessage =
            "Configuration is incomplete or invalid. Return to Setup step to resolve rule issues."
        )
      return
    }

    val now = timeProvider.currentWallClock()
    val draft =
      buildOrUpdateDraft(current.editor.existingDraft, rules, now) ?: run {
        transientFlow.value =
          transientFlow.value.copy(errorMessage = "Unable to prepare commitment draft.")
        return
      }

    val agreedToExactRules = transientFlow.value.agreedToExactRules
    when (
      val activationResult =
        CommitmentStateMachine.activate(
          commitment = draft,
          agreedToExactRules = agreedToExactRules,
          now = now,
        )
    ) {
      is CommitmentResult.Failure -> {
        transientFlow.value =
          transientFlow.value.copy(errorMessage = mapTransitionError(activationResult.error))
      }
      is CommitmentResult.Success -> {
        val active = activationResult.commitment
        transientFlow.value = transientFlow.value.copy(isSubmitting = true, errorMessage = null)
        viewModelScope.launch {
          if (active.rules.requirePinForEmergencyTermination) {
            val hasPinCredential =
              runCatching { accountabilityPinRepository.isCredentialAvailable() }.getOrDefault(false)
            if (!hasPinCredential) {
              transientFlow.value =
                transientFlow.value.copy(
                  isSubmitting = false,
                  errorMessage =
                    "Accountability PIN protection is enabled, but no valid local PIN credential is available. Configure a PIN in Settings or turn off PIN protection.",
                )
              return@launch
            }
          }

          when (val saveResult = commitmentRepository.saveCommitment(active)) {
            is PersistenceResult.Failure -> {
              transientFlow.value =
                transientFlow.value.copy(
                  isSubmitting = false,
                  errorMessage = saveResult.error.toDisplayMessage(),
                )
            }
            is PersistenceResult.Success -> {
              securityEventRepository.recordEvent(
                SecurityEvent(
                  id = SecurityEventId(idGenerator.nextId()),
                  commitmentId = active.id,
                  eventType = SecurityEventType.COMMITMENT_ACTIVATED,
                  occurredAt = active.activatedAt,
                  summary = "7-day commitment activated with explicit agreement during onboarding",
                )
              )
              if (active.rules.hasConfiguredWebsiteFiltering()) {
                val prefs = runCatching { userPreferencesRepository.preferences.first() }.getOrNull()
                val canAutoStart =
                  prefs != null &&
                    prefs.websiteFilteringConsented &&
                    !prefs.vpnStoppedByUser &&
                    vpnFilterMonitor.prepareVpnIntent() == null
                if (canAutoStart) {
                  runCatching { vpnFilterMonitor.requestStartFiltering() }
                } else {
                  vpnFilterMonitor.refreshSystemStatus()
                }
              }
              when (val prefResult = userPreferencesRepository.setOnboardingCompleted(true)) {
                is PersistenceResult.Failure -> {
                  transientFlow.value =
                    transientFlow.value.copy(
                      isSubmitting = false,
                      errorMessage = prefResult.error.toDisplayMessage(),
                    )
                }
                is PersistenceResult.Success -> {
                  transientFlow.value =
                    transientFlow.value.copy(isSubmitting = false, errorMessage = null)
                  _effects.send(OnboardingEffect.NavigateToDashboard)
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * Safely skips onboarding to the Balance dashboard. Preserves any partial draft rules without ever
   * activating a commitment or bypassing mandatory explicit agreement.
   */
  fun skipOnboarding() {
    val current = uiState.value as? OnboardingUiState.Ready ?: return
    if (current.isSubmitting) return

    transientFlow.value = transientFlow.value.copy(isSubmitting = true, errorMessage = null)
    viewModelScope.launch {
      if (!current.activeCommitmentLocked && hasPartialDraftChanges(current.editor)) {
        val rules = buildRules(current.editor)
        val draft = buildOrUpdateDraft(current.editor.existingDraft, rules, timeProvider.currentWallClock())
        if (draft != null) {
          val draftSave = commitmentRepository.saveCommitment(draft)
          if (draftSave is PersistenceResult.Success) {
            hydratedDraftId = draft.id
            updateEditor { it.copy(existingDraft = draft) }
          }
        }
      }
      when (val result = userPreferencesRepository.setOnboardingCompleted(true)) {
        is PersistenceResult.Success -> {
          transientFlow.value = transientFlow.value.copy(isSubmitting = false, errorMessage = null)
          _effects.send(OnboardingEffect.NavigateToDashboard)
        }
        is PersistenceResult.Failure -> {
          transientFlow.value =
            transientFlow.value.copy(
              isSubmitting = false,
              errorMessage = result.error.toDisplayMessage(),
            )
        }
      }
    }
  }

  fun completeOnboarding() {
    skipOnboarding()
  }

  // Configuration delegates matching Setup behavior
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
    updateEditor { current ->
      val updated =
        if (current.restrictedApps.any { it.appId == restrictedApp.appId }) {
          current.restrictedApps.filterNot { it.appId == restrictedApp.appId }.toSet()
        } else {
          current.restrictedApps + restrictedApp
        }
      current.copy(restrictedApps = updated, validationMessages = emptyList(), statusMessage = null)
    }
  }

  fun toggleInstalledAllowedApp(appInfo: InstalledAppInfo) {
    val category =
      if (appInfo.isSystemEssential) AllowlistCategory.EMERGENCY_PHONE else AllowlistCategory.CUSTOM
    val allowedApp =
      AllowedApp(appId = appInfo.appId, displayLabel = appInfo.displayLabel, category = category)
    updateEditor { current ->
      val updated =
        if (current.allowedApps.any { it.appId == allowedApp.appId }) {
          current.allowedApps.filterNot { it.appId == allowedApp.appId }.toSet()
        } else {
          current.allowedApps + allowedApp
        }
      current.copy(allowedApps = updated, validationMessages = emptyList(), statusMessage = null)
    }
  }

  fun onCustomRestrictedLabelChange(value: String) {
    updateEditor { it.copy(customRestrictedLabel = value, statusMessage = null) }
  }

  fun onCustomRestrictedPackageChange(value: String) {
    updateEditor { it.copy(customRestrictedPackage = value, statusMessage = null) }
  }

  fun addCustomRestrictedApp() {
    val current = editorFlow.value
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
        restrictedApps =
          it.restrictedApps.filterNot { existing -> existing.appId == app.appId }.toSet() + app,
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
    val current = editorFlow.value
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
        allowedApps =
          it.allowedApps.filterNot { existing -> existing.appId == app.appId }.toSet() + app,
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
    val current = editorFlow.value
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

  fun setDailyAllowanceMinutes(minutes: Int) {
    updateEditor { current ->
      current.copy(
        dailyAllowanceMinutes = minutes.coerceIn(0, 24 * 60),
        validationMessages = emptyList(),
        statusMessage = null,
      )
    }
  }

  fun adjustDailyAllowance(deltaMinutes: Int) {
    updateEditor { current ->
      current.copy(
        dailyAllowanceMinutes = (current.dailyAllowanceMinutes + deltaMinutes).coerceIn(0, 24 * 60),
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
    val current = editorFlow.value
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
            DomainValidationError.INVALID_LABEL_SYNTAX -> "Invalid domain label syntax."
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
                  "Domain $host already exists as a ${oppositeType.name.lowercase()} rule. Remove it first."
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

  private fun hasPartialDraftChanges(editor: DraftEditorState): Boolean =
    editor.existingDraft != null ||
      editor.restrictedApps.isNotEmpty() ||
      editor.allowedApps.isNotEmpty() ||
      editor.scheduleWindows.isNotEmpty() ||
      editor.websiteFilteringEnabled ||
      editor.blockedDomains.isNotEmpty() ||
      editor.blockedDomainCategories.isNotEmpty() ||
      editor.blockYouTubeShorts ||
      editor.requirePinForEmergencyTermination ||
      editor.dailyAllowanceMinutes != 30 ||
      editor.mode != RestrictionMode.HYBRID

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
    editorFlow.value = transform(editorFlow.value)
  }

  private fun mapValidationError(error: RulesValidationError): String =
    when (error) {
      RulesValidationError.RestrictedAppsRequired ->
        "Select at least one restricted app, enable YouTube Shorts restriction, or configure a website filter target."
      is RulesValidationError.ConflictingAppSelections ->
        "Apps appear in both Restricted and Allowlist (${error.overlappingPackageNames.joinToString(", ")})."
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
        "Invalid domain entries (${error.rawDomains.joinToString(", ")})."
      is RulesValidationError.ConflictingDomainRules ->
        "Domains appear in both Block and Allow lists (${error.overlappingDomains.joinToString(", ")})."
      RulesValidationError.WebsiteFilteringTargetsRequired ->
        "Website filtering is enabled, but no blocked domains or categories are selected."
    }

  private fun mapTransitionError(error: CommitmentTransitionError): String =
    when (error) {
      CommitmentTransitionError.ExplicitAgreementRequired ->
        "Explicit agreement is required before activating this 7-day commitment."
      CommitmentTransitionError.RulesLockedWhileNotDraft ->
        "Rules are locked because this commitment is not a draft."
      is CommitmentTransitionError.InvalidStateTransition ->
        "Cannot activate from state ${error.fromState}."
      else -> "Unable to activate commitment."
    }

  private data class MonitorBundle(
    val health: EnforcementHealthSnapshot,
    val usageAccess: UsageAccessSnapshot,
    val vpn: VpnFilterSnapshot,
  )

  private data class RepositoryBundle(
    val commitmentsResult: PersistenceResult<List<Commitment>>,
    val datasetsResult: PersistenceResult<List<com.example.domain.model.FilterDatasetMetadata>>,
    val metadataResult: PersistenceResult<com.example.domain.model.AccountabilityMetadata>,
  )

  private data class OnboardingTransientState(
    val agreedToExactRules: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
  )

  companion object {
    internal const val KEY_ONBOARDING_STAGE = "onboarding_stage"
  }
}
