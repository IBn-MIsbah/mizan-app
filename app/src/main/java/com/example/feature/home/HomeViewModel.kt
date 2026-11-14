package com.example.feature.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.common.toDisplayMessage
import com.example.domain.allowance.SharedAllowanceAccountant
import com.example.domain.insights.DashboardInsights
import com.example.domain.insights.UsageInsightsCalculator
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.model.Commitment
import com.example.domain.model.DailyUsage
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.repository.AccountabilityMetadataRepository
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DailyUsageRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.TimeProvider
import com.example.platform.health.BatteryOptimizationMonitor
import com.example.platform.health.BatteryOptimizationSnapshot
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.health.EnforcementHealthSnapshot
import com.example.platform.health.EnforcementHealthStatus
import com.example.platform.health.UsageAccessMonitor
import com.example.platform.health.UsageAccessPermissionStatus
import com.example.platform.health.UsageAccessSnapshot
import com.example.platform.usage.UsageStatsReconciler
import com.example.platform.vpn.VpnFilterMonitor
import com.example.platform.vpn.VpnFilterSnapshot
import com.example.platform.vpn.VpnStartRequestOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DashboardUsageCoverage {
  data object Unavailable : DashboardUsageCoverage

  data class Incomplete(val reason: String) : DashboardUsageCoverage

  data class Measured(
    val coverageStatus: UsageCoverageStatus,
    val isUsageAccessEnabled: Boolean,
    val isAccessibilityActive: Boolean,
  ) : DashboardUsageCoverage
}

sealed interface DashboardStatus {
  data object NoCommitment : DashboardStatus

  data class DraftInProgress(val draft: Commitment.Draft) : DashboardStatus

  data class Active(
    val commitment: Commitment.Active,
    val dailyUsage: DailyUsage?,
    val remainingAllowance: Duration,
    val hasReachedEnd: Boolean,
    val usageLoadError: String? = null,
    val usageCoverage: DashboardUsageCoverage =
      DashboardUsageCoverage.Measured(
        coverageStatus = UsageCoverageStatus.MEASURED,
        isUsageAccessEnabled = false,
        isAccessibilityActive = false,
      ),
  ) : DashboardStatus

  data class Completed(val commitment: Commitment.Completed) : DashboardStatus

  data class EmergencyTerminated(val commitment: Commitment.EmergencyTerminated) : DashboardStatus
}

data class DashboardContent(
  val status: DashboardStatus,
  val history: List<Commitment>,
  val isPinEnabled: Boolean,
  val healthSnapshot: EnforcementHealthSnapshot = EnforcementHealthSnapshot(),
  val usageAccessSnapshot: UsageAccessSnapshot = UsageAccessSnapshot(),
  val batteryOptimizationSnapshot: BatteryOptimizationSnapshot = BatteryOptimizationSnapshot(),
  val vpnFilterSnapshot: VpnFilterSnapshot = VpnFilterSnapshot(),
  val insights: DashboardInsights? = null,
)

sealed interface DashboardUiState {
  data object Loading : DashboardUiState

  data class Error(val message: String) : DashboardUiState

  data class Success(val content: DashboardContent) : DashboardUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
@Inject
constructor(
  private val commitmentRepository: CommitmentRepository,
  private val dailyUsageRepository: DailyUsageRepository,
  private val accountabilityMetadataRepository: AccountabilityMetadataRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val enforcementHealthMonitor: EnforcementHealthMonitor,
  private val usageAccessMonitor: UsageAccessMonitor,
  private val batteryOptimizationMonitor: BatteryOptimizationMonitor,
  private val vpnFilterMonitor: VpnFilterMonitor,
  private val usageStatsReconciler: UsageStatsReconciler,
  private val timeProvider: TimeProvider,
) : ViewModel() {

  private val refreshTrigger = MutableStateFlow(0)

  private val systemMonitorsFlow =
    combine(
      enforcementHealthMonitor.healthState,
      usageAccessMonitor.usageAccessState,
      batteryOptimizationMonitor.batteryState,
      vpnFilterMonitor.vpnState,
    ) { health, usageAccess, battery, vpn ->
      SystemMonitors(health, usageAccess, battery, vpn)
    }

  val uiState: StateFlow<DashboardUiState> =
    combine(
        commitmentRepository.observeAllCommitments(),
        accountabilityMetadataRepository.observeMetadata(),
        dailyUsageRepository.observeRecentDailyUsage(30),
        systemMonitorsFlow,
        refreshTrigger,
      ) { commitmentsResult, metadataResult, recentUsageResult, monitors, _ ->
        DashboardInputs(
          commitmentsResult = commitmentsResult,
          metadataResult = metadataResult,
          recentUsageResult = recentUsageResult,
          monitors = monitors,
        )
      }
      .flatMapLatest { inputs -> buildDashboardFlow(inputs) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DashboardUiState.Loading,
      )

  fun refresh() {
    enforcementHealthMonitor.refreshSystemStatus()
    usageAccessMonitor.refreshSystemStatus()
    batteryOptimizationMonitor.refreshSystemStatus()
    vpnFilterMonitor.refreshSystemStatus()
    refreshTrigger.value += 1
    triggerUsageReconciliation()
  }

  fun refreshEnforcementHealth() {
    enforcementHealthMonitor.refreshSystemStatus()
    usageAccessMonitor.refreshSystemStatus()
    batteryOptimizationMonitor.refreshSystemStatus()
    vpnFilterMonitor.refreshSystemStatus()
    triggerUsageReconciliation()
  }

  fun createAccessibilitySettingsIntent(): Intent =
    enforcementHealthMonitor.createAccessibilitySettingsIntent()

  fun createUsageAccessSettingsIntent(): Intent =
    usageAccessMonitor.createUsageAccessSettingsIntent()

  fun createBatteryOptimizationSettingsIntent(): Intent =
    batteryOptimizationMonitor.createBatteryOptimizationSettingsIntent()

  fun prepareVpnIntent(): Intent? = vpnFilterMonitor.prepareVpnIntent()

  fun setWebsiteFilteringConsent(consented: Boolean) {
    viewModelScope.launch { userPreferencesRepository.setWebsiteFilteringConsented(consented) }
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

  private fun triggerUsageReconciliation() {
    viewModelScope.launch {
      val activeResult =
        runCatching { commitmentRepository.observeActiveCommitment().first() }.getOrNull()
      val active =
        (activeResult as? PersistenceResult.Success)?.data as? Commitment.Active ?: return@launch
      runCatching { usageStatsReconciler.reconcileActiveCommitment(active) }
    }
  }

  private fun buildDashboardFlow(inputs: DashboardInputs): Flow<DashboardUiState> {
    val commitmentsResult = inputs.commitmentsResult
    if (commitmentsResult is PersistenceResult.Failure) {
      return flowOf(DashboardUiState.Error(commitmentsResult.error.toDisplayMessage()))
    }

    val commitments = (commitmentsResult as PersistenceResult.Success).data
    val isPinEnabled =
      when (val meta = inputs.metadataResult) {
        is PersistenceResult.Success -> meta.data.isPinEnabled
        is PersistenceResult.Failure -> false
      }
    val recentUsages =
      when (val usageRes = inputs.recentUsageResult) {
        is PersistenceResult.Success -> usageRes.data
        is PersistenceResult.Failure -> emptyList()
      }

    val todayDate = timeProvider.currentWallClock().date
    val insights =
      UsageInsightsCalculator.compute(
        commitments = commitments,
        recentUsages = recentUsages,
        todayDate = todayDate,
      )

    val monitors = inputs.monitors
    val active = commitments.firstOrNull { it is Commitment.Active } as? Commitment.Active
    if (active != null) {
      return dailyUsageRepository.observeDailyUsage(active.id, todayDate).let { usageFlow ->
        combine(usageFlow, flowOf(Unit)) { usageResult, _ ->
          val currentNow = timeProvider.currentWallClock()
          val (usage, usageError) =
            when (usageResult) {
              is PersistenceResult.Success -> usageResult.data to null
              is PersistenceResult.Failure -> null to usageResult.error.toDisplayMessage()
            }
          val remaining =
            if (usage != null) {
              SharedAllowanceAccountant.remainingAllowance(active, usage, currentNow.date)
            } else {
              Duration.ZERO
            }
          val coverage =
            resolveCoverage(
              usage = usage,
              usageError = usageError,
              healthSnapshot = monitors.health,
              usageAccessSnapshot = monitors.usageAccess,
            )
          val status =
            DashboardStatus.Active(
              commitment = active,
              dailyUsage = usage,
              remainingAllowance = remaining,
              hasReachedEnd = active.hasReachedEnd(currentNow),
              usageLoadError = usageError,
              usageCoverage = coverage,
            )
          val history = commitments.filter { it.id != active.id && it !is Commitment.Draft }
          DashboardUiState.Success(
            DashboardContent(
              status = status,
              history = history,
              isPinEnabled = isPinEnabled,
              healthSnapshot = monitors.health,
              usageAccessSnapshot = monitors.usageAccess,
              batteryOptimizationSnapshot = monitors.battery,
              vpnFilterSnapshot = monitors.vpn,
              insights = insights,
            )
          )
        }
      }
    }

    val draft = commitments.firstOrNull { it is Commitment.Draft } as? Commitment.Draft
    val primaryStatus: DashboardStatus =
      when {
        draft != null -> DashboardStatus.DraftInProgress(draft)
        else -> {
          when (val latest = commitments.firstOrNull()) {
            is Commitment.Completed -> DashboardStatus.Completed(latest)
            is Commitment.EmergencyTerminated -> DashboardStatus.EmergencyTerminated(latest)
            else -> DashboardStatus.NoCommitment
          }
        }
      }

    val primaryId =
      when (primaryStatus) {
        is DashboardStatus.DraftInProgress -> primaryStatus.draft.id
        is DashboardStatus.Completed -> primaryStatus.commitment.id
        is DashboardStatus.EmergencyTerminated -> primaryStatus.commitment.id
        else -> null
      }
    val history = commitments.filter { it.id != primaryId && it !is Commitment.Draft }

    return flowOf(
      DashboardUiState.Success(
        DashboardContent(
          status = primaryStatus,
          history = history,
          isPinEnabled = isPinEnabled,
          healthSnapshot = monitors.health,
          usageAccessSnapshot = monitors.usageAccess,
          batteryOptimizationSnapshot = monitors.battery,
          vpnFilterSnapshot = monitors.vpn,
          insights = insights,
        )
      )
    )
  }

  private fun resolveCoverage(
    usage: DailyUsage?,
    usageError: String?,
    healthSnapshot: EnforcementHealthSnapshot,
    usageAccessSnapshot: UsageAccessSnapshot,
  ): DashboardUsageCoverage {
    if (usageError != null || usage == null) {
      return DashboardUsageCoverage.Unavailable
    }
    val isA11yActive = healthSnapshot.healthStatus == EnforcementHealthStatus.ACTIVE
    val isUsageAccessEnabled =
      usageAccessSnapshot.permissionStatus == UsageAccessPermissionStatus.ENABLED

    if (usage.coverageStatus == UsageCoverageStatus.INCOMPLETE) {
      return DashboardUsageCoverage.Incomplete(
        usage.incompleteReason ?: "Measurement interrupted; coverage may be incomplete."
      )
    }
    if (!isA11yActive && !isUsageAccessEnabled && usage.consumed == Duration.ZERO) {
      return DashboardUsageCoverage.Incomplete(
        "Accessibility monitoring is inactive and Usage Access is disabled; foreground usage cannot be measured."
      )
    }
    return DashboardUsageCoverage.Measured(
      coverageStatus = usage.coverageStatus,
      isUsageAccessEnabled = isUsageAccessEnabled,
      isAccessibilityActive = isA11yActive,
    )
  }

  private data class SystemMonitors(
    val health: EnforcementHealthSnapshot,
    val usageAccess: UsageAccessSnapshot,
    val battery: BatteryOptimizationSnapshot,
    val vpn: VpnFilterSnapshot,
  )

  private data class DashboardInputs(
    val commitmentsResult: PersistenceResult<List<Commitment>>,
    val metadataResult: PersistenceResult<AccountabilityMetadata>,
    val recentUsageResult: PersistenceResult<List<DailyUsage>>,
    val monitors: SystemMonitors,
  )
}
