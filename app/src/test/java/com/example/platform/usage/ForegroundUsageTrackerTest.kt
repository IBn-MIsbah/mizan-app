package com.example.platform.usage

import com.example.domain.evaluator.RestrictionEvaluator
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AllowedApp
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.PersistedActiveUsageSession
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEventType
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import com.example.platform.enforcement.AppRestrictionCoordinator
import com.example.platform.health.AccessibilityPermissionStatus
import com.example.testing.FakeBlockingUiLauncher
import com.example.testing.FakeBootSessionProvider
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeDeviceInteractiveStateProvider
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import com.example.testing.createTestUsageReconciler
import com.example.testing.createTestUsageTracker
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundUsageTrackerTest {

  private val ownPackage = "com.ibnmisbah.mizan"
  private val appA = "com.instagram.android"
  private val appB = "com.reddit.frontpage"
  private val allowedPkg = "com.android.dialer"
  private val unrelatedPkg = "com.android.calculator2"

  private val baseWall =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(10, 0, 0),
      epochMillis = 1_794_564_000_000L,
    )

  private fun activeCommitment(
    mode: RestrictionMode = RestrictionMode.HYBRID,
    allowanceMinutes: Int = 30,
  ): Commitment.Active {
    val rules =
      CommitmentRules(
        mode = mode,
        restrictedApps = setOf(RestrictedApp(AppId(appA), "Instagram"), RestrictedApp(AppId(appB), "Reddit")),
        allowedApps = setOf(AllowedApp(AppId(allowedPkg), "Phone")),
        dailyAllowance = allowanceMinutes.minutes,
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-track"), rules, baseWall)
    return (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseWall)
        as CommitmentResult.Success)
      .commitment
  }

  @Test
  fun `app switch records non overlapping durations across two restricted apps`() = runTest {
    val timeProvider = FakeTimeProvider(baseWall, MonotonicTimestamp(10_000L))
    val usageRepo = FakeDailyUsageRepository()
    val tracker = createTestUsageTracker(dailyUsageRepository = usageRepo, timeProvider = timeProvider)
    val commitment = activeCommitment()

    // Enter appA at t=10s
    val decA = RestrictionEvaluator.evaluate(commitment, AppId(appA), baseWall)
    tracker.onForegroundTransition(commitment, appA, decA, baseWall, timeProvider.monotonic)

    // Duplicate window event for appA 2m later -> must keep original start without closing or restarting
    timeProvider.monotonic = MonotonicTimestamp(130_000L)
    val wall2 = baseWall.copy(time = DomainTime(10, 2, 0), epochMillis = baseWall.epochMillis + 120_000L)
    tracker.onForegroundTransition(commitment, appA, decA, wall2, timeProvider.monotonic)

    // Switch to appB at +5m -> appA logs 5m, appB starts
    timeProvider.monotonic = MonotonicTimestamp(310_000L)
    val wall5 = baseWall.copy(time = DomainTime(10, 5, 0), epochMillis = baseWall.epochMillis + 300_000L)
    val decB = RestrictionEvaluator.evaluate(commitment, AppId(appB), wall5)
    tracker.onForegroundTransition(commitment, appB, decB, wall5, timeProvider.monotonic)

    // Switch to unrelated neutral app at +8m -> appB logs 3m
    timeProvider.monotonic = MonotonicTimestamp(490_000L)
    val wall8 = baseWall.copy(time = DomainTime(10, 8, 0), epochMillis = baseWall.epochMillis + 480_000L)
    val decNeutral = RestrictionEvaluator.evaluate(commitment, AppId(unrelatedPkg), wall8)
    tracker.onForegroundTransition(commitment, unrelatedPkg, decNeutral, wall8, timeProvider.monotonic)

    val usage =
      (usageRepo.getDailyUsage(commitment.id, baseWall.date) as PersistenceResult.Success).data
    assertEquals(8.minutes, usage.consumed)
    assertEquals(2, usage.intervals.size)
    assertEquals(AppId(appA), usage.intervals[0].packageName)
    assertEquals(AppId(appB), usage.intervals[1].packageName)
    assertTrue(usage.intervals[0].endedAtEpochMillis!! <= usage.intervals[1].startedAtEpochMillis!!)
  }

  @Test
  fun `mizan allowlisted unselected and screen-locked apps are excluded`() = runTest {
    val timeProvider = FakeTimeProvider(baseWall, MonotonicTimestamp(1_000L))
    val usageRepo = FakeDailyUsageRepository()
    val interactiveProvider = FakeDeviceInteractiveStateProvider(interactiveAndUnlocked = false)
    val tracker =
      createTestUsageTracker(
        dailyUsageRepository = usageRepo,
        timeProvider = timeProvider,
        deviceInteractiveStateProvider = interactiveProvider,
      )
    val commitment = activeCommitment()

    // Device locked -> ignored even for restricted app
    tracker.onForegroundTransition(
      commitment,
      appA,
      RestrictionEvaluator.evaluate(commitment, AppId(appA), baseWall),
    )
    assertNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)

    // Unlock and open MIZAN, allowlist, and unrelated app -> none start sessions
    interactiveProvider.interactiveAndUnlocked = true
    tracker.onForegroundTransition(commitment, ownPackage, null)
    assertNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)

    tracker.onForegroundTransition(
      commitment,
      allowedPkg,
      RestrictionEvaluator.evaluate(commitment, AppId(allowedPkg), baseWall),
    )
    assertNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)

    tracker.onForegroundTransition(
      commitment,
      unrelatedPkg,
      RestrictionEvaluator.evaluate(commitment, AppId(unrelatedPkg), baseWall),
    )
    assertNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)

    // Strict mode commitment is always blocked -> never tracked
    val strict = activeCommitment(mode = RestrictionMode.STRICT)
    tracker.onForegroundTransition(
      strict,
      appA,
      RestrictionEvaluator.evaluate(strict, AppId(appA), baseWall),
    )
    assertNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)

    val usage =
      (usageRepo.getDailyUsage(commitment.id, baseWall.date) as PersistenceResult.Success).data
    assertEquals(Duration.ZERO, usage.consumed)
  }

  @Test
  fun `screen off service interrupt and commitment end stop active session accurately`() = runTest {
    val timeProvider = FakeTimeProvider(baseWall, MonotonicTimestamp(10_000L))
    val usageRepo = FakeDailyUsageRepository()
    val tracker = createTestUsageTracker(dailyUsageRepository = usageRepo, timeProvider = timeProvider)
    val commitment = activeCommitment()
    val decA = RestrictionEvaluator.evaluate(commitment, AppId(appA), baseWall)

    tracker.onForegroundTransition(commitment, appA, decA, baseWall, timeProvider.monotonic)
    assertNotNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)

    // Screen off 90s later
    timeProvider.monotonic = MonotonicTimestamp(100_000L)
    tracker.closeActiveSession(SessionEndReason.SCREEN_OFF, baseWall, timeProvider.monotonic)
    assertNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)

    // Re-enter and receive SERVICE_INTERRUPTED 30s later
    timeProvider.monotonic = MonotonicTimestamp(200_000L)
    tracker.onForegroundTransition(commitment, appA, decA, baseWall, timeProvider.monotonic)
    timeProvider.monotonic = MonotonicTimestamp(230_000L)
    tracker.closeActiveSession(SessionEndReason.SERVICE_INTERRUPTED, baseWall, timeProvider.monotonic)

    val usage =
      (usageRepo.getDailyUsage(commitment.id, baseWall.date) as PersistenceResult.Success).data
    assertEquals(120.seconds, usage.consumed)
  }

  @Test
  fun `process death and reboot recovery mark incomplete without fabricating usage`() = runTest {
    val timeProvider = FakeTimeProvider(baseWall, MonotonicTimestamp(50_000L))
    val bootProvider = FakeBootSessionProvider("boot:1")
    val usageRepo = FakeDailyUsageRepository()
    val commitment = activeCommitment()

    // Simulate process death leaving an open active_usage_sessions row from 20m ago
    usageRepo.setPersistedActiveSession(
      PersistedActiveUsageSession(
        commitmentId = commitment.id,
        appId = AppId(appA),
        startedAtWallClock = baseWall,
        startedAtMonotonic = MonotonicTimestamp(10_000L),
        bootSessionToken = "boot:1",
      )
    )

    val tracker =
      createTestUsageTracker(
        dailyUsageRepository = usageRepo,
        timeProvider = timeProvider,
        bootSessionProvider = bootProvider,
      )
    assertEquals(UsageTrackingOutcome.Ok, tracker.recoverUnfinishedSessionIfNeeded())

    assertNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)
    val afterProcessDeath =
      (usageRepo.getDailyUsage(commitment.id, baseWall.date) as PersistenceResult.Success).data
    assertEquals(Duration.ZERO, afterProcessDeath.consumed)
    assertEquals(UsageCoverageStatus.INCOMPLETE, afterProcessDeath.coverageStatus)

    // Reboot scenario: boot token changes & monotonic resets lower
    usageRepo.setPersistedActiveSession(
      PersistedActiveUsageSession(
        commitmentId = commitment.id,
        appId = AppId(appB),
        startedAtWallClock = baseWall,
        startedAtMonotonic = MonotonicTimestamp(900_000L),
        bootSessionToken = "boot:1",
      )
    )
    bootProvider.token = "boot:2"
    timeProvider.monotonic = MonotonicTimestamp(5_000L)
    tracker.recoverUnfinishedSessionIfNeeded()
    val afterReboot =
      (usageRepo.getDailyUsage(commitment.id, baseWall.date) as PersistenceResult.Success).data
    assertEquals(Duration.ZERO, afterReboot.consumed)
    assertTrue(afterReboot.incompleteReason.orEmpty().contains("reboot", ignoreCase = true))
  }

  @Test
  fun `wall clock manipulation logs anomaly without inflating monotonic duration`() = runTest {
    val timeProvider = FakeTimeProvider(baseWall, MonotonicTimestamp(10_000L))
    val usageRepo = FakeDailyUsageRepository()
    val securityRepo = FakeSecurityEventRepository()
    val tracker =
      createTestUsageTracker(
        dailyUsageRepository = usageRepo,
        securityEventRepository = securityRepo,
        timeProvider = timeProvider,
      )
    val commitment = activeCommitment()
    val decA = RestrictionEvaluator.evaluate(commitment, AppId(appA), baseWall)

    tracker.onForegroundTransition(commitment, appA, decA, baseWall, timeProvider.monotonic)

    // Monotonic advances only 2 minutes, while wall clock jumps forward by 5 hours
    timeProvider.monotonic = MonotonicTimestamp(130_000L)
    val jumpedWall =
      baseWall.copy(
        time = DomainTime(15, 0, 0),
        epochMillis = baseWall.epochMillis + 5 * 3_600_000L,
      )
    timeProvider.wallClock = jumpedWall
    tracker.onForegroundTransition(
      commitment,
      unrelatedPkg,
      RestrictionEvaluator.evaluate(commitment, AppId(unrelatedPkg), jumpedWall),
      jumpedWall,
      timeProvider.monotonic,
    )

    val usage =
      (usageRepo.getDailyUsage(commitment.id, baseWall.date) as PersistenceResult.Success).data
    assertEquals(2.minutes, usage.consumed)
    assertTrue(
      securityRepo.recordedEvents.any { it.eventType == SecurityEventType.WALL_CLOCK_ANOMALY_OBSERVED }
    )
    // Verify no package name leaked into SecurityEvent summary
    securityRepo.recordedEvents.forEach { event ->
      assertFalse(event.summary.orEmpty().contains(appA))
    }
  }

  @Test
  fun `hybrid exhaustion timer closes active session and coordinator launches blocking ui`() = runTest {
    val timeProvider = FakeTimeProvider(baseWall, MonotonicTimestamp(1_000L))
    val usageRepo = FakeDailyUsageRepository()
    val securityRepo = FakeSecurityEventRepository()
    val tracker =
      createTestUsageTracker(
        dailyUsageRepository = usageRepo,
        securityEventRepository = securityRepo,
        timeProvider = timeProvider,
        ownPackageName = ownPackage,
      )
    tracker.setTimerScopeForTesting(backgroundScope)

    val commitment = activeCommitment(mode = RestrictionMode.HYBRID, allowanceMinutes = 5)
    val blockingLauncher = FakeBlockingUiLauncher()
    val healthMonitor = FakeEnforcementHealthMonitor(AccessibilityPermissionStatus.ENABLED)
    val coordinator =
      AppRestrictionCoordinator(
        commitmentRepository = FakeCommitmentRepository(listOf(commitment)),
        dailyUsageRepository = usageRepo,
        securityEventRepository = securityRepo,
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        healthMonitor = healthMonitor,
        blockingUiLauncher = blockingLauncher,
        usageTracker = tracker,
        usageReconciler = createTestUsageReconciler(dailyUsageRepository = usageRepo, timeProvider = timeProvider),
        dispatcherProvider = FakeDispatcherProvider(),
        ownPackageName = ownPackage,
      )
    coordinator.onServiceConnected()

    // Enter restricted app with 5 minutes allowance remaining
    val enterDecision = coordinator.onForegroundPackageObserved(appA)
    assertFalse(enterDecision!!.isBlocked)
    assertTrue(blockingLauncher.requests.isEmpty())

    // Advance virtual time + clocks to 5m boundary -> single-shot timer fires cleanly
    val fiveMinutesMs = 5.minutes.inWholeMilliseconds
    timeProvider.monotonic = MonotonicTimestamp(1_000L + fiveMinutesMs)
    timeProvider.wallClock =
      baseWall.copy(time = DomainTime(10, 5, 0), epochMillis = baseWall.epochMillis + fiveMinutesMs)
    advanceTimeBy(fiveMinutesMs)
    runCurrent()

    val usage =
      (usageRepo.getDailyUsage(commitment.id, baseWall.date) as PersistenceResult.Success).data
    assertEquals(5.minutes, usage.consumed)
    assertEquals(1, blockingLauncher.requests.size)
    assertEquals(appA, blockingLauncher.requests.single().packageName)
  }

  @Test
  fun `storage write failure surfaces StorageError rather than silent success`() = runTest {
    val timeProvider = FakeTimeProvider(baseWall, MonotonicTimestamp(1_000L))
    val usageRepo = FakeDailyUsageRepository()
    usageRepo.writeFailure = PersistenceError.StorageFailure("Disk full")
    val tracker = createTestUsageTracker(dailyUsageRepository = usageRepo, timeProvider = timeProvider)
    val commitment = activeCommitment()

    val outcome =
      tracker.onForegroundTransition(
        commitment = commitment,
        packageName = appA,
        decision = RestrictionEvaluator.evaluate(commitment, AppId(appA), baseWall),
      )

    assertEquals(UsageTrackingOutcome.StorageError, outcome)
  }
}
