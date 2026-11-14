package com.example.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.domain.insights.BaselineComparison
import com.example.domain.insights.DailyTrendPoint
import com.example.domain.insights.DashboardInsights
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.AppPreferences
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import com.example.feature.emergency.EmergencyTerminationScreen
import com.example.feature.emergency.EmergencyTerminationUiState
import com.example.feature.home.DashboardContent
import com.example.feature.home.DashboardStatus
import com.example.feature.home.DashboardUiState
import com.example.feature.home.HomeScreen
import com.example.feature.settings.PinManagementUiState
import com.example.feature.settings.RemovalRequestUiState
import com.example.feature.settings.RemovalUiStatus
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsSummary
import com.example.feature.settings.SettingsUiState
import com.example.platform.health.BatteryOptimizationSnapshot
import com.example.platform.health.BatteryOptimizationStatus
import com.example.ui.theme.MizanTheme
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardeningUiRobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 20),
      time = DomainTime(10, 0, 0),
      epochMillis = 1_795_168_800_000L,
    )

  private val pinRules =
    CommitmentRules(
      mode = RestrictionMode.HYBRID,
      restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Social")),
      dailyAllowance = 30.minutes,
      requirePinForEmergencyTermination = true,
    )

  private fun activeCommitment() =
    (CommitmentStateMachine.activate(
      commitment =
        CommitmentStateMachine.createDraft(CommitmentId("c-ui-m7"), pinRules, baseTime),
      agreedToExactRules = true,
      now = baseTime,
    ) as CommitmentResult.Success).commitment

  @Test
  fun `emergency screen requires non-blank PIN and shows lockout notice when required`() {
    var submittedClicks = 0
    var latestPin = ""
    composeRule.setContent {
      MizanTheme {
        EmergencyTerminationScreen(
          uiState =
            EmergencyTerminationUiState.Ready(
              active = activeCommitment(),
              confirmedWarning = true,
              requiresPin = true,
              pinInput = "",
              lockoutRemainingSeconds = 25L,
            ),
          onConfirmChanged = {},
          onConfirmTermination = { submittedClicks += 1 },
          onNavigateBack = {},
          onPinChanged = { latestPin = it },
        )
      }
    }

    composeRule
      .onNodeWithTag("emergency_screen_list")
      .performScrollToNode(hasTestTag("emergency_pin_input_card"))
    composeRule.onNodeWithTag("emergency_pin_lockout_text").assertIsDisplayed()
    composeRule.onNodeWithTag("emergency_pin_input").performTextInput("4829")
    assertEquals("4829", latestPin)

    // Since uiState passed pinInput = "", confirm remains disabled until non-blank PIN state arrives
    composeRule
      .onNodeWithTag("emergency_screen_list")
      .performScrollToNode(hasTestTag("emergency_confirm_button"))
    composeRule.onNodeWithTag("emergency_confirm_button").assertIsNotEnabled()
    assertEquals(0, submittedClicks)
  }

  @Test
  fun `settings screen renders PIN setup 24h removal ready state and battery optimization card`() {
    var savePinClicked = false
    var executeRemovalClicked = false
    var openBatteryClicked = false
    composeRule.setContent {
      MizanTheme {
        SettingsScreen(
          uiState =
            SettingsUiState.Ready(
              SettingsSummary(
                preferences = AppPreferences(),
                applicationId = "com.ibnmisbah.mizan",
                versionName = "1.0",
                batteryOptimizationSnapshot =
                  BatteryOptimizationSnapshot(status = BatteryOptimizationStatus.OPTIMIZED),
                pinState = PinManagementUiState(isPinEnabled = false, newPinInput = "4829"),
                removalState =
                  RemovalRequestUiState(
                    status = RemovalUiStatus.READY_TO_EXECUTE,
                    requestId = "rem-1",
                    requestedAt = baseTime,
                    unlocksAt = baseTime.plusElapsedDays(1),
                    remainingDuration = 0.hours,
                    hasActiveCommitment = true,
                  ),
              )
            ),
          onSelectTheme = {},
          onToggleWifiOnly = {},
          onRevisitOnboarding = {},
          onSavePin = { savePinClicked = true },
          onExecuteRemoval = { executeRemovalClicked = true },
          onOpenBatterySettings = { openBatteryClicked = true },
        )
      }
    }

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_pin_save_button"))
    composeRule.onNodeWithTag("settings_pin_save_button").performClick()
    assertTrue(savePinClicked)

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_removal_execute_button"))
    composeRule.onNodeWithTag("settings_removal_execute_button").assertIsEnabled().performClick()
    assertTrue(executeRemovalClicked)

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_battery_optimization_open_settings_button"))
    composeRule
      .onNodeWithTag("settings_battery_optimization_open_settings_button")
      .performClick()
    assertTrue(openBatteryClicked)
  }

  @Test
  fun `home dashboard renders insights metrics 7 day chart baseline and battery warning`() {
    val active = activeCommitment()
    val trend =
      (6 downTo 0).map { daysAgo ->
        DailyTrendPoint(
          date = baseTime.date.minusDays(daysAgo),
          consumed = 15.minutes,
          allowance = 30.minutes,
          blockedAttempts = 1,
          coverageStatus = com.example.domain.model.UsageCoverageStatus.MEASURED,
          withinAllowance = true,
        )
      }
    val insights =
      DashboardInsights(
        todayUsage = 15.minutes,
        todayBlockedAttempts = 3,
        activeCommitmentBlockedAttempts = 7,
        totalBlockedAttemptsRecorded = 7,
        completedCommitmentsCount = 1,
        emergencyTerminatedCount = 0,
        totalCommitmentsRecorded = 2,
        consecutiveDaysWithinAllowance = 5,
        weeklyTrend = trend,
        weeklyTotalUsage = 105.minutes,
        weeklyAverageDailyUsage = 15.minutes,
        baselineComparison =
          BaselineComparison.InsufficientData(recordedDaysCount = 4, requiredDaysCount = 6),
      )

    composeRule.setContent {
      MizanTheme {
        HomeScreen(
          uiState =
            DashboardUiState.Success(
              DashboardContent(
                status =
                  DashboardStatus.Active(
                    commitment = active,
                    dailyUsage = null,
                    remainingAllowance = 15.minutes,
                    hasReachedEnd = false,
                  ),
                history = listOf(active),
                isPinEnabled = true,
                batteryOptimizationSnapshot =
                  BatteryOptimizationSnapshot(status = BatteryOptimizationStatus.OPTIMIZED),
                insights = insights,
              )
            ),
          onRetry = {},
          onNavigateToSetup = {},
          onNavigateToReview = {},
          onNavigateToCompletion = {},
          onNavigateToEmergencyTermination = {},
        )
      }
    }

    composeRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(hasTestTag("dashboard_insights_card"))
    composeRule.onNodeWithTag("insights_today_blocked_value").assertTextContains("3")
    composeRule.onNodeWithTag("insights_scope_blocked_value").assertTextContains("7")
    composeRule.onNodeWithTag("insights_streak_value").assertTextContains("5d")
    composeRule.onNodeWithTag("insights_trend_day_0").assertIsDisplayed()
    composeRule.onNodeWithTag("insights_trend_day_6").assertIsDisplayed()
    composeRule.onNodeWithTag("insights_baseline_text").assertTextContains("4 of 6", substring = true)

    composeRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(hasTestTag("dashboard_battery_optimization_card"))
    composeRule
      .onNodeWithTag("dashboard_battery_optimization_open_settings_button")
      .performScrollTo()
      .assertIsDisplayed()
  }
}
