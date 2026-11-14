package com.example.feature.settings

import com.example.MainDispatcherRule
import com.example.domain.model.ThemePreference
import com.example.platform.health.UsageAccessPermissionStatus
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeDomainFilterRepository
import com.example.testing.FakeEnforcementHealthMonitor
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
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `settings viewModel reflects and updates persisted preferences and usage access state`() = runTest {
    val prefsRepo = FakeUserPreferencesRepository()
    val healthMonitor = FakeEnforcementHealthMonitor()
    val usageAccessMonitor = FakeUsageAccessMonitor(UsageAccessPermissionStatus.DISABLED)
    val vpnMonitor = FakeVpnFilterMonitor()
    val domainFilterRepo = FakeDomainFilterRepository()
    val commitmentRepo = FakeCommitmentRepository()
    val usageRepo = FakeDailyUsageRepository()
    val viewModel =
      SettingsViewModel(
        userPreferencesRepository = prefsRepo,
        enforcementHealthMonitor = healthMonitor,
        usageAccessMonitor = usageAccessMonitor,
        vpnFilterMonitor = vpnMonitor,
        domainFilterRepository = domainFilterRepo,
        commitmentRepository = commitmentRepo,
        usageStatsReconciler =
          createTestUsageReconciler(
            usageAccessMonitor = usageAccessMonitor,
            dailyUsageRepository = usageRepo,
          ),
      )
    val job = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    viewModel.setThemePreference(ThemePreference.DARK)
    viewModel.setWifiOnlyFilterUpdates(false)
    usageAccessMonitor.systemStatus = UsageAccessPermissionStatus.ENABLED
    viewModel.refreshEnforcementHealth()
    advanceUntilIdle()

    val ready = viewModel.uiState.value as SettingsUiState.Ready
    assertEquals(ThemePreference.DARK, ready.summary.preferences.themePreference)
    assertFalse(ready.summary.preferences.wifiOnlyFilterUpdates)
    assertEquals(ThemePreference.DARK, prefsRepo.preferences.first().themePreference)
    assertEquals(
      UsageAccessPermissionStatus.ENABLED,
      ready.summary.usageAccessSnapshot.permissionStatus,
    )
    job.cancel()
  }
}
