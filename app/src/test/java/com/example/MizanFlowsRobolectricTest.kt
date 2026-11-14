package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import com.example.feature.completion.CommitmentCompletionScreen
import com.example.feature.completion.CommitmentCompletionUiState
import com.example.feature.emergency.EmergencyTerminationScreen
import com.example.feature.emergency.EmergencyTerminationUiState
import com.example.ui.theme.MizanTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = HiltTestApplication::class)
class MizanFlowsRobolectricTest {

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  @Test
  fun `setup review explicit activation and emergency termination end to end ui flow`() {
    composeTestRule.waitUntilTag("onboarding_screen_list")

    // Complete first-launch onboarding
    composeTestRule
      .onNodeWithTag("onboarding_screen_list")
      .performScrollToNode(hasTestTag("onboarding_complete_button"))
    composeTestRule.onNodeWithTag("onboarding_complete_button").performClick()

    // Navigate from Dashboard empty state to Setup
    composeTestRule.waitUntilTag("dashboard_start_setup_button")
    composeTestRule.onNodeWithTag("dashboard_start_setup_button").performClick()

    // Configure Strict mode + test restricted app package
    composeTestRule.waitUntilTag("setup_mode_strict")
    composeTestRule.onNodeWithTag("setup_mode_strict").performClick()
    composeTestRule
      .onNodeWithTag("commitments_screen_list")
      .performScrollToNode(hasTestTag("setup_add_custom_restricted_button"))
    composeTestRule.onNodeWithTag("setup_custom_restricted_label_input").performTextInput("Sample Social")
    composeTestRule
      .onNodeWithTag("setup_custom_restricted_package_input")
      .performTextInput("com.example.social")
    composeTestRule.onNodeWithTag("setup_add_custom_restricted_button").performClick()

    composeTestRule
      .onNodeWithTag("commitments_screen_list")
      .performScrollToNode(hasTestTag("setup_continue_review_button"))
    composeTestRule.onNodeWithTag("setup_continue_review_button").performClick()

    // Review screen: activation disabled until explicit agreement checkbox checked
    composeTestRule.waitUntilTag("review_duration_card")
    composeTestRule
      .onNodeWithTag("review_screen_list")
      .performScrollToNode(hasTestTag("review_agreement_card"))
    composeTestRule.onNodeWithTag("review_activate_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("review_agreement_checkbox").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("review_activate_button").assertIsEnabled().performClick()

    // Dashboard now renders active commitment with accessible status badge
    composeTestRule.waitUntilTag("dashboard_active_card")
    composeTestRule.onNodeWithTag("dashboard_active_card").assertIsDisplayed()
    composeTestRule
      .onNode(hasContentDescription("Commitment status indicator"), useUnmergedTree = true)
      .assertExists()

    // Navigate to Emergency Termination
    composeTestRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(hasTestTag("dashboard_emergency_terminate_button"))
    composeTestRule.onNodeWithTag("dashboard_emergency_terminate_button").performClick()

    // Warning confirmation required
    composeTestRule.waitUntilTag("emergency_consequence_card")
    composeTestRule
      .onNodeWithTag("emergency_screen_list")
      .performScrollToNode(hasTestTag("emergency_confirm_card"))
    composeTestRule.onNodeWithTag("emergency_confirm_button").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("emergency_confirm_checkbox").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("emergency_confirm_button").assertIsEnabled().performClick()

    // Dashboard now renders Emergency Terminated state
    composeTestRule.waitUntilTag("dashboard_terminated_card")
    composeTestRule.onNodeWithTag("dashboard_terminated_card").assertIsDisplayed()
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MizanScreenStatesRobolectricTest {

  @get:Rule val composeRule = androidx.compose.ui.test.junit4.createComposeRule()

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(9, 0, 0),
      epochMillis = 1_794_560_400_000L,
    )

  private val rules =
    CommitmentRules(
      mode = RestrictionMode.STRICT,
      restrictedApps = setOf(RestrictedApp(AppId("com.instagram.android"), "Instagram")),
      requirePinForEmergencyTermination = true,
    )

  @Test
  fun `completion screen renders all explicit next step options without auto renewal`() {
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-1"), rules, baseTime)
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseTime)
          as CommitmentResult.Success)
        .commitment
    val completed =
      (CommitmentStateMachine.completeIfReachedEnd(active, active.endsAt)
          as CommitmentResult.Success)
        .commitment

    composeRule.setContent {
      MizanTheme {
        CommitmentCompletionScreen(
          uiState = CommitmentCompletionUiState.Ready(completed = completed),
          onSelectNextStep = {},
          onNavigateBack = {},
          onNavigateToDashboard = {},
        )
      }
    }

    composeRule
      .onNodeWithTag("completion_screen_list")
      .performScrollToNode(hasTestTag("completion_choices_card"))
    composeRule.onNodeWithTag("completion_finish_button").assertIsDisplayed()
    composeRule.onNodeWithTag("completion_begin_another_button").assertIsDisplayed()
    composeRule.onNodeWithTag("completion_discipline_mode_button").assertIsDisplayed()
  }

  @Test
  fun `emergency screen renders fail closed blocker when pin verification unavailable`() {
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-2"), rules, baseTime)
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseTime)
          as CommitmentResult.Success)
        .commitment

    composeRule.setContent {
      MizanTheme {
        EmergencyTerminationScreen(
          uiState = EmergencyTerminationUiState.BlockedByUnavailablePinVerification(active),
          onConfirmChanged = {},
          onConfirmTermination = {},
          onNavigateBack = {},
        )
      }
    }

    composeRule.onNodeWithTag("emergency_pin_blocked_card").assertIsDisplayed()
    composeRule.onNodeWithTag("emergency_confirm_button").assertDoesNotExist()
  }
}
