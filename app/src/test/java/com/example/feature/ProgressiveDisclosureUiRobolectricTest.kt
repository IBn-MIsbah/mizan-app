package com.example.feature

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.example.core.designsystem.component.LocalMizanReducedMotion
import com.example.domain.model.AppPreferences
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import com.example.feature.home.DashboardContent
import com.example.feature.home.DashboardProtectionReadiness
import com.example.feature.home.DashboardStatus
import com.example.feature.home.DashboardUiState
import com.example.feature.home.HomeScreen
import com.example.feature.home.OperationalReadiness
import com.example.feature.onboarding.OnboardingScreen
import com.example.feature.onboarding.OnboardingStage
import com.example.feature.onboarding.OnboardingUiState
import com.example.feature.settings.PinManagementUiState
import com.example.feature.settings.RemovalRequestUiState
import com.example.feature.settings.RemovalUiStatus
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsSummary
import com.example.feature.settings.SettingsUiState
import com.example.ui.theme.MizanTheme
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProgressiveDisclosureUiRobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 20),
      time = DomainTime(10, 0, 0),
      epochMillis = 1_795_168_800_000L,
    )

  @Test
  fun `settings screen groups controls into domains and hides secondary disclosures until expanded`() {
    var setupClicked = false
    composeRule.setContent {
      MizanTheme {
        CompositionLocalProvider(LocalMizanReducedMotion provides true) {
          SettingsScreen(
            uiState =
              SettingsUiState.Ready(
                SettingsSummary(
                  preferences = AppPreferences(reduceGuideAnimations = true),
                  applicationId = "com.ibnmisbah.mizan",
                  versionName = "1.0",
                  pinState = PinManagementUiState(isPinEnabled = false),
                  removalState =
                    RemovalRequestUiState(
                      status = RemovalUiStatus.NONE,
                      hasActiveCommitment = true,
                      remainingDuration = 24.hours,
                    ),
                )
              ),
            onSelectTheme = {},
            onToggleWifiOnly = {},
            onRevisitOnboarding = {},
            onNavigateToSetup = { setupClicked = true },
          )
        }
      }
    }

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_section_system_health"))
    composeRule.onNodeWithTag("settings_section_system_health").assertIsDisplayed()

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_section_website"))
    composeRule.onNodeWithTag("settings_section_website").assertIsDisplayed()

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_section_commitment"))
    composeRule.onNodeWithTag("settings_section_commitment").assertIsDisplayed()
    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_open_setup_button"))
    composeRule.onNodeWithTag("settings_open_setup_button").performScrollTo().performClick()
    assertTrue(setupClicked)

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_section_security"))
    composeRule.onNodeWithTag("settings_section_security").assertIsDisplayed()
    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_pin_details_toggle"))
    composeRule.onNodeWithTag("settings_pin_details_toggle_content").assertDoesNotExist()
    composeRule.onNodeWithTag("settings_pin_details_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("settings_pin_details_toggle_content").assertExists()

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_removal_details_toggle"))
    composeRule.onNodeWithTag("settings_removal_details_toggle_content").assertDoesNotExist()
    composeRule.onNodeWithTag("settings_removal_details_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("settings_removal_details_toggle_content").assertExists()

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_section_preferences"))
    composeRule.onNodeWithTag("settings_section_preferences").assertIsDisplayed()
    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_privacy_details_toggle"))
    composeRule.onNodeWithTag("settings_privacy_details_toggle_content").assertDoesNotExist()
    composeRule.onNodeWithTag("settings_privacy_details_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("settings_privacy_details_toggle_content").assertExists()
  }

  @Test
  fun `onboarding permissions stage renders concise cards with collapsed detail toggles`() {
    composeRule.setContent {
      MizanTheme {
        OnboardingScreen(
          uiState = OnboardingUiState.Ready(stage = OnboardingStage.PERMISSIONS),
          onContinue = {},
          onNavigateBack = {},
        )
      }
    }

    composeRule.onNodeWithTag("onboarding_stage_indicator").assertTextContains("Step 2 of 5")
    composeRule.onNodeWithTag("onboarding_perm_a11y_card").assertIsDisplayed()
    composeRule.onNodeWithTag("onboarding_perm_a11y_details_toggle_content").assertDoesNotExist()
    composeRule.onNodeWithTag("onboarding_perm_a11y_details_toggle").performClick()
    composeRule.onNodeWithTag("onboarding_perm_a11y_details_toggle_content").assertExists()
    composeRule
      .onNodeWithTag("onboarding_screen_list")
      .performScrollToNode(hasTestTag("onboarding_perm_usage_card"))
    composeRule.onNodeWithTag("onboarding_perm_usage_card").assertIsDisplayed()
    composeRule
      .onNodeWithTag("onboarding_screen_list")
      .performScrollToNode(hasTestTag("onboarding_perm_vpn_card"))
    composeRule.onNodeWithTag("onboarding_perm_vpn_card").assertIsDisplayed()
  }

  @Test
  fun `onboarding agreement stage requires checkbox before activating commitment`() {
    var agreed by mutableStateOf(false)
    composeRule.setContent {
      MizanTheme {
        OnboardingScreen(
          uiState =
            OnboardingUiState.Ready(
              stage = OnboardingStage.AGREEMENT,
              previewStart = baseTime,
              previewEnd = baseTime.plusElapsedDays(7),
              agreedToExactRules = agreed,
            ),
          onContinue = {},
          onNavigateBack = {},
          onAgreementChanged = { agreed = it },
        )
      }
    }

    composeRule.onNodeWithTag("onboarding_stage_indicator").assertTextContains("Step 5 of 5")
    composeRule.onNodeWithTag("onboarding_activate_button").assertIsNotEnabled()
    composeRule.onNodeWithTag("onboarding_agreement_checkbox").performClick()
    composeRule.waitForIdle()
    assertTrue(agreed)
    composeRule.onNodeWithTag("onboarding_activate_button").assertIsEnabled()
  }

  @Test
  fun `onboarding configuration stage exposes setup cards`() {
    composeRule.setContent {
      MizanTheme {
        OnboardingScreen(
          uiState = OnboardingUiState.Ready(stage = OnboardingStage.CONFIGURATION),
          onContinue = {},
          onNavigateBack = {},
        )
      }
    }

    composeRule.onNodeWithTag("onboarding_stage_indicator").assertTextContains("Step 3 of 5")
    composeRule.onNodeWithTag("setup_mode_card").assertIsDisplayed()
    composeRule
      .onNodeWithTag("onboarding_screen_list")
      .performScrollToNode(hasTestTag("setup_website_filtering_card"))
    composeRule.onNodeWithTag("setup_website_filtering_card").assertIsDisplayed()
  }

  @Test
  fun `onboarding review stage shows 7 day preview and collapsed limits disclosure`() {
    composeRule.setContent {
      MizanTheme {
        OnboardingScreen(
          uiState =
            OnboardingUiState.Ready(
              stage = OnboardingStage.REVIEW,
              previewStart = baseTime,
              previewEnd = baseTime.plusElapsedDays(7),
            ),
          onContinue = {},
          onNavigateBack = {},
        )
      }
    }

    composeRule.onNodeWithTag("onboarding_stage_indicator").assertTextContains("Step 4 of 5")
    composeRule.onNodeWithTag("onboarding_review_duration_card").assertIsDisplayed()
    composeRule
      .onNodeWithTag("onboarding_screen_list")
      .performScrollToNode(hasTestTag("onboarding_review_rules_card"))
    composeRule.onNodeWithTag("onboarding_review_limits_toggle_content").assertDoesNotExist()
    composeRule.onNodeWithTag("onboarding_review_limits_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("onboarding_review_limits_toggle_content").assertExists()
  }

  @Test
  fun `home dashboard renders all operational readiness badge labels cleanly`() {
    var currentStatus by mutableStateOf(OperationalReadiness.NOT_CONFIGURED)
    val statuses =
      listOf(
        OperationalReadiness.NOT_CONFIGURED to "Not Configured",
        OperationalReadiness.CONFIGURED_ACTION_NEEDED to "Configured · Action Needed",
        OperationalReadiness.ENABLED_NOT_OPERATIONAL to "Enabled · Not Operational",
        OperationalReadiness.OPERATIONAL to "Operational",
        OperationalReadiness.UNAVAILABLE to "Unavailable",
      )

    composeRule.setContent {
      MizanTheme {
        HomeScreen(
          uiState =
            DashboardUiState.Success(
              DashboardContent(
                status = DashboardStatus.NoCommitment,
                history = emptyList(),
                isPinEnabled = false,
                readiness =
                  DashboardProtectionReadiness(
                    appStatus = currentStatus,
                    webStatus = OperationalReadiness.NOT_CONFIGURED,
                  ),
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

    statuses.forEach { (status, expectedLabel) ->
      currentStatus = status
      composeRule.waitForIdle()
      composeRule
        .onNodeWithTag("home_screen_list")
        .performScrollToNode(hasTestTag("dashboard_protection_status_card"))
      composeRule
        .onNodeWithTag("dashboard_app_readiness_badge")
        .performScrollTo()
        .assertTextContains(expectedLabel)
    }
  }
}
