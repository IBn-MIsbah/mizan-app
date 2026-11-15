package com.example.feature.home

import com.example.MainDispatcherRule
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.PinVerificationResult
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.repository.PersistenceError
import com.example.platform.health.AccessibilityPermissionStatus
import com.example.platform.health.UsageAccessPermissionStatus
import com.example.testing.FakeAccountabilityMetadataRepository
import com.example.testing.FakeBatteryOptimizationMonitor
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUsageAccessMonitor
import com.example.testing.FakeUserPreferencesRepository
import com.example.testing.FakeVpnFilterMonitor
import com.example.testing.createTestUsageReconciler
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val timeProvider = FakeTimeProvider()
  private val healthMonitor = FakeEnforcementHealthMonitor()
  private val usageAccessMonitor = FakeUsageAccessMonitor()
  private val batteryMonitor = FakeBatteryOptimizationMonitor()
  private val vpnFilterMonitor = FakeVpnFilterMonitor()
  private val userPreferencesRepository = FakeUserPreferencesRepository()
  private val rules =
    CommitmentRules(
      mode = RestrictionMode.HYBRID,
      restrictedApps = setOf(RestrictedApp(AppId("com.instagram.android"), "Instagram")),
      dailyAllowance = 30.minutes,
    )

  private fun createViewModel(
    commitmentRepository: FakeCommitmentRepository = FakeCommitmentRepository(),
    dailyUsageRepository: FakeDailyUsageRepository = FakeDailyUsageRepository(),
  ): HomeViewModel =
    HomeViewModel(
      commitmentRepository = commitmentRepository,
      dailyUsageRepository = dailyUsageRepository,
      accountabilityMetadataRepository = FakeAccountabilityMetadataRepository(),
      userPreferencesRepository = userPreferencesRepository,
      enforcementHealthMonitor = healthMonitor,
      usageAccessMonitor = usageAccessMonitor,
      batteryOptimizationMonitor = batteryMonitor,
      vpnFilterMonitor = vpnFilterMonitor,
      usageStatsReconciler =
        createTestUsageReconciler(
          usageAccessMonitor = usageAccessMonitor,
          dailyUsageRepository = dailyUsageRepository,
          timeProvider = timeProvider,
        ),
      timeProvider = timeProvider,
    )

  @Test
  fun `dashboard renders NoCommitment when repository is empty`() = runTest {
    val viewModel = createViewModel()
    val job = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    val state = viewModel.uiState.value as DashboardUiState.Success
    assertEquals(DashboardStatus.NoCommitment, state.content.status)
    job.cancel()
  }

  @Test
  fun `dashboard renders DraftInProgress Active Completed and Terminated states`() = runTest {
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-1"), rules, timeProvider.wallClock)
    val repo = FakeCommitmentRepository(listOf(draft))
    val usageRepo = FakeDailyUsageRepository()
    val viewModel = createViewModel(repo, usageRepo)

    val job = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    var state = viewModel.uiState.value as DashboardUiState.Success
    assertTrue(state.content.status is DashboardStatus.DraftInProgress)

    // Activate commitment and check remaining allowance + not reached end
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
          as CommitmentResult.Success)
        .commitment
    usageRepo.setUsage(DailyUsage(active.id, timeProvider.wallClock.date, consumed = 10.minutes))
    repo.saveCommitment(active)
    advanceUntilIdle()

    state = viewModel.uiState.value as DashboardUiState.Success
    val activeStatus = state.content.status as DashboardStatus.Active
    assertFalse(activeStatus.hasReachedEnd)
    assertEquals(20.minutes, activeStatus.remainingAllowance)

    // Advance clock to 7-day end boundary -> hasReachedEnd becomes true on refresh
    timeProvider.wallClock = active.endsAt
    viewModel.refresh()
    advanceUntilIdle()
    state = viewModel.uiState.value as DashboardUiState.Success
    assertTrue((state.content.status as DashboardStatus.Active).hasReachedEnd)

    // Complete
    val completed =
      (CommitmentStateMachine.completeIfReachedEnd(active, active.endsAt) as CommitmentResult.Success)
        .commitment
    repo.saveCommitment(completed)
    advanceUntilIdle()
    state = viewModel.uiState.value as DashboardUiState.Success
    assertTrue(state.content.status is DashboardStatus.Completed)

    // Replace with emergency terminated
    repo.deleteCommitment(completed.id)
    val active2 =
      (CommitmentStateMachine.activate(
          CommitmentStateMachine.createDraft(CommitmentId("c-2"), rules, timeProvider.wallClock),
          agreedToExactRules = true,
          now = timeProvider.wallClock,
        ) as CommitmentResult.Success)
        .commitment
    val terminated =
      (CommitmentStateMachine.emergencyTerminate(
          active2,
          confirmedWarning = true,
          pinVerification = PinVerificationResult.NOT_PROVIDED,
          now = timeProvider.wallClock,
        ) as CommitmentResult.Success)
        .commitment
    repo.saveCommitment(terminated)
    advanceUntilIdle()

    state = viewModel.uiState.value as DashboardUiState.Success
    assertTrue(state.content.status is DashboardStatus.EmergencyTerminated)
    job.cancel()
  }

  @Test
  fun `dashboard distinguishes unavailable incomplete and measured usage states honestly`() = runTest {
    val active =
      (CommitmentStateMachine.activate(
          CommitmentStateMachine.createDraft(CommitmentId("c-cov"), rules, timeProvider.wallClock),
          agreedToExactRules = true,
          now = timeProvider.wallClock,
        ) as CommitmentResult.Success)
        .commitment
    val repo = FakeCommitmentRepository(listOf(active))
    val usageRepo = FakeDailyUsageRepository()
    usageRepo.readFailure = PersistenceError.StorageFailure("Read error")

    val viewModel = createViewModel(repo, usageRepo)
    val job = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    // Unavailable storage -> explicit Unavailable coverage, never fake 0m remaining as 30m
    var activeStatus =
      ((viewModel.uiState.value as DashboardUiState.Success).content.status as DashboardStatus.Active)
    assertTrue(activeStatus.usageCoverage is DashboardUsageCoverage.Unavailable)

    // Restored DB with INCOMPLETE day -> explicit Incomplete coverage
    usageRepo.readFailure = null
    usageRepo.setUsage(
      DailyUsage(
        commitmentId = active.id,
        date = timeProvider.wallClock.date,
        consumed = 5.minutes,
        coverageStatus = UsageCoverageStatus.INCOMPLETE,
        incompleteReason = "Reboot occurred",
      )
    )
    viewModel.refresh()
    advanceUntilIdle()
    activeStatus =
      ((viewModel.uiState.value as DashboardUiState.Success).content.status as DashboardStatus.Active)
    assertTrue(activeStatus.usageCoverage is DashboardUsageCoverage.Incomplete)
    assertEquals(
      "Reboot occurred",
      (activeStatus.usageCoverage as DashboardUsageCoverage.Incomplete).reason,
    )

    // Both monitoring sources disabled with 0 usage -> Incomplete warning rather than false confidence
    usageRepo.setUsage(DailyUsage.empty(active.id, timeProvider.wallClock.date))
    healthMonitor.systemPermissionStatus = AccessibilityPermissionStatus.DISABLED
    usageAccessMonitor.systemStatus = UsageAccessPermissionStatus.DISABLED
    viewModel.refresh()
    advanceUntilIdle()
    activeStatus =
      ((viewModel.uiState.value as DashboardUiState.Success).content.status as DashboardStatus.Active)
    assertTrue(activeStatus.usageCoverage is DashboardUsageCoverage.Incomplete)

    // Accessibility active -> Measured
    healthMonitor.setServiceConnected(true)
    viewModel.refresh()
    advanceUntilIdle()
    activeStatus =
      ((viewModel.uiState.value as DashboardUiState.Success).content.status as DashboardStatus.Active)
    assertTrue(activeStatus.usageCoverage is DashboardUsageCoverage.Measured)
    job.cancel()
  }

  @Test
  fun `dashboard distinguishes Not Configured, Configured Action Needed, Enabled Not Operational, Operational, and Unavailable readiness states`() =
    runTest {
      val repo = FakeCommitmentRepository()
      val viewModel = createViewModel(repo)
      val job = launch { viewModel.uiState.collect {} }
      advanceUntilIdle()

      // 1. No commitment -> NOT_CONFIGURED
      var content = (viewModel.uiState.value as DashboardUiState.Success).content
      assertEquals(OperationalReadiness.NOT_CONFIGURED, content.readiness.appStatus)
      assertEquals(OperationalReadiness.NOT_CONFIGURED, content.readiness.webStatus)

      // 2. Active commitment with restricted app & website domain filtering, Accessibility DISABLED & VPN PERMISSION_REQUIRED
      val webAndAppRules =
        rules.copy(
          websiteFilteringEnabled = true,
          blockedDomainCategories = setOf(com.example.domain.model.DomainCategory.SOCIAL_MEDIA),
        )
      val draft = CommitmentStateMachine.createDraft(CommitmentId("c-ops"), webAndAppRules, timeProvider.wallClock)
      val active =
        (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
            as CommitmentResult.Success)
          .commitment
      healthMonitor.systemPermissionStatus = AccessibilityPermissionStatus.DISABLED
      healthMonitor.setServiceConnected(false)
      vpnFilterMonitor.setSnapshot(
        com.example.platform.vpn.VpnFilterSnapshot(
          status = com.example.platform.vpn.VpnServiceLifecycleStatus.PERMISSION_REQUIRED,
          hasActiveWebsiteCommitment = true,
        )
      )
      repo.saveCommitment(active)
      advanceUntilIdle()

      content = (viewModel.uiState.value as DashboardUiState.Success).content
      assertEquals(OperationalReadiness.CONFIGURED_ACTION_NEEDED, content.readiness.appStatus)
      assertEquals(OperationalReadiness.CONFIGURED_ACTION_NEEDED, content.readiness.webStatus)

      // 3. Accessibility enabled but service disconnected + VPN STARTING -> ENABLED_NOT_OPERATIONAL
      healthMonitor.systemPermissionStatus = AccessibilityPermissionStatus.ENABLED
      healthMonitor.setServiceConnected(false)
      vpnFilterMonitor.onServiceStarting()
      viewModel.refresh()
      advanceUntilIdle()

      content = (viewModel.uiState.value as DashboardUiState.Success).content
      assertEquals(OperationalReadiness.ENABLED_NOT_OPERATIONAL, content.readiness.appStatus)
      assertEquals(OperationalReadiness.ENABLED_NOT_OPERATIONAL, content.readiness.webStatus)

      // 4. Both active/established -> OPERATIONAL
      healthMonitor.setServiceConnected(true)
      vpnFilterMonitor.onTunnelEstablished(upstreamDnsCount = 2, isPrivateDnsActive = false)
      viewModel.refresh()
      advanceUntilIdle()

      content = (viewModel.uiState.value as DashboardUiState.Success).content
      assertEquals(OperationalReadiness.OPERATIONAL, content.readiness.appStatus)
      assertEquals(OperationalReadiness.OPERATIONAL, content.readiness.webStatus)

      // 5. Accessibility UNAVAILABLE + VPN DEGRADED_ERROR -> UNAVAILABLE
      healthMonitor.systemPermissionStatus = AccessibilityPermissionStatus.UNAVAILABLE
      healthMonitor.setServiceConnected(false)
      vpnFilterMonitor.onTunnelError("Error")
      viewModel.refresh()
      advanceUntilIdle()

      content = (viewModel.uiState.value as DashboardUiState.Success).content
      assertEquals(OperationalReadiness.UNAVAILABLE, content.readiness.appStatus)
      assertEquals(OperationalReadiness.UNAVAILABLE, content.readiness.webStatus)
      job.cancel()
    }
}
