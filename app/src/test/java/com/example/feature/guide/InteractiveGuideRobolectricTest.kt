package com.example.feature.guide

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import com.example.MainDispatcherRule
import com.example.core.designsystem.component.LocalMizanReducedMotion
import com.example.core.designsystem.component.MizanExplanationSlide
import com.example.core.designsystem.component.MizanSlideDeck
import com.example.core.designsystem.component.VpnFilterHealthCard
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.AppPreferences
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
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
import com.example.feature.review.CommitmentReviewScreen
import com.example.feature.review.CommitmentReviewUiState
import com.example.feature.settings.PinManagementUiState
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsSummary
import com.example.feature.settings.SettingsUiState
import com.example.platform.vpn.VpnFilterSnapshot
import com.example.platform.vpn.VpnServiceLifecycleStatus
import com.example.testing.FakeUserPreferencesRepository
import com.example.ui.theme.MizanTheme
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
@OptIn(ExperimentalCoroutinesApi::class)
class InteractiveGuideRobolectricTest {

  @get:Rule val composeRule = createComposeRule()
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 20),
      time = DomainTime(10, 0, 0),
      epochMillis = 1_795_168_800_000L,
    )

  private val sampleRules =
    CommitmentRules(
      mode = RestrictionMode.HYBRID,
      restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Social")),
      dailyAllowance = 30.minutes,
    )

  @Test
  fun `guide viewModel manages category filter, topic navigation, walkthrough state, and reduced motion`() =
    runTest {
      val prefsRepo = FakeUserPreferencesRepository()
      val viewModel =
        GuideViewModel(
          savedStateHandle = SavedStateHandle(mapOf("walkthroughMode" to true)),
          userPreferencesRepository = prefsRepo,
        )
      val job = launch { viewModel.uiState.collect {} }
      advanceUntilIdle()

      assertEquals(17, GuideTopicsCatalog.topics.size)
      assertTrue(viewModel.uiState.value.walkthroughMode)
      assertEquals("onboarding_setup", viewModel.uiState.value.activeTopic.id)

      viewModel.nextTopic()
      advanceUntilIdle()
      assertEquals("purpose_modes", viewModel.uiState.value.activeTopic.id)

      viewModel.previousTopic()
      advanceUntilIdle()
      assertEquals("onboarding_setup", viewModel.uiState.value.activeTopic.id)

      viewModel.selectCategory(GuideCategory.DATASETS_PRIVACY)
      advanceUntilIdle()
      assertFalse(viewModel.uiState.value.walkthroughMode)
      assertEquals("dataset_updates", viewModel.uiState.value.activeTopic.id)

      viewModel.setReduceMotion(true)
      advanceUntilIdle()
      assertTrue(prefsRepo.preferences.first().reduceGuideAnimations)

      viewModel.completeWalkthrough()
      advanceUntilIdle()
      assertTrue(prefsRepo.preferences.first().firstUseGuideCompleted)

      viewModel.replayWalkthrough()
      advanceUntilIdle()
      assertTrue(viewModel.uiState.value.walkthroughMode)
      assertFalse(prefsRepo.preferences.first().firstUseGuideCompleted)

      viewModel.skipWalkthrough()
      advanceUntilIdle()
      assertFalse(viewModel.uiState.value.walkthroughMode)
      assertTrue(prefsRepo.preferences.first().firstUseGuideCompleted)
      job.cancel()
    }

  @Test
  fun `slide deck supports swipe buttons, page counter, skip, final action, and reduced motion`() {
    var skipClicked = false
    var finalClicked = false
    val slides =
      listOf(
        MizanExplanationSlide(
          id = "s1",
          heading = "First Step",
          explanation = "Read step one calmly.",
          exampleBody = "Example one.",
          icon = Icons.Outlined.Tune,
        ),
        MizanExplanationSlide(
          id = "s2",
          heading = "Second Step",
          explanation = "Read step two calmly.",
          icon = Icons.Outlined.Shield,
        ),
        MizanExplanationSlide(
          id = "s3",
          heading = "Third Step",
          explanation = "Read step three calmly.",
          icon = Icons.Outlined.Lock,
        ),
      )

    composeRule.setContent {
      MizanTheme {
        CompositionLocalProvider(LocalMizanReducedMotion provides true) {
          MizanSlideDeck(
            slides = slides,
            testTagPrefix = "test_deck",
            skipLabel = "Skip",
            onSkip = { skipClicked = true },
            finalActionLabel = "Finish",
            onFinalAction = { finalClicked = true },
          )
        }
      }
    }

    composeRule.onNodeWithTag("test_deck_counter").assertTextContains("Slide 1 of 3")
    composeRule.onNodeWithTag("test_deck_back_button").assertIsNotEnabled()
    composeRule.onNodeWithTag("test_deck_next_button").performClick()

    composeRule.onNodeWithTag("test_deck_counter").assertTextContains("Slide 2 of 3")
    composeRule.onNodeWithTag("test_deck_back_button").assertIsEnabled().performClick()
    composeRule.onNodeWithTag("test_deck_counter").assertTextContains("Slide 1 of 3")

    composeRule.onNodeWithTag("test_deck_next_button").performClick()
    composeRule.onNodeWithTag("test_deck_next_button").performClick()
    composeRule.onNodeWithTag("test_deck_counter").assertTextContains("Slide 3 of 3")
    composeRule.onNodeWithTag("test_deck_done_button").performClick()
    assertTrue(finalClicked)

    composeRule.onNodeWithTag("test_deck_skip_button").performClick()
    assertTrue(skipClicked)
  }

  @Test
  fun `interactive guide screen renders 17 topics, mode demo interaction, breakdown, and controls`() {
    var skipped = false
    var reduceMotionValue = false
    var selectedCategory: GuideCategory? = null
    var selectedTopicId = GuideTopicsCatalog.topics[1].id // purpose_modes

    composeRule.setContent {
      MizanTheme {
        CompositionLocalProvider(LocalMizanReducedMotion provides true) {
          InteractiveGuideScreen(
            uiState =
              GuideUiState(
                selectedCategory = selectedCategory,
                selectedTopicId = selectedTopicId,
                walkthroughMode = true,
                firstUseGuideCompleted = false,
                reduceGuideAnimations = reduceMotionValue,
              ),
            onNavigateBack = {},
            onSelectCategory = { selectedCategory = it },
            onSelectTopic = { selectedTopicId = it },
            onNextTopic = {},
            onPreviousTopic = {},
            onCompleteWalkthrough = {},
            onSkipWalkthrough = { skipped = true },
            onReplayWalkthrough = {},
            onToggleReduceMotion = { reduceMotionValue = it },
          )
        }
      }
    }

    composeRule.onNodeWithTag("guide_topic_counter").assertTextContains("Topic 2 of 17")
    composeRule
      .onNodeWithTag("guide_active_topic_title")
      .assertTextContains("Purpose & Operating Modes", substring = true)

    composeRule
      .onNodeWithTag("guide_screen_list")
      .performScrollToNode(hasTestTag("guide_mode_chip_strict"))
    composeRule.onNodeWithTag("guide_mode_chip_strict").performScrollTo().performClick()
    composeRule.onNodeWithText("Protection coverage: Full 7-day cycle").performScrollTo().assertIsDisplayed()

    composeRule
      .onNodeWithTag("guide_screen_list")
      .performScrollToNode(hasTestTag("guide_progress_card"))
    composeRule.onNodeWithTag("guide_reduce_motion_switch").performScrollTo().performClick()
    assertTrue(reduceMotionValue)

    composeRule.onNodeWithTag("guide_skip_walkthrough_button").performScrollTo().performClick()
    assertTrue(skipped)
  }

  @Test
  fun `onboarding screen renders 5 stages, collapsed privacy disclosure, and skip action`() {
    var skipClicked = false
    var nextClicked = false

    composeRule.setContent {
      MizanTheme {
        CompositionLocalProvider(LocalMizanReducedMotion provides true) {
          OnboardingScreen(
            uiState =
              OnboardingUiState.Ready(
                stage = OnboardingStage.INTRODUCTION,
                revisitFromSettings = false,
              ),
            onContinue = { skipClicked = true },
            onNavigateBack = {},
            onAdvanceFromIntroduction = { nextClicked = true },
          )
        }
      }
    }

    composeRule.onNodeWithTag("onboarding_stage_indicator").assertTextContains("Step 1 of 5")
    composeRule.onNodeWithTag("onboarding_intro_card").assertIsDisplayed()
    composeRule.onNodeWithTag("onboarding_intro_privacy_toggle_content").assertDoesNotExist()
    composeRule.onNodeWithTag("onboarding_intro_privacy_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("onboarding_intro_privacy_toggle_content").assertExists()

    composeRule
      .onNodeWithTag("onboarding_screen_list")
      .performScrollToNode(hasTestTag("onboarding_stage_next_button"))
    composeRule.onNodeWithTag("onboarding_stage_next_button").performClick()
    assertTrue(nextClicked)

    composeRule
      .onNodeWithTag("onboarding_screen_list")
      .performScrollToNode(hasTestTag("onboarding_skip_button"))
    composeRule.onNodeWithTag("onboarding_skip_button").performClick()
    assertTrue(skipClicked)
  }

  @Test
  fun `home dashboard displays operational readiness badges, progress indicators, and collapsed health`() {
    val active =
      (CommitmentStateMachine.activate(
        commitment =
          CommitmentStateMachine.createDraft(CommitmentId("c-dash-guide"), sampleRules, baseTime),
        agreedToExactRules = true,
        now = baseTime,
      ) as CommitmentResult.Success).commitment

    composeRule.setContent {
      MizanTheme {
        CompositionLocalProvider(LocalMizanReducedMotion provides true) {
          HomeScreen(
            uiState =
              DashboardUiState.Success(
                DashboardContent(
                  status =
                    DashboardStatus.Active(
                      commitment = active,
                      dailyUsage =
                        DailyUsage(
                          commitmentId = active.id,
                          date = baseTime.date,
                          consumed = 15.minutes,
                        ),
                      remainingAllowance = 15.minutes,
                      hasReachedEnd = false,
                    ),
                  history = listOf(active),
                  isPinEnabled = false,
                  preferences = AppPreferences(firstUseGuideCompleted = false),
                  readiness =
                    DashboardProtectionReadiness(
                      appStatus = OperationalReadiness.OPERATIONAL,
                      webStatus = OperationalReadiness.CONFIGURED_ACTION_NEEDED,
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
    }

    composeRule.onNodeWithTag("home_contextual_help_button").performClick()
    composeRule.onNodeWithTag("home_contextual_guide_sheet").assertIsDisplayed()
    composeRule.onNodeWithTag("home_contextual_guide_sheet_close").performClick()

    composeRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(hasTestTag("dashboard_active_card"))
    composeRule.onNodeWithTag("dashboard_active_progress_bar").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("dashboard_allowance_progress_bar").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("dashboard_active_rules_toggle_content").assertDoesNotExist()

    composeRule
      .onNodeWithTag("home_screen_list")
      .performScrollToNode(hasTestTag("dashboard_protection_status_card"))
    composeRule.onNodeWithTag("dashboard_app_readiness_badge").assertTextContains("Operational")
    composeRule
      .onNodeWithTag("dashboard_web_readiness_badge")
      .assertTextContains("Configured · Action Needed")
    composeRule.onNodeWithTag("dashboard_system_health_toggle_content").assertDoesNotExist()
  }

  @Test
  fun `review screen uses collapsed limitations disclosure and contextual help`() {
    val draft =
      CommitmentStateMachine.createDraft(CommitmentId("c-rev-guide"), sampleRules, baseTime)

    composeRule.setContent {
      MizanTheme {
        CompositionLocalProvider(LocalMizanReducedMotion provides true) {
          CommitmentReviewScreen(
            uiState =
              CommitmentReviewUiState.Ready(
                draft = draft,
                previewStart = baseTime,
                previewEnd = baseTime.plusElapsedDays(7),
                hasActiveCommitmentConflict = false,
              ),
            onAgreementChanged = {},
            onActivate = {},
            onRetry = {},
            onNavigateBack = {},
          )
        }
      }
    }

    composeRule.onNodeWithTag("review_contextual_help_button").performClick()
    composeRule.onNodeWithTag("review_contextual_guide_sheet").assertIsDisplayed()
    composeRule.onNodeWithTag("review_contextual_guide_sheet_close").performClick()

    composeRule
      .onNodeWithTag("review_screen_list")
      .performScrollToNode(hasTestTag("review_limitations_toggle"))
    composeRule.onNodeWithTag("review_limitations_toggle_content").assertDoesNotExist()
    composeRule.onNodeWithTag("review_limitations_toggle").performClick()
    composeRule.onNodeWithTag("review_limitations_toggle_content").assertIsDisplayed()
  }

  @Test
  fun `vpn disclosure uses collapsed section inside health card`() {
    composeRule.setContent {
      MizanTheme {
        CompositionLocalProvider(LocalMizanReducedMotion provides true) {
          Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            VpnFilterHealthCard(
              vpnSnapshot =
                VpnFilterSnapshot(
                  status = VpnServiceLifecycleStatus.READY_INACTIVE,
                  hasActiveWebsiteCommitment = true,
                  hasConsented = false,
                  isPermissionPrepared = false,
                  isTunnelEstablished = false,
                ),
              onConsentChanged = {},
              onEnableAndStartVpn = {},
              onStopVpn = {},
              testTagPrefix = "test_vpn",
            )
          }
        }
      }
    }

    composeRule.onNodeWithTag("test_vpn_limitations_toggle_content").assertDoesNotExist()
    composeRule.onNodeWithTag("test_vpn_limitations_toggle").performScrollTo().performClick()
    composeRule.onNodeWithTag("test_vpn_limitations_toggle_content").assertIsDisplayed()
  }

  @Test
  fun `settings help guide card and remove pin confirmation dialog work safely`() {
    var openGuideCalled = false
    var replayWalkthroughCalled = false
    var reduceMotionSet = false
    var removePinCalled = false

    composeRule.setContent {
      MizanTheme {
        CompositionLocalProvider(LocalMizanReducedMotion provides true) {
          SettingsScreen(
            uiState =
              SettingsUiState.Ready(
                SettingsSummary(
                  preferences = AppPreferences(reduceGuideAnimations = false),
                  applicationId = "com.ibnmisbah.mizan",
                  versionName = "1.0",
                  pinState =
                    PinManagementUiState(
                      isPinEnabled = true,
                      isCredentialAvailable = true,
                      currentPinInput = "4829",
                    ),
                )
              ),
            onSelectTheme = {},
            onToggleWifiOnly = {},
            onRevisitOnboarding = {},
            onOpenUserGuide = { _, walkthrough ->
              if (walkthrough) replayWalkthroughCalled = true else openGuideCalled = true
            },
            onReplayWalkthrough = { replayWalkthroughCalled = true },
            onToggleReduceMotion = { reduceMotionSet = it },
            onRemovePin = { removePinCalled = true },
          )
        }
      }
    }

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_help_guide_card"))
    composeRule.onNodeWithTag("settings_open_guide_button").performScrollTo().performClick()
    assertTrue(openGuideCalled)
    composeRule.onNodeWithTag("settings_replay_walkthrough_button").performScrollTo().performClick()
    assertTrue(replayWalkthroughCalled)
    composeRule.onNodeWithTag("settings_reduce_motion_switch").performScrollTo().performClick()
    assertTrue(reduceMotionSet)

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_pin_remove_button"))
    composeRule.onNodeWithTag("settings_pin_remove_button").performClick()
    composeRule.onNodeWithTag("settings_pin_remove_confirm_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("settings_pin_remove_cancel_button").performClick()
    assertFalse(removePinCalled)

    composeRule.onNodeWithTag("settings_pin_remove_button").performClick()
    composeRule.onNodeWithTag("settings_pin_remove_confirm_button").performClick()
    assertTrue(removePinCalled)
  }
}
