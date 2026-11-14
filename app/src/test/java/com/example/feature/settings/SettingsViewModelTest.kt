package com.example.feature.settings

import com.example.MainDispatcherRule
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RemovalRequest
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ThemePreference
import com.example.domain.repository.PersistenceResult
import com.example.platform.health.BatteryOptimizationStatus
import com.example.platform.health.UsageAccessPermissionStatus
import com.example.testing.FakeAccountabilityMetadataRepository
import com.example.testing.FakeAccountabilityPinRepository
import com.example.testing.FakeBatteryOptimizationMonitor
import com.example.testing.FakeBootSessionProvider
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeDomainFilterRepository
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeRemovalRequestRepository
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUsageAccessMonitor
import com.example.testing.FakeUserPreferencesRepository
import com.example.testing.FakeVpnFilterMonitor
import com.example.testing.createTestUsageReconciler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `settings viewModel reflects preferences, health, pin configuration, and 24h removal flow`() =
    runTest {
      val timeProvider = FakeTimeProvider()
      val prefsRepo = FakeUserPreferencesRepository()
      val healthMonitor = FakeEnforcementHealthMonitor()
      val usageAccessMonitor = FakeUsageAccessMonitor(UsageAccessPermissionStatus.DISABLED)
      val batteryMonitor = FakeBatteryOptimizationMonitor(BatteryOptimizationStatus.OPTIMIZED)
      val vpnMonitor = FakeVpnFilterMonitor()
      val domainFilterRepo = FakeDomainFilterRepository()
      val metadataRepo = FakeAccountabilityMetadataRepository()
      val pinRepo = FakeAccountabilityPinRepository(metadataRepository = metadataRepo)
      val removalRepo = FakeRemovalRequestRepository()
      val securityRepo = FakeSecurityEventRepository()
      val draft =
        CommitmentStateMachine.createDraft(
          id = CommitmentId("c-set-1"),
          rules =
            CommitmentRules(
              mode = RestrictionMode.STRICT,
              restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Social")),
              requirePinForEmergencyTermination = true,
            ),
          now = timeProvider.wallClock,
        )
      val active =
        (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
            as CommitmentResult.Success)
          .commitment
      val commitmentRepo = FakeCommitmentRepository(listOf(active))
      val usageRepo = FakeDailyUsageRepository()
      val viewModel =
        SettingsViewModel(
          userPreferencesRepository = prefsRepo,
          enforcementHealthMonitor = healthMonitor,
          usageAccessMonitor = usageAccessMonitor,
          batteryOptimizationMonitor = batteryMonitor,
          vpnFilterMonitor = vpnMonitor,
          domainFilterRepository = domainFilterRepo,
          commitmentRepository = commitmentRepo,
          accountabilityMetadataRepository = metadataRepo,
          accountabilityPinRepository = pinRepo,
          removalRequestRepository = removalRepo,
          securityEventRepository = securityRepo,
          usageStatsReconciler =
            createTestUsageReconciler(
              usageAccessMonitor = usageAccessMonitor,
              dailyUsageRepository = usageRepo,
            ),
          bootSessionProvider = FakeBootSessionProvider("boot:1"),
          timeProvider = timeProvider,
          idGenerator = FakeIdGenerator(),
        )
      val job = launch { viewModel.uiState.collect {} }
      advanceUntilIdle()

      viewModel.setThemePreference(ThemePreference.DARK)
      viewModel.setWifiOnlyFilterUpdates(false)
      usageAccessMonitor.systemStatus = UsageAccessPermissionStatus.ENABLED
      batteryMonitor.systemStatus = BatteryOptimizationStatus.UNRESTRICTED
      viewModel.refreshEnforcementHealth()
      advanceUntilIdle()

      var ready = viewModel.uiState.value as SettingsUiState.Ready
      assertEquals(ThemePreference.DARK, ready.summary.preferences.themePreference)
      assertFalse(ready.summary.preferences.wifiOnlyFilterUpdates)
      assertEquals(ThemePreference.DARK, prefsRepo.preferences.first().themePreference)
      assertEquals(
        UsageAccessPermissionStatus.ENABLED,
        ready.summary.usageAccessSnapshot.permissionStatus,
      )
      assertEquals(
        BatteryOptimizationStatus.UNRESTRICTED,
        ready.summary.batteryOptimizationSnapshot.status,
      )

      // Configure PIN via Settings
      viewModel.onNewPinInputChange("2580")
      viewModel.savePin()
      advanceUntilIdle()
      ready = viewModel.uiState.value as SettingsUiState.Ready
      assertTrue(ready.summary.pinState.isPinEnabled)
      assertTrue(ready.summary.pinState.isCredentialAvailable)

      // Request 24-hour voluntary removal; cannot execute early
      viewModel.request24HourRemoval()
      advanceUntilIdle()
      ready = viewModel.uiState.value as SettingsUiState.Ready
      assertEquals(RemovalUiStatus.PENDING, ready.summary.removalState.status)
      viewModel.executeRemovalRequest()
      advanceUntilIdle()
      ready = viewModel.uiState.value as SettingsUiState.Ready
      assertNotNull(ready.summary.removalState.errorMessage)

      // Advance >24h -> ready to execute, lifts active commitment & clears PIN
      timeProvider.wallClock = timeProvider.wallClock.plusElapsedDays(1)
      timeProvider.monotonic =
        com.example.domain.time.MonotonicTimestamp(
          timeProvider.monotonic.elapsedMillis + RemovalRequest.COOLING_OFF_MILLIS + 1000L
        )
      viewModel.refreshEnforcementHealth()
      advanceUntilIdle()
      ready = viewModel.uiState.value as SettingsUiState.Ready
      assertEquals(RemovalUiStatus.READY_TO_EXECUTE, ready.summary.removalState.status)

      viewModel.executeRemovalRequest()
      advanceUntilIdle()
      ready = viewModel.uiState.value as SettingsUiState.Ready
      assertEquals(RemovalUiStatus.NONE, ready.summary.removalState.status)
      assertFalse(ready.summary.pinState.isPinEnabled)
      assertTrue(
        (commitmentRepo.getCommitment(active.id) as PersistenceResult.Success).data
          is Commitment.EmergencyTerminated
      )
      job.cancel()
    }
}
