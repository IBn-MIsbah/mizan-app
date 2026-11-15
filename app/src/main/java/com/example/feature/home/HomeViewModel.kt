package com.example.feature.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.common.toDisplayMessage
import com.example.domain.allowance.SharedAllowanceAccountant
import com.example.domain.insights.DashboardInsights
import com.example.domain.insights.UsageInsightsCalculator
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.model.AppPreferences
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

enum class OperationalReadiness {
  NOT_CONFIGURED,
  CONFIGURED_ACTION_NEEDED,
  ENABLED_NOT_OPERATIONAL,
  OPERATIONAL,
  UNAVAILABLE,
}

data class DashboardProtectionReadiness(
  val appStatus: OperationalReadiness = OperationalReadiness.NOT_CONFIGURED,
  val webStatus: OperationalReadiness = OperationalReadiness.NOT_CONFIGURED,
)

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
    val currentDayOfSeven: Int = 1,
    val elapsedProgressFraction: Float = 0.02f,
    val remainingDurationLabel: String = "6d",
  ) : DashboardStatus

  data class Completed(val commitment: Commitment.Completed) : DashboardStatus

  data class EmergencyTerminated(val commitment: Commitment.EmergencyTerminated) : DashboardStatus
}

data class DashboardContent(
  val status: DashboardStatus,
  val history: List<Commitment>,
  val isPinEnabled: Boolean,
  val preferences: AppPreferences = AppPreferences(),
  val healthSnapshot: EnforcementHealthSnapshot = EnforcementHealthSnapshot(),
  val usageAccessSnapshot: UsageAccessSnapshot = UsageAccessSnapshot(),
  val batteryOptimizationSnapshot: BatteryOptimizationSnapshot = BatteryOptimizationSnapshot(),
  val vpnFilterSnapshot: VpnFilterSnapshot = VpnFilterSnapshot(),
  val insights: DashboardInsights? = null,
  val readiness: DashboardProtectionReadiness = DashboardProtectionReadiness(),
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
      userPreferencesRepository.preferences,
    ) { health, usageAccess, battery, vpn, prefs ->
      SystemMonitors(health, usageAccess, battery, vpn, prefs)
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

  fun dismissFirstUseGuide() {
    viewModelScope.launch { userPreferencesRepository.setFirstUseGuideCompleted(true) }
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
          val hasReachedEnd = active.hasReachedEnd(currentNow)
          val totalMs =
            (active.endsAt.epochMillis - active.activatedAt.epochMillis).coerceAtLeast(1L)
          val elapsedFraction =
            if (hasReachedEnd) 1f
            else
              ((currentNow.epochMillis - active.activatedAt.epochMillis).toFloat() /
                  totalMs.toFloat())
                .coerceIn(0.02f, 1f)
          val elapsedDays =
            (((currentNow.epochMillis - active.activatedAt.epochMillis).coerceAtLeast(0L)) /
                (24L * 60L * 60L * 1000L))
              .toInt() + 1
          val boundedDay = elapsedDays.coerceIn(1, 7)
          val status =
            DashboardStatus.Active(
              commitment = active,
              dailyUsage = usage,
              remainingAllowance = remaining,
              hasReachedEnd = hasReachedEnd,
              usageLoadError = usageError,
              usageCoverage = coverage,
              currentDayOfSeven = boundedDay,
              elapsedProgressFraction = elapsedFraction,
              remainingDurationLabel = "${(7 - boundedDay).coerceAtLeast(0)}d",
            )
          val readiness =
            resolveReadiness(
              active = active,
              draft = null,
              health = monitors.health,
              vpn = monitors.vpn,
            )
          val history = commitments.filter { it.id != active.id && it !is Commitment.Draft }
          DashboardUiState.Success(
            DashboardContent(
              status = status,
              history = history,
              isPinEnabled = isPinEnabled,
              preferences = monitors.preferences,
              healthSnapshot = monitors.health,
              usageAccessSnapshot = monitors.usageAccess,
              batteryOptimizationSnapshot = monitors.battery,
              vpnFilterSnapshot = monitors.vpn,
              insights = insights,
              readiness = readiness,
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

    val readiness =
      resolveReadiness(
        active = null,
        draft = draft,
        health = monitors.health,
        vpn = monitors.vpn,
      )

    return flowOf(
      DashboardUiState.Success(
        DashboardContent(
          status = primaryStatus,
          history = history,
          isPinEnabled = isPinEnabled,
          preferences = monitors.preferences,
          healthSnapshot = monitors.health,
          usageAccessSnapshot = monitors.usageAccess,
          batteryOptimizationSnapshot = monitors.battery,
          vpnFilterSnapshot = monitors.vpn,
          insights = insights,
          readiness = readiness,
        )
      )
    )
  }

  private fun resolveReadiness(
    active: Commitment.Active?,
    draft: Commitment.Draft?,
    health: EnforcementHealthSnapshot,
    vpn: VpnFilterSnapshot,
  ): DashboardProtectionReadiness {
    val appStatus =
      when {
        active != null && active.rules.restrictedApps.isNotEmpty() ->
          when (health.healthStatus) {
            EnforcementHealthStatus.ACTIVE -> OperationalReadiness.OPERATIONAL
            EnforcementHealthStatus.ENABLED_SERVICE_DISCONNECTED ->
              OperationalReadiness.ENABLED_NOT_OPERATIONAL
            EnforcementHealthStatus.DISABLED -> OperationalReadiness.CONFIGURED_ACTION_NEEDED
            EnforcementHealthStatus.UNAVAILABLE -> OperationalReadiness.UNAVAILABLE
          }
        draft != null && draft.rules.restrictedApps.isNotEmpty() ->
          if (health.healthStatus == EnforcementHealthStatus.UNAVAILABLE) {
            OperationalReadiness.UNAVAILABLE
          } else {
            OperationalReadiness.CONFIGURED_ACTION_NEEDED
          }
        else -> OperationalReadiness.NOT_CONFIGURED
      }

    val webStatus =
      when {
        active != null && active.rules.hasConfiguredWebsiteFiltering() ->
          when (vpn.status) {
            com.example.platform.vpn.VpnServiceLifecycleStatus.ACTIVE ->
              OperationalReadiness.OPERATIONAL
            com.example.platform.vpn.VpnServiceLifecycleStatus.STARTING ->
              OperationalReadiness.ENABLED_NOT_OPERATIONAL
            com.example.platform.vpn.VpnServiceLifecycleStatus.DEGRADED_ERROR ->
              OperationalReadiness.UNAVAILABLE
            else -> OperationalReadiness.CONFIGURED_ACTION_NEEDED
          }
        draft != null && draft.rules.hasConfiguredWebsiteFiltering() ->
          OperationalReadiness.CONFIGURED_ACTION_NEEDED
        else -> OperationalReadiness.NOT_CONFIGURED
      }

    return DashboardProtectionReadiness(appStatus = appStatus, webStatus = webStatus)
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
    val preferences: AppPreferences = AppPreferences(),
  )

  private data class DashboardInputs(
    val commitmentsResult: PersistenceResult<List<Commitment>>,
    val metadataResult: PersistenceResult<AccountabilityMetadata>,
    val recentUsageResult: PersistenceResult<List<DailyUsage>>,
    val monitors: SystemMonitors,
  )
}
