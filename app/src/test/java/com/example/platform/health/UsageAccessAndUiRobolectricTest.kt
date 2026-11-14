package com.example.platform.health

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.core.designsystem.component.UsageAccessCard
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import com.example.feature.home.DashboardContent
import com.example.feature.home.DashboardStatus
import com.example.feature.home.DashboardUiState
import com.example.feature.home.DashboardUsageCoverage
import com.example.feature.home.HomeScreen
import com.example.testing.FakeTimeProvider
import com.example.ui.theme.MizanTheme
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UsageAccessAndUiRobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `manifest declares PACKAGE_USAGE_STATS and AndroidUsageAccessMonitor reflects AppOps status`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    @Suppress("DEPRECATION")
    val pkgInfo =
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_PERMISSIONS,
      )
    assertTrue(
      pkgInfo.requestedPermissions.orEmpty().contains(android.Manifest.permission.PACKAGE_USAGE_STATS)
    )

    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val shadowAppOps = shadowOf(appOps)
    val monitor = AndroidUsageAccessMonitor(context, FakeTimeProvider())

    shadowAppOps.setMode(
      AppOpsManager.OPSTR_GET_USAGE_STATS,
      Process.myUid(),
      context.packageName,
      AppOpsManager.MODE_IGNORED,
    )
    monitor.refreshSystemStatus()
    assertEquals(
      UsageAccessPermissionStatus.DISABLED,
      monitor.usageAccessState.value.permissionStatus,
    )

    shadowAppOps.setMode(
      AppOpsManager.OPSTR_GET_USAGE_STATS,
      Process.myUid(),
      context.packageName,
      AppOpsManager.MODE_ALLOWED,
    )
    monitor.refreshSystemStatus()
    assertEquals(
      UsageAccessPermissionStatus.ENABLED,
      monitor.usageAccessState.value.permissionStatus,
    )

    val settingsIntent = monitor.createUsageAccessSettingsIntent()
    assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, settingsIntent.action)
  }

  @Test
  fun `usage access card renders disabled enabled and unavailable states and requires user tap`() {
    var clicks = 0
    val statusState =
      androidx.compose.runtime.mutableStateOf(
        UsageAccessSnapshot(UsageAccessPermissionStatus.DISABLED)
      )

    composeRule.setContent {
      MizanTheme {
        UsageAccessCard(
          usageAccessSnapshot = statusState.value,
          onOpenUsageAccessSettings = { clicks += 1 },
        )
      }
    }

    composeRule.onNodeWithTag("usage_access_status_badge").assertTextContains("Disabled", substring = true)
    assertEquals(0, clicks)
    composeRule.onNodeWithTag("usage_access_open_usage_settings_button").performClick()
    assertEquals(1, clicks)

    statusState.value = UsageAccessSnapshot(UsageAccessPermissionStatus.ENABLED)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("usage_access_status_badge").assertTextContains("Enabled", substring = true)

    statusState.value = UsageAccessSnapshot(UsageAccessPermissionStatus.UNAVAILABLE)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("usage_access_status_badge").assertTextContains("Unavailable", substring = true)
  }

  @Test
  fun `home screen renders unavailable incomplete and exhausted hybrid allowance states cleanly`() {
    val baseTime =
      WallClockPoint(DomainDate(2026, 11, 13), DomainTime(12, 0), 1_794_571_200_000L)
    val rules =
      CommitmentRules(
        mode = RestrictionMode.HYBRID,
        restrictedApps = setOf(RestrictedApp(AppId("com.instagram.android"), "Instagram")),
        dailyAllowance = 30.minutes,
      )
    val active =
      (CommitmentStateMachine.activate(
          CommitmentStateMachine.createDraft(CommitmentId("c-ui"), rules, baseTime),
          agreedToExactRules = true,
          now = baseTime,
        ) as CommitmentResult.Success)
        .commitment

    val stateHolder =
      androidx.compose.runtime.mutableStateOf(
        DashboardUiState.Success(
          DashboardContent(
            status =
              DashboardStatus.Active(
                commitment = active,
                dailyUsage = null,
                remainingAllowance = 0.minutes,
                hasReachedEnd = false,
                usageLoadError = null,
                usageCoverage = DashboardUsageCoverage.Unavailable,
              ),
            history = emptyList(),
            isPinEnabled = false,
          )
        )
      )

    composeRule.setContent {
      MizanTheme {
        HomeScreen(
          uiState = stateHolder.value,
          onRetry = {},
          onNavigateToSetup = {},
          onNavigateToReview = {},
          onNavigateToCompletion = {},
          onNavigateToEmergencyTermination = {},
        )
      }
    }

    // 1. Unavailable state (not mislabeled as 0m used)
    composeRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(androidx.compose.ui.test.hasTestTag("dashboard_allowance_summary"))
    composeRule.onNodeWithTag("dashboard_allowance_summary").assertTextContains("Usage unavailable", substring = true)

    // 2. Incomplete coverage with partial consumption
    stateHolder.value =
      DashboardUiState.Success(
        DashboardContent(
          status =
            DashboardStatus.Active(
              commitment = active,
              dailyUsage = DailyUsage(active.id, baseTime.date, consumed = 12.minutes),
              remainingAllowance = 18.minutes,
              hasReachedEnd = false,
              usageCoverage = DashboardUsageCoverage.Incomplete("Service restarted"),
            ),
          history = emptyList(),
          isPinEnabled = false,
        )
      )
    composeRule.waitForIdle()
    composeRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(androidx.compose.ui.test.hasTestTag("dashboard_allowance_summary"))
    composeRule.onNodeWithTag("dashboard_allowance_summary").assertTextContains("18m remaining", substring = true)
    composeRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(androidx.compose.ui.test.hasTestTag("dashboard_usage_coverage_status"))
    composeRule.onNodeWithTag("dashboard_usage_coverage_status").assertTextContains("Partial coverage", substring = true)

    // 3. Exhausted shared daily allowance
    stateHolder.value =
      DashboardUiState.Success(
        DashboardContent(
          status =
            DashboardStatus.Active(
              commitment = active,
              dailyUsage = DailyUsage(active.id, baseTime.date, consumed = 30.minutes),
              remainingAllowance = 0.minutes,
              hasReachedEnd = false,
              usageCoverage =
                DashboardUsageCoverage.Measured(
                  coverageStatus = com.example.domain.model.UsageCoverageStatus.MEASURED,
                  isUsageAccessEnabled = true,
                  isAccessibilityActive = true,
                ),
            ),
          history = emptyList(),
          isPinEnabled = false,
        )
      )
    composeRule.waitForIdle()
    composeRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(androidx.compose.ui.test.hasTestTag("dashboard_allowance_summary"))
    composeRule.onNodeWithTag("dashboard_allowance_summary").assertIsDisplayed()
    composeRule.onNodeWithTag("dashboard_allowance_summary").assertTextContains("Exhausted", substring = true)
  }
}
