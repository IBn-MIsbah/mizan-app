package com.example.platform.enforcement

import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AllowedApp
import com.example.domain.model.AllowlistCategory
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import com.example.platform.accessibility.AccessibilityNodeSnapshot
import com.example.platform.accessibility.AppRestrictionAccessibilityService
import com.example.platform.accessibility.DetectionConfidence
import com.example.platform.accessibility.ShortsDetectionClassification
import com.example.platform.accessibility.ShortsDetectionResult
import com.example.platform.accessibility.ShortsSignalCategory
import com.example.platform.accessibility.YouTubeShortsDetector
import com.example.platform.health.AccessibilityPermissionStatus
import com.example.testing.FakeBlockingUiLauncher
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YouTubeShortsEnforcementTest {

  private val ownPackage = "com.ibnmisbah.mizan"
  private val youtubePkg = YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(14, 0, 0),
      epochMillis = 1_794_578_400_000L,
    )

  private fun shortsSnapshot(): AccessibilityNodeSnapshot =
    AccessibilityNodeSnapshot(
      packageName = youtubePkg,
      viewIdResourceName = "$youtubePkg:id/reel_watch_fragment_root",
      isVisibleToUser = true,
      children =
        listOf(
          AccessibilityNodeSnapshot(
            packageName = youtubePkg,
            viewIdResourceName = "$youtubePkg:id/reel_player_page_container",
            isVisibleToUser = true,
          ),
          AccessibilityNodeSnapshot(
            packageName = youtubePkg,
            viewIdResourceName = "$youtubePkg:id/reel_dyn_like",
            contentDescription = "Like this video",
            isVisibleToUser = true,
          ),
        ),
    )

  private fun homeSnapshot(): AccessibilityNodeSnapshot =
    AccessibilityNodeSnapshot(
      packageName = youtubePkg,
      viewIdResourceName = "$youtubePkg:id/results",
      isVisibleToUser = true,
      children =
        listOf(
          AccessibilityNodeSnapshot(
            packageName = youtubePkg,
            viewIdResourceName = "$youtubePkg:id/shelf_header",
            text = "Shorts",
            isVisibleToUser = true,
          )
        ),
    )

  private fun normalWatchSnapshot(): AccessibilityNodeSnapshot =
    AccessibilityNodeSnapshot(
      packageName = youtubePkg,
      viewIdResourceName = "$youtubePkg:id/watch_player",
      isVisibleToUser = true,
      children =
        listOf(
          AccessibilityNodeSnapshot(
            packageName = youtubePkg,
            viewIdResourceName = "$youtubePkg:id/time_bar_current_time",
            text = "02:15",
            isVisibleToUser = true,
          )
        ),
    )

  private class RecordingFeedbackDisplayer : YouTubeShortsFeedbackDisplayer {
    var count: Int = 0

    override fun showShortsRestrictedFeedback() {
      count += 1
    }
  }

  private fun activeCommitment(
    blockYouTubeShorts: Boolean,
    restrictWholeYouTube: Boolean = false,
    allowlistYouTube: Boolean = false,
  ): Commitment.Active {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps =
          if (restrictWholeYouTube) setOf(RestrictedApp(AppId(youtubePkg), "YouTube"))
          else setOf(RestrictedApp(AppId("com.example.other"), "Other Social")),
        allowedApps =
          if (allowlistYouTube) {
            setOf(AllowedApp(AppId(youtubePkg), "YouTube", AllowlistCategory.CUSTOM))
          } else {
            emptySet()
          },
        blockYouTubeShorts = blockYouTubeShorts,
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-yt-1"), rules, baseTime)
    return (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseTime)
        as CommitmentResult.Success)
      .commitment
  }

  private fun buildCoordinator(
    commitments: List<Commitment>,
    timeProvider: FakeTimeProvider = FakeTimeProvider(initialWallClock = baseTime),
    feedbackDisplayer: YouTubeShortsFeedbackDisplayer = RecordingFeedbackDisplayer(),
  ): Triple<AppRestrictionCoordinator, FakeDailyUsageRepository, FakeSecurityEventRepository> {
    val usageRepo = FakeDailyUsageRepository()
    val securityRepo = FakeSecurityEventRepository()
    val usageTracker =
      com.example.testing.createTestUsageTracker(
        dailyUsageRepository = usageRepo,
        securityEventRepository = securityRepo,
        timeProvider = timeProvider,
        ownPackageName = ownPackage,
      )
    val usageReconciler =
      com.example.testing.createTestUsageReconciler(
        dailyUsageRepository = usageRepo,
        timeProvider = timeProvider,
        ownPackageName = ownPackage,
      )
    val coordinator =
      AppRestrictionCoordinator(
        commitmentRepository = FakeCommitmentRepository(commitments),
        dailyUsageRepository = usageRepo,
        securityEventRepository = securityRepo,
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        healthMonitor = FakeEnforcementHealthMonitor(AccessibilityPermissionStatus.ENABLED),
        blockingUiLauncher = FakeBlockingUiLauncher(),
        usageTracker = usageTracker,
        usageReconciler = usageReconciler,
        dispatcherProvider = FakeDispatcherProvider(),
        ownPackageName = ownPackage,
        youtubeShortsFeedbackDisplayer = feedbackDisplayer,
      )
    return Triple(coordinator, usageRepo, securityRepo)
  }

  @Test
  fun `state machine triggers once per entry enters cooldown and resets on clean exit`() {
    val sm =
      YouTubeShortsEnforcementStateMachine(
        backCooldownMillis = 1_800L,
        feedbackCooldownMillis = 4_000L,
        settleBeforeResetMillis = 500L,
      )
    val confirmed =
      ShortsDetectionResult(
        classification = ShortsDetectionClassification.CONFIRMED_SHORTS_PLAYER,
        confidence = DetectionConfidence.HIGH,
        matchedSignals = setOf(ShortsSignalCategory.REEL_PLAYER_CONTAINER),
      )
    val nonShorts =
      ShortsDetectionResult(
        classification = ShortsDetectionClassification.NON_SHORTS_SCREEN,
        confidence = DetectionConfidence.HIGH,
      )
    val uncertain =
      ShortsDetectionResult(
        classification = ShortsDetectionClassification.UNCERTAIN,
        confidence = DetectionConfidence.LOW,
      )

    // Uncertain never triggers back
    assertEquals(YouTubeShortsAction.None, sm.onDetectionEvaluated(uncertain, 1_000L))
    assertEquals(YouTubeShortsEnforcementState.POSSIBLE_SHORTS, sm.state)

    // Confirmed Shorts triggers exactly once + feedback
    assertEquals(
      YouTubeShortsAction.NavigateBackAndNotify(showFeedback = true),
      sm.onDetectionEvaluated(confirmed, 2_000L),
    )
    assertEquals(YouTubeShortsEnforcementState.COOLDOWN, sm.state)

    // Burst events within cooldown do NOT trigger repeated Back
    assertEquals(YouTubeShortsAction.None, sm.onDetectionEvaluated(confirmed, 2_300L))
    assertEquals(YouTubeShortsAction.None, sm.onDetectionEvaluated(confirmed, 3_500L))

    // Normal YouTube screen after settle resets state cleanly to IDLE
    assertEquals(YouTubeShortsAction.None, sm.onDetectionEvaluated(nonShorts, 2_600L))
    assertEquals(YouTubeShortsEnforcementState.IDLE, sm.state)

    // Re-entering Shorts within feedbackCooldown still navigates back cleanly without spamming toast
    assertEquals(
      YouTubeShortsAction.NavigateBackAndNotify(showFeedback = false),
      sm.onDetectionEvaluated(confirmed, 4_200L),
    )

    // After leaving to another app and later re-entering after feedback cooldown, toast shows again
    sm.onAppLeftYouTube()
    assertEquals(YouTubeShortsEnforcementState.IDLE, sm.state)
    assertEquals(
      YouTubeShortsAction.NavigateBackAndNotify(showFeedback = true),
      sm.onDetectionEvaluated(confirmed, 9_000L),
    )
  }

  @Test
  fun `coordinator triggers back once and feedback once on Shorts while leaving normal YouTube open`() =
    runTest {
      val timeProvider = FakeTimeProvider(initialWallClock = baseTime)
      val feedback = RecordingFeedbackDisplayer()
      val (coordinator, usageRepo, securityRepo) =
        buildCoordinator(listOf(activeCommitment(blockYouTubeShorts = true)), timeProvider, feedback)
      coordinator.onServiceConnected()

      // Normal YouTube Home and normal long-form video never trigger Back
      assertFalse(coordinator.onYouTubeWindowObserved(youtubePkg, homeSnapshot()))
      assertFalse(coordinator.onYouTubeWindowObserved(youtubePkg, normalWatchSnapshot()))
      assertEquals(0, feedback.count)

      // Opening Shorts triggers Back + feedback once
      assertTrue(coordinator.onYouTubeWindowObserved(youtubePkg, shortsSnapshot()))
      assertEquals(1, feedback.count)
      assertEquals(YouTubeShortsEnforcementState.COOLDOWN, coordinator.currentShortsEnforcementState())

      // Repeated content bursts inside cooldown do not re-trigger Back or double-count attempts
      timeProvider.monotonic = MonotonicTimestamp(1_500L)
      assertFalse(coordinator.onYouTubeWindowObserved(youtubePkg, shortsSnapshot()))
      assertEquals(1, feedback.count)

      val daily =
        (usageRepo.getDailyUsage(CommitmentId("c-yt-1"), baseTime.date) as PersistenceResult.Success)
          .data
      assertEquals(1, daily.blockedAttempts)
      // Confirm zero YouTube titles/history/queries were stored in SecurityEvents
      assertTrue(securityRepo.recordedEvents.isEmpty())
    }

  @Test
  fun `disabled setting no active commitment or allowlisted YouTube never enforces Shorts`() =
    runTest {
      val feedback = RecordingFeedbackDisplayer()

      val (disabledCoord, _, _) =
        buildCoordinator(listOf(activeCommitment(blockYouTubeShorts = false)), feedbackDisplayer = feedback)
      disabledCoord.onServiceConnected()
      assertFalse(disabledCoord.shouldInspectYouTubeShortsNow())
      assertFalse(disabledCoord.onYouTubeWindowObserved(youtubePkg, shortsSnapshot()))
      assertEquals(0, feedback.count)

      val (inactiveCoord, _, _) =
        buildCoordinator(emptyList(), feedbackDisplayer = feedback)
      inactiveCoord.onServiceConnected()
      assertFalse(inactiveCoord.shouldInspectYouTubeShortsNow())
      assertFalse(inactiveCoord.onYouTubeWindowObserved(youtubePkg, shortsSnapshot()))
      assertEquals(0, feedback.count)

      val (allowlistedCoord, _, _) =
        buildCoordinator(
          listOf(activeCommitment(blockYouTubeShorts = true, allowlistYouTube = true)),
          feedbackDisplayer = feedback,
        )
      allowlistedCoord.onServiceConnected()
      assertFalse(allowlistedCoord.shouldInspectYouTubeShortsNow())
      assertFalse(allowlistedCoord.onYouTubeWindowObserved(youtubePkg, shortsSnapshot()))
      assertEquals(0, feedback.count)
    }

  @Test
  fun `full app block on YouTube takes precedence over Shorts back action`() = runTest {
    val feedback = RecordingFeedbackDisplayer()
    val (coord, _, _) =
      buildCoordinator(
        listOf(activeCommitment(blockYouTubeShorts = true, restrictWholeYouTube = true)),
        feedbackDisplayer = feedback,
      )
    coord.onServiceConnected()

    assertFalse(coord.onYouTubeWindowObserved(youtubePkg, shortsSnapshot()))
    assertEquals(0, feedback.count)
  }

  @Test
  fun `AndroidYouTubeShortsFeedbackDisplayer displays non-disruptive explanation Toast`() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val displayer = AndroidYouTubeShortsFeedbackDisplayer(context)
    displayer.showShortsRestrictedFeedback()

    val shownText = ShadowToast.getTextOfLatestToast()
    assertNotNull(shownText)
    assertTrue(shownText.contains("YouTube Shorts are restricted by MIZAN"))
  }

  @Suppress("DEPRECATION")
  @Test
  fun `AccessibilityService binds and processes events without crashing when coordinator exists`() =
    runTest {
      val service = Robolectric.buildService(AppRestrictionAccessibilityService::class.java).get()
      val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
      event.packageName = youtubePkg
      // Prior to Hilt injection, handler returns gracefully without crashing.
      service.onAccessibilityEvent(event)
      service.onAccessibilityEvent(null)

      val (coordinator, _, _) =
        buildCoordinator(listOf(activeCommitment(blockYouTubeShorts = true)))
      service.coordinator = coordinator
      service.dispatcherProvider = FakeDispatcherProvider()
      service.onAccessibilityEvent(event)
      service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))

      val unbindResult = service.onUnbind(Intent())
      assertFalse(unbindResult)
    }
}
