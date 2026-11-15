package com.example.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.MainDispatcherRule
import com.example.ui.theme.MizanTheme
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentRulesValidator
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.RulesValidationResult
import com.example.domain.model.AppId
import com.example.domain.model.AppPreferences
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.feature.commitments.YouTubeShortsSetupCard
import com.example.platform.health.AccessibilityPermissionStatus
import com.example.platform.health.EnforcementHealthSnapshot
import com.example.platform.health.EnforcementHealthStatus
import com.example.testing.FakeTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsAndSetupYouTubeShortsUiTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `youtube shorts setup card starts disabled respects accessibility status and expands explanation`() {
    var toggledValue = false
    var openSettingsClicks = 0

    composeRule.setContent {
      MizanTheme {
        YouTubeShortsSetupCard(
          blockYouTubeShorts = toggledValue,
          healthSnapshot =
            EnforcementHealthSnapshot(
              permissionStatus = AccessibilityPermissionStatus.DISABLED,
              isServiceConnected = false,
              ruleStateAvailable = true,
              healthStatus = EnforcementHealthStatus.DISABLED,
            ),
          onToggleBlockYouTubeShorts = { toggledValue = it },
          onOpenAccessibilitySettings = { openSettingsClicks += 1 },
          testTagPrefix = "setup_youtube_shorts",
        )
      }
    }

    composeRule.onNodeWithTag("setup_youtube_shorts_switch").assertIsOff().assertIsEnabled()
    composeRule
      .onNodeWithTag("setup_youtube_shorts_a11y_status")
      .assertTextContains("Accessibility: Accessibility Disabled", substring = true)
    composeRule
      .onNodeWithTag("setup_youtube_shorts_open_a11y_button")
      .assertIsDisplayed()
      .performClick()
    assertEquals(1, openSettingsClicks)

    // Expand explanation & check honest YouTube update limitation
    composeRule.onNodeWithTag("setup_youtube_shorts_details_toggle").performClick()
    composeRule
      .onNodeWithTag("setup_youtube_shorts_details_toggle_content")
      .assertIsDisplayed()

    // Toggle Shorts setting explicitly
    composeRule.onNodeWithTag("setup_youtube_shorts_switch").performClick()
    assertTrue(toggledValue)
  }

  @Test
  fun `settings screen shows YouTube Shorts card locked when commitment is active`() {
    val activeHealth =
      EnforcementHealthSnapshot(
        permissionStatus = AccessibilityPermissionStatus.ENABLED,
        isServiceConnected = true,
        ruleStateAvailable = true,
        healthStatus = EnforcementHealthStatus.ACTIVE,
      )
    assertEquals(EnforcementHealthStatus.ACTIVE, activeHealth.healthStatus)

    composeRule.setContent {
      MizanTheme {
        SettingsScreen(
          uiState =
            SettingsUiState.Ready(
              SettingsSummary(
                preferences = AppPreferences(),
                applicationId = "com.ibnmisbah.mizan",
                versionName = "1.0.0",
                healthSnapshot = activeHealth,
                youtubeShortsBlocked = true,
                youtubeShortsRuleLocked = true,
              )
            ),
          onSelectTheme = {},
          onToggleWifiOnly = {},
          onRevisitOnboarding = {},
        )
      }
    }

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_youtube_shorts_card"))

    composeRule
      .onNodeWithTag("settings_youtube_shorts_locked_badge")
      .performScrollTo()
      .assertIsDisplayed()
    composeRule
      .onNodeWithTag("settings_youtube_shorts_switch")
      .performScrollTo()
      .assertIsOn()
      .assertIsNotEnabled()
    composeRule
      .onNodeWithTag("settings_youtube_shorts_a11y_status")
      .performScrollTo()
      .assertTextContains("Accessibility: Enabled & Connected", substring = true)
    composeRule.onNodeWithTag("settings_youtube_shorts_open_a11y_button").assertDoesNotExist()
  }

  @Test
  fun `validator defaults blockYouTubeShorts to false and allows Shorts-only commitment when opted in`() {
    val timeProvider = FakeTimeProvider()
    val defaultRules = CommitmentRules(mode = RestrictionMode.HYBRID, restrictedApps = emptySet())
    assertFalse(defaultRules.blockYouTubeShorts)
    assertTrue(
      CommitmentRulesValidator.validateForActivation(defaultRules) is RulesValidationResult.Invalid
    )

    val shortsOnlyRules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = emptySet(),
        blockYouTubeShorts = true,
      )
    assertEquals(
      RulesValidationResult.Valid,
      CommitmentRulesValidator.validateForActivation(shortsOnlyRules),
    )

    val draft =
      CommitmentStateMachine.createDraft(
        CommitmentId("c-shorts-only"),
        shortsOnlyRules,
        timeProvider.wallClock,
      )
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
          as CommitmentResult.Success)
        .commitment
    assertTrue(active.rules.blockYouTubeShorts)
    // Never blocks whole YouTube unless explicitly added
    assertFalse(active.rules.isRestricted(AppId("com.google.android.youtube")))
    assertFalse(active.rules.isRestricted(AppId("com.example.social")))
    assertTrue(
      active.rules.copy(restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Social")))
        .blockYouTubeShorts
    )
  }
}
