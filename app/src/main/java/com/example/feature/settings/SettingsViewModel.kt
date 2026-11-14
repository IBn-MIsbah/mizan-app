package com.example.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.core.common.IdGenerator
import com.example.core.common.toDisplayMessage
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.PinVerificationResult
import com.example.domain.lifecycle.RemovalActionError
import com.example.domain.lifecycle.RemovalActionResult
import com.example.domain.lifecycle.RemovalRequestStateMachine
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.model.AppPreferences
import com.example.domain.model.Commitment
import com.example.domain.model.DatasetAvailabilityStatus
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DatasetStatusEvaluator
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.model.RemovalRequest
import com.example.domain.model.RemovalRequestId
import com.example.domain.model.RemovalRequestStatus
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.model.ThemePreference
import com.example.domain.pin.AccountabilityPinRepository
import com.example.domain.pin.PinConfigureResult
import com.example.domain.pin.PinFormatValidator
import com.example.domain.pin.PinValidationError
import com.example.domain.repository.AccountabilityMetadataRepository
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.RemovalRequestRepository
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import com.example.platform.filter.DatasetSyncController
import com.example.platform.filter.DatasetSyncUiSnapshot
import com.example.platform.filter.DatasetUpdateScheduler
import com.example.platform.health.BatteryOptimizationMonitor
import com.example.platform.health.BatteryOptimizationSnapshot
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.health.EnforcementHealthSnapshot
import com.example.platform.health.UsageAccessMonitor
import com.example.platform.health.UsageAccessSnapshot
import com.example.platform.usage.BootSessionProvider
import com.example.platform.usage.UsageStatsReconciler
import com.example.platform.vpn.VpnFilterMonitor
import com.example.platform.vpn.VpnFilterSnapshot
import com.example.platform.vpn.VpnStartRequestOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PinManagementUiState(
  val isPinEnabled: Boolean = false,
  val isCredentialAvailable: Boolean = false,
  val currentPinInput: String = "",
  val newPinInput: String = "",
  val lockoutRemainingSeconds: Long = 0L,
  val isSubmitting: Boolean = false,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

enum class RemovalUiStatus {
  NONE,
  PENDING,
  READY_TO_EXECUTE,
  CLOCK_ANOMALY_BLOCKED,
}

data class RemovalRequestUiState(
  val status: RemovalUiStatus = RemovalUiStatus.NONE,
  val requestId: String? = null,
  val requestedAt: WallClockPoint? = null,
  val unlocksAt: WallClockPoint? = null,
  val remainingDuration: Duration = Duration.ZERO,
  val hasActiveCommitment: Boolean = false,
  val isSubmitting: Boolean = false,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

data class DatasetManagementItem(
  val metadata: FilterDatasetMetadata,
  val availabilityStatus: DatasetAvailabilityStatus,
)

data class SettingsSummary(
  val preferences: AppPreferences,
  val applicationId: String,
  val versionName: String,
  val healthSnapshot: EnforcementHealthSnapshot = EnforcementHealthSnapshot(),
  val usageAccessSnapshot: UsageAccessSnapshot = UsageAccessSnapshot(),
  val batteryOptimizationSnapshot: BatteryOptimizationSnapshot = BatteryOptimizationSnapshot(),
  val vpnFilterSnapshot: VpnFilterSnapshot = VpnFilterSnapshot(),
  val datasets: List<FilterDatasetMetadata> = DatasetSeedDefaults.defaultMetadata(),
  val datasetItems: List<DatasetManagementItem> = emptyList(),
  val datasetSyncSnapshot: DatasetSyncUiSnapshot = DatasetSyncUiSnapshot(),
  val pinState: PinManagementUiState = PinManagementUiState(),
  val removalState: RemovalRequestUiState = RemovalRequestUiState(),
  val errorMessage: String? = null,
)

sealed interface SettingsUiState {
  data object Loading : SettingsUiState

  data class Ready(val summary: SettingsSummary) : SettingsUiState
}

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
  private val userPreferencesRepository: UserPreferencesRepository,
  private val enforcementHealthMonitor: EnforcementHealthMonitor,
  private val usageAccessMonitor: UsageAccessMonitor,
  private val batteryOptimizationMonitor: BatteryOptimizationMonitor,
  private val vpnFilterMonitor: VpnFilterMonitor,
  private val domainFilterRepository: DomainFilterRepository,
  private val datasetSyncController: DatasetSyncController,
  private val datasetUpdateScheduler: DatasetUpdateScheduler,
  private val commitmentRepository: CommitmentRepository,
  private val accountabilityMetadataRepository: AccountabilityMetadataRepository,
  private val accountabilityPinRepository: AccountabilityPinRepository,
  private val removalRequestRepository: RemovalRequestRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val usageStatsReconciler: UsageStatsReconciler,
  private val bootSessionProvider: BootSessionProvider,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
) : ViewModel() {

  private val _errorMessage = MutableStateFlow<String?>(null)
  private val _pinDraft = MutableStateFlow(PinDraftInternal())
  private val _removalTransient = MutableStateFlow(RemovalTransientInternal())
  private val _pinVaultInfo = MutableStateFlow(PinVaultSnapshot())
  private val _evalTick = MutableStateFlow(0)

  init {
    viewModelScope.launch {
      runCatching { domainFilterRepository.ensureDefaultDatasetsInitialized() }
      refreshPinVaultState()
    }
  }

  private val systemHealthFlow =
    combine(
      userPreferencesRepository.preferences,
      enforcementHealthMonitor.healthState,
      usageAccessMonitor.usageAccessState,
      batteryOptimizationMonitor.batteryState,
      vpnFilterMonitor.vpnState,
    ) { prefs, health, usageAccess, battery, vpn ->
      SystemHealthBundle(prefs, health, usageAccess, battery, vpn)
    }

  private val securityDataFlow =
    combine(
      domainFilterRepository.observeDatasets(),
      accountabilityMetadataRepository.observeMetadata(),
      removalRequestRepository.observeLatestRequest(),
      commitmentRepository.observeActiveCommitment(),
      _evalTick,
    ) { datasetsResult, metadataResult, removalResult, activeResult, _ ->
      SecurityDataBundle(datasetsResult, metadataResult, removalResult, activeResult)
    }

  private val transientUiFlow =
    combine(_removalTransient, _errorMessage, datasetSyncController.syncState) {
      removal,
      err,
      datasetSync ->
      TransientUiBundle(removal, err, datasetSync)
    }

  val uiState: StateFlow<SettingsUiState> =
    combine(
        systemHealthFlow,
        securityDataFlow,
        _pinDraft,
        _pinVaultInfo,
        transientUiFlow,
      ) { sys, sec, pinDraft, vaultInfo, transientUi ->
        val datasets =
          when (val res = sec.datasetsResult) {
            is PersistenceResult.Success ->
              if (res.data.isEmpty()) DatasetSeedDefaults.defaultMetadata() else res.data
            is PersistenceResult.Failure -> DatasetSeedDefaults.defaultMetadata()
          }
        val nowEpochMillis = timeProvider.currentWallClock().epochMillis
        val datasetItems =
          datasets.map { meta ->
            DatasetManagementItem(
              metadata = meta,
              availabilityStatus =
                DatasetStatusEvaluator.evaluate(
                  metadata = meta,
                  isUpdating = meta.datasetId in transientUi.datasetSync.updatingDatasetIds,
                  nowEpochMillis = nowEpochMillis,
                ),
            )
          }
        val metadata =
          (sec.metadataResult as? PersistenceResult.Success)?.data ?: AccountabilityMetadata()
        val activeCommitment =
          (sec.activeResult as? PersistenceResult.Success)?.data as? Commitment.Active

        val pinUiState =
          PinManagementUiState(
            isPinEnabled = metadata.isPinEnabled,
            isCredentialAvailable = vaultInfo.available,
            currentPinInput = pinDraft.currentPin,
            newPinInput = pinDraft.newPin,
            lockoutRemainingSeconds = vaultInfo.lockoutRemainingSeconds,
            isSubmitting = pinDraft.isSubmitting,
            statusMessage = pinDraft.statusMessage,
            errorMessage = pinDraft.errorMessage,
          )

        val latestRequest = (sec.removalResult as? PersistenceResult.Success)?.data
        val removalUiState =
          buildRemovalUiState(
            latestRequest = latestRequest,
            hasActiveCommitment = activeCommitment != null,
            transient = transientUi.removal,
          )

        SettingsUiState.Ready(
          SettingsSummary(
            preferences = sys.prefs,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            healthSnapshot = sys.health,
            usageAccessSnapshot = sys.usageAccess,
            batteryOptimizationSnapshot = sys.battery,
            vpnFilterSnapshot = sys.vpn,
            datasets = datasets,
            datasetItems = datasetItems,
            datasetSyncSnapshot = transientUi.datasetSync,
            pinState = pinUiState,
            removalState = removalUiState,
            errorMessage = transientUi.errorMessage,
          )
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState.Loading,
      )

  fun refreshEnforcementHealth() {
    enforcementHealthMonitor.refreshSystemStatus()
    usageAccessMonitor.refreshSystemStatus()
    batteryOptimizationMonitor.refreshSystemStatus()
    vpnFilterMonitor.refreshSystemStatus()
    _evalTick.value += 1
    viewModelScope.launch {
      refreshPinVaultState()
      val activeRes =
        runCatching { commitmentRepository.observeActiveCommitment().first() }.getOrNull()
      val active =
        (activeRes as? PersistenceResult.Success)?.data as? Commitment.Active ?: return@launch
      runCatching { usageStatsReconciler.reconcileActiveCommitment(active) }
    }
  }

  fun createAccessibilitySettingsIntent(): Intent =
    enforcementHealthMonitor.createAccessibilitySettingsIntent()

  fun createUsageAccessSettingsIntent(): Intent =
    usageAccessMonitor.createUsageAccessSettingsIntent()

  fun createBatteryOptimizationSettingsIntent(): Intent =
    batteryOptimizationMonitor.createBatteryOptimizationSettingsIntent()

  fun createAppDetailsSettingsIntent(): Intent =
    batteryOptimizationMonitor.createAppDetailsSettingsIntent()

  fun prepareVpnIntent(): Intent? = vpnFilterMonitor.prepareVpnIntent()

  fun setWebsiteFilteringConsent(consented: Boolean) {
    _errorMessage.value = null
    viewModelScope.launch {
      val result = userPreferencesRepository.setWebsiteFilteringConsented(consented)
      if (result is PersistenceResult.Failure) {
        _errorMessage.value = result.error.toDisplayMessage()
      }
    }
  }

  fun onVpnPermissionResult(granted: Boolean) {
    viewModelScope.launch {
      if (granted) {
        vpnFilterMonitor.refreshSystemStatus()
        vpnFilterMonitor.requestStartFiltering()
      } else {
        vpnFilterMonitor.markPermissionDenied()
      }
    }
  }

  fun requestStartVpnFiltering() {
    viewModelScope.launch {
      val outcome = vpnFilterMonitor.requestStartFiltering()
      if (outcome == VpnStartRequestOutcome.PERMISSION_REQUIRED) {
        vpnFilterMonitor.refreshSystemStatus()
      }
    }
  }

  fun requestStopVpnFiltering() {
    viewModelScope.launch { vpnFilterMonitor.requestStopFiltering(markStoppedByUser = true) }
  }

  fun setThemePreference(themePreference: ThemePreference) {
    _errorMessage.value = null
    viewModelScope.launch {
      val result = userPreferencesRepository.setThemePreference(themePreference)
      if (result is PersistenceResult.Failure) {
        _errorMessage.value = result.error.toDisplayMessage()
      }
    }
  }

  fun setWifiOnlyFilterUpdates(wifiOnly: Boolean) {
    _errorMessage.value = null
    viewModelScope.launch {
      val result = userPreferencesRepository.setWifiOnlyFilterUpdates(wifiOnly)
      if (result is PersistenceResult.Failure) {
        _errorMessage.value = result.error.toDisplayMessage()
      } else {
        runCatching { datasetUpdateScheduler.syncPeriodicSchedule(wifiOnly) }
      }
    }
  }

  fun setReduceGuideAnimations(reduce: Boolean) {
    _errorMessage.value = null
    viewModelScope.launch {
      val result = userPreferencesRepository.setReduceGuideAnimations(reduce)
      if (result is PersistenceResult.Failure) {
        _errorMessage.value = result.error.toDisplayMessage()
      }
    }
  }

  fun replayFirstUseWalkthrough() {
    _errorMessage.value = null
    viewModelScope.launch {
      val result = userPreferencesRepository.setFirstUseGuideCompleted(false)
      if (result is PersistenceResult.Failure) {
        _errorMessage.value = result.error.toDisplayMessage()
      }
    }
  }

  fun checkDatasetUpdatesNow() {
    _errorMessage.value = null
    viewModelScope.launch {
      datasetSyncController.runUpdateCheck(targetDatasetId = null)
      val wifiOnly =
        runCatching { userPreferencesRepository.preferences.first().wifiOnlyFilterUpdates }
          .getOrDefault(true)
      runCatching { datasetUpdateScheduler.syncPeriodicSchedule(wifiOnly) }
      _evalTick.value += 1
    }
  }

  fun importSignedDatasetPackage(rawJsonBytes: ByteArray) {
    _errorMessage.value = null
    viewModelScope.launch {
      datasetSyncController.importSignedLocalPackage(rawJsonBytes)
      _evalTick.value += 1
    }
  }

  fun rollbackDataset(datasetId: String) {
    _errorMessage.value = null
    viewModelScope.launch {
      datasetSyncController.rollbackDataset(datasetId)
      _evalTick.value += 1
    }
  }

  fun onCurrentPinInputChange(value: String) {
    val sanitized = value.filter { it.isDigit() }.take(PinFormatValidator.MAX_PIN_LENGTH)
    _pinDraft.value =
      _pinDraft.value.copy(currentPin = sanitized, statusMessage = null, errorMessage = null)
  }

  fun onNewPinInputChange(value: String) {
    val sanitized = value.filter { it.isDigit() }.take(PinFormatValidator.MAX_PIN_LENGTH)
    _pinDraft.value =
      _pinDraft.value.copy(newPin = sanitized, statusMessage = null, errorMessage = null)
  }

  fun savePin() {
    val draft = _pinDraft.value
    if (draft.isSubmitting) return

    _pinDraft.value = draft.copy(isSubmitting = true, statusMessage = null, errorMessage = null)
    viewModelScope.launch {
      val metaResult = accountabilityMetadataRepository.observeMetadata().first()
      val isEnabled = metaResult is PersistenceResult.Success && metaResult.data.isPinEnabled
      val result =
        if (!isEnabled) {
          accountabilityPinRepository.configurePin(draft.newPin)
        } else {
          accountabilityPinRepository.changePin(draft.currentPin, draft.newPin)
        }
      refreshPinVaultState()
      when (result) {
        PinConfigureResult.Success -> {
          _pinDraft.value =
            PinDraftInternal(
              statusMessage =
                if (!isEnabled) {
                  "Local Accountability PIN configured using Keystore + PBKDF2."
                } else {
                  "Local Accountability PIN updated."
                }
            )
        }
        else -> {
          _pinDraft.value =
            draft.copy(
              isSubmitting = false,
              currentPin = "",
              newPin = if (result is PinConfigureResult.InvalidFormat) draft.newPin else "",
              errorMessage = mapPinConfigureError(result),
            )
        }
      }
    }
  }

  fun removePin() {
    val draft = _pinDraft.value
    if (draft.isSubmitting) return

    _pinDraft.value = draft.copy(isSubmitting = true, statusMessage = null, errorMessage = null)
    viewModelScope.launch {
      val result = accountabilityPinRepository.removePin(draft.currentPin)
      refreshPinVaultState()
      when (result) {
        PinConfigureResult.Success -> {
          _pinDraft.value =
            PinDraftInternal(statusMessage = "Accountability PIN removed locally.")
        }
        else -> {
          _pinDraft.value =
            draft.copy(
              isSubmitting = false,
              currentPin = "",
              errorMessage = mapPinConfigureError(result),
            )
        }
      }
    }
  }

  fun request24HourRemoval() {
    if (_removalTransient.value.isSubmitting) return
    _removalTransient.value = RemovalTransientInternal(isSubmitting = true)
    viewModelScope.launch {
      val existingOpen = removalRequestRepository.getLatestOpenRequest()
      if (existingOpen is PersistenceResult.Failure) {
        _removalTransient.value =
          RemovalTransientInternal(errorMessage = existingOpen.error.toDisplayMessage())
        return@launch
      }
      if ((existingOpen as PersistenceResult.Success).data != null) {
        _removalTransient.value =
          RemovalTransientInternal(
            errorMessage = "A 24-hour voluntary removal request is already active."
          )
        return@launch
      }

      val activeRes = commitmentRepository.observeActiveCommitment().first()
      val activeCommitment =
        (activeRes as? PersistenceResult.Success)?.data as? Commitment.Active
      val nowWall = timeProvider.currentWallClock()
      val nowMono = timeProvider.currentMonotonic()
      val bootToken = bootSessionProvider.currentBootSessionToken()

      val newRequest =
        RemovalRequestStateMachine.createRequest(
          id = RemovalRequestId(idGenerator.nextId()),
          commitmentId = activeCommitment?.id,
          nowWall = nowWall,
          nowMonotonic = nowMono,
          bootSessionToken = bootToken,
        )

      when (val saveRes = removalRequestRepository.saveRequest(newRequest)) {
        is PersistenceResult.Failure -> {
          _removalTransient.value =
            RemovalTransientInternal(errorMessage = saveRes.error.toDisplayMessage())
        }
        is PersistenceResult.Success -> {
          securityEventRepository.recordEvent(
            SecurityEvent(
              id = SecurityEventId(idGenerator.nextId()),
              commitmentId = activeCommitment?.id,
              eventType = SecurityEventType.REMOVAL_REQUEST_CREATED,
              occurredAt = nowWall,
              summary = "Voluntary 24-hour removal cooling-off period requested",
            )
          )
          _evalTick.value += 1
          _removalTransient.value =
            RemovalTransientInternal(
              statusMessage =
                "24-hour voluntary removal request started. You can cancel anytime before executing."
            )
        }
      }
    }
  }

  fun cancelRemovalRequest() {
    if (_removalTransient.value.isSubmitting) return
    _removalTransient.value = RemovalTransientInternal(isSubmitting = true)
    viewModelScope.launch {
      val existingRes = removalRequestRepository.getLatestOpenRequest()
      val open = (existingRes as? PersistenceResult.Success)?.data
      if (open == null) {
        _removalTransient.value =
          RemovalTransientInternal(errorMessage = "No open removal request to cancel.")
        return@launch
      }
      val nowWall = timeProvider.currentWallClock()
      when (val result = RemovalRequestStateMachine.cancel(open, nowWall)) {
        is RemovalActionResult.Failure -> {
          _removalTransient.value =
            RemovalTransientInternal(errorMessage = "Removal request can no longer be cancelled.")
        }
        is RemovalActionResult.Success -> {
          when (val saveRes = removalRequestRepository.saveRequest(result.request)) {
            is PersistenceResult.Failure -> {
              _removalTransient.value =
                RemovalTransientInternal(errorMessage = saveRes.error.toDisplayMessage())
            }
            is PersistenceResult.Success -> {
              securityEventRepository.recordEvent(
                SecurityEvent(
                  id = SecurityEventId(idGenerator.nextId()),
                  commitmentId = result.request.commitmentId,
                  eventType = SecurityEventType.REMOVAL_REQUEST_CANCELLED,
                  occurredAt = nowWall,
                  summary = "Voluntary 24-hour removal request cancelled",
                )
              )
              _evalTick.value += 1
              _removalTransient.value =
                RemovalTransientInternal(statusMessage = "Voluntary removal request cancelled.")
            }
          }
        }
      }
    }
  }

  fun executeRemovalRequest() {
    if (_removalTransient.value.isSubmitting) return
    _removalTransient.value = RemovalTransientInternal(isSubmitting = true)
    viewModelScope.launch {
      val existingRes = removalRequestRepository.getLatestOpenRequest()
      val open = (existingRes as? PersistenceResult.Success)?.data
      if (open == null) {
        _removalTransient.value =
          RemovalTransientInternal(errorMessage = "No open removal request found.")
        return@launch
      }

      val nowWall = timeProvider.currentWallClock()
      val nowMono = timeProvider.currentMonotonic()
      val bootToken = bootSessionProvider.currentBootSessionToken()

      when (
        val execResult =
          RemovalRequestStateMachine.execute(
            request = open,
            nowWall = nowWall,
            nowMonotonic = nowMono,
            currentBootSessionToken = bootToken,
          )
      ) {
        is RemovalActionResult.Failure -> {
          val msg =
            when (execResult.error) {
              RemovalActionError.ClockAnomalyBlocked ->
                "A suspicious clock change was detected during the 24-hour cooling-off window. Wait for genuine elapsed time before completing removal."
              RemovalActionError.DelayNotElapsed ->
                "The 24-hour cooling-off window has not finished yet."
              RemovalActionError.RequestNotOpen ->
                "This removal request is no longer active."
            }
          _removalTransient.value = RemovalTransientInternal(errorMessage = msg)
        }
        is RemovalActionResult.Success -> {
          val saveReq = removalRequestRepository.saveRequest(execResult.request)
          if (saveReq is PersistenceResult.Failure) {
            _removalTransient.value =
              RemovalTransientInternal(errorMessage = saveReq.error.toDisplayMessage())
            return@launch
          }

          val activeRes = commitmentRepository.observeActiveCommitment().first()
          val active = (activeRes as? PersistenceResult.Success)?.data as? Commitment.Active
          if (active != null) {
            val terminatedRes =
              CommitmentStateMachine.emergencyTerminate(
                commitment = active,
                confirmedWarning = true,
                pinVerification = PinVerificationResult.VERIFIED,
                now = nowWall,
              )
            if (terminatedRes is CommitmentResult.Success) {
              commitmentRepository.saveCommitment(terminatedRes.commitment)
              securityEventRepository.recordEvent(
                SecurityEvent(
                  id = SecurityEventId(idGenerator.nextId()),
                  commitmentId = active.id,
                  eventType = SecurityEventType.EMERGENCY_TERMINATED,
                  occurredAt = nowWall,
                  summary = "Active commitment lifted after 24-hour voluntary removal window",
                )
              )
            }
          }

          runCatching { vpnFilterMonitor.onCommitmentEnded() }
          runCatching { accountabilityPinRepository.clearPinOnVerifiedRemoval() }
          refreshPinVaultState()

          securityEventRepository.recordEvent(
            SecurityEvent(
              id = SecurityEventId(idGenerator.nextId()),
              commitmentId = execResult.request.commitmentId,
              eventType = SecurityEventType.REMOVAL_REQUEST_EXECUTED,
              occurredAt = nowWall,
              summary = "24-hour voluntary removal cooling-off executed; local locks lifted",
            )
          )

          _evalTick.value += 1
          _removalTransient.value =
            RemovalTransientInternal(
              statusMessage =
                "24-hour cooling-off completed. Active restrictions and local Accountability PIN have been cleared."
            )
        }
      }
    }
  }

  private suspend fun refreshPinVaultState() {
    val available = runCatching { accountabilityPinRepository.isCredentialAvailable() }.getOrDefault(false)
    val lockoutMs =
      runCatching { accountabilityPinRepository.currentLockoutRemainingMillis() }.getOrDefault(0L)
    _pinVaultInfo.value =
      PinVaultSnapshot(
        available = available,
        lockoutRemainingSeconds = millisToSecondsCeil(lockoutMs),
      )
  }

  private fun buildRemovalUiState(
    latestRequest: RemovalRequest?,
    hasActiveCommitment: Boolean,
    transient: RemovalTransientInternal,
  ): RemovalRequestUiState {
    if (
      latestRequest == null ||
        latestRequest.status == RemovalRequestStatus.CANCELLED ||
        latestRequest.status == RemovalRequestStatus.EXECUTED
    ) {
      return RemovalRequestUiState(
        status = RemovalUiStatus.NONE,
        hasActiveCommitment = hasActiveCommitment,
        isSubmitting = transient.isSubmitting,
        statusMessage = transient.statusMessage,
        errorMessage = transient.errorMessage,
      )
    }

    val eval =
      RemovalRequestStateMachine.evaluate(
        request = latestRequest,
        nowWall = timeProvider.currentWallClock(),
        nowMonotonic = timeProvider.currentMonotonic(),
        currentBootSessionToken = bootSessionProvider.currentBootSessionToken(),
      )

    val uiStatus =
      when {
        eval.clockAnomalyDetected && !eval.isReadyToExecute -> RemovalUiStatus.CLOCK_ANOMALY_BLOCKED
        eval.isReadyToExecute -> RemovalUiStatus.READY_TO_EXECUTE
        else -> RemovalUiStatus.PENDING
      }

    return RemovalRequestUiState(
      status = uiStatus,
      requestId = latestRequest.id.value,
      requestedAt = latestRequest.requestedAt,
      unlocksAt = latestRequest.unlocksAt,
      remainingDuration = eval.remainingDuration,
      hasActiveCommitment = hasActiveCommitment,
      isSubmitting = transient.isSubmitting,
      statusMessage = transient.statusMessage,
      errorMessage = transient.errorMessage,
    )
  }

  private fun mapPinConfigureError(result: PinConfigureResult): String =
    when (result) {
      PinConfigureResult.Success -> ""
      is PinConfigureResult.InvalidFormat ->
        when (result.error) {
          PinValidationError.BLANK -> "Enter a 4–8 digit PIN."
          PinValidationError.TOO_SHORT ->
            "PIN must be at least ${PinFormatValidator.MIN_PIN_LENGTH} digits."
          PinValidationError.TOO_LONG ->
            "PIN must be at most ${PinFormatValidator.MAX_PIN_LENGTH} digits."
          PinValidationError.NON_DIGIT -> "PIN may contain digits (0–9) only."
        }
      PinConfigureResult.CurrentPinMismatch -> "Current PIN did not match."
      is PinConfigureResult.LockedOut ->
        "Too many failed PIN attempts. Wait ${millisToSecondsCeil(result.remainingMillis)}s."
      PinConfigureResult.PinNotConfigured -> "No Accountability PIN is currently configured."
      is PinConfigureResult.StorageFailure -> result.reason
    }

  private fun millisToSecondsCeil(millis: Long): Long =
    if (millis <= 0L) 0L else ceil(millis / 1000.0).toLong().coerceAtLeast(1L)

  private data class SystemHealthBundle(
    val prefs: AppPreferences,
    val health: EnforcementHealthSnapshot,
    val usageAccess: UsageAccessSnapshot,
    val battery: BatteryOptimizationSnapshot,
    val vpn: VpnFilterSnapshot,
  )

  private data class SecurityDataBundle(
    val datasetsResult: PersistenceResult<List<FilterDatasetMetadata>>,
    val metadataResult: PersistenceResult<AccountabilityMetadata>,
    val removalResult: PersistenceResult<RemovalRequest?>,
    val activeResult: PersistenceResult<Commitment?>,
  )

  private data class PinDraftInternal(
    val currentPin: String = "",
    val newPin: String = "",
    val isSubmitting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
  )

  private data class RemovalTransientInternal(
    val isSubmitting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
  )

  private data class PinVaultSnapshot(
    val available: Boolean = false,
    val lockoutRemainingSeconds: Long = 0L,
  )

  private data class TransientUiBundle(
    val removal: RemovalTransientInternal,
    val errorMessage: String?,
    val datasetSync: DatasetSyncUiSnapshot,
  )
}
