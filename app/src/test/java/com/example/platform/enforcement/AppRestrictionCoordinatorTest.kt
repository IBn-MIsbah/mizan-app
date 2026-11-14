package com.example.platform.enforcement

import com.example.domain.evaluator.RestrictionReason
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AllowedApp
import com.example.domain.model.AllowlistCategory
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ScheduleWindow
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.PersistenceError
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import com.example.platform.health.AccessibilityPermissionStatus
import com.example.platform.health.EnforcementHealthStatus
import com.example.testing.FakeBlockingUiLauncher
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRestrictionCoordinatorTest {

  private val ownPackage = "com.ibnmisbah.mizan"
  private val restrictedPkg = "com.example.social"
  private val allowedPkg = "com.example.dialer"
  private val neutralPkg = "com.example.calculator"

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 13), // Friday
      time = DomainTime(22, 30, 0),
      epochMillis = 1_794_609_000_000L,
    )

  private fun createCoordinator(
    commitments: List<Commitment> = emptyList(),
    timeProvider: FakeTimeProvider = FakeTimeProvider(initialWallClock = baseTime),
    usageRepository: FakeDailyUsageRepository = FakeDailyUsageRepository(),
    securityEventRepository: FakeSecurityEventRepository = FakeSecurityEventRepository(),
    healthMonitor: FakeEnforcementHealthMonitor =
      FakeEnforcementHealthMonitor(AccessibilityPermissionStatus.ENABLED),
    blockingUiLauncher: FakeBlockingUiLauncher = FakeBlockingUiLauncher(),
    commitmentRepository: FakeCommitmentRepository = FakeCommitmentRepository(commitments),
  ): CoordinatorHarness {
    val usageTracker =
      com.example.testing.createTestUsageTracker(
        dailyUsageRepository = usageRepository,
        securityEventRepository = securityEventRepository,
        timeProvider = timeProvider,
        ownPackageName = ownPackage,
      )
    val usageReconciler =
      com.example.testing.createTestUsageReconciler(
        dailyUsageRepository = usageRepository,
        timeProvider = timeProvider,
        ownPackageName = ownPackage,
      )
    val coordinator =
      AppRestrictionCoordinator(
        commitmentRepository = commitmentRepository,
        dailyUsageRepository = usageRepository,
        securityEventRepository = securityEventRepository,
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        healthMonitor = healthMonitor,
        blockingUiLauncher = blockingUiLauncher,
        usageTracker = usageTracker,
        usageReconciler = usageReconciler,
        dispatcherProvider = FakeDispatcherProvider(),
        ownPackageName = ownPackage,
      )
    return CoordinatorHarness(
      coordinator = coordinator,
      commitmentRepository = commitmentRepository,
      usageRepository = usageRepository,
      securityEventRepository = securityEventRepository,
      timeProvider = timeProvider,
      healthMonitor = healthMonitor,
      blockingUiLauncher = blockingUiLauncher,
    )
  }

  private fun activeCommitment(
    id: String = "c-1",
    mode: RestrictionMode = RestrictionMode.STRICT,
    scheduleWindows: List<ScheduleWindow> = emptyList(),
    allowanceMinutes: Int = 30,
  ): Commitment.Active {
    val rules =
      CommitmentRules(
        mode = mode,
        restrictedApps = setOf(RestrictedApp(AppId(restrictedPkg), "Sample Social")),
        allowedApps =
          setOf(AllowedApp(AppId(allowedPkg), "Phone", AllowlistCategory.EMERGENCY_PHONE)),
        scheduleWindows = scheduleWindows,
        dailyAllowance = allowanceMinutes.minutes,
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId(id), rules, baseTime)
    return (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseTime)
        as CommitmentResult.Success)
      .commitment
  }

  @Test
  fun `no active commitment and draft commitment never trigger blocking`() = runTest {
    val emptyHarness = createCoordinator()
    assertEquals(
      RestrictionReason.NO_ACTIVE_COMMITMENT,
      emptyHarness.coordinator.onForegroundPackageObserved(restrictedPkg)?.reason,
    )
    assertTrue(emptyHarness.blockingUiLauncher.requests.isEmpty())

    val draft =
      CommitmentStateMachine.createDraft(
        CommitmentId("draft-1"),
        CommitmentRules(
          mode = RestrictionMode.STRICT,
          restrictedApps = setOf(RestrictedApp(AppId(restrictedPkg), "Sample Social")),
        ),
        baseTime,
      )
    val draftHarness = createCoordinator(listOf(draft))
    assertEquals(
      RestrictionReason.NO_ACTIVE_COMMITMENT,
      draftHarness.coordinator.onForegroundPackageObserved(restrictedPkg)?.reason,
    )
    assertTrue(draftHarness.blockingUiLauncher.requests.isEmpty())
  }

  @Test
  fun `strict commitment blocks restricted app once debounces burst and re-blocks after leaving`() =
    runTest {
      val harness = createCoordinator(listOf(activeCommitment(mode = RestrictionMode.STRICT)))
      harness.coordinator.onServiceConnected()

      // First entry -> launches blocking UI
      val d1 = harness.coordinator.onForegroundPackageObserved(restrictedPkg)
      assertTrue(d1!!.isBlocked)
      assertEquals(1, harness.blockingUiLauncher.requests.size)
      assertEquals(restrictedPkg, harness.blockingUiLauncher.requests.single().packageName)

      // MIZAN's own BlockingActivity foregrounded -> ignored, does not loop or clear burst state
      assertNull(harness.coordinator.onForegroundPackageObserved(ownPackage))
      assertEquals(1, harness.blockingUiLauncher.requests.size)

      // Duplicate accessibility burst within debounce window -> debounced
      harness.timeProvider.monotonic = MonotonicTimestamp(1_200L)
      harness.coordinator.onForegroundPackageObserved(restrictedPkg)
      assertEquals(1, harness.blockingUiLauncher.requests.size)

      // User leaves to home launcher / neutral allowed app -> clears block state
      val homeDecision = harness.coordinator.onForegroundPackageObserved(neutralPkg)
      assertTrue(homeDecision!!.isAllowed)
      assertEquals(1, harness.blockingUiLauncher.requests.size)

      // User taps restricted app again even immediately -> blocked again cleanly
      harness.timeProvider.monotonic = MonotonicTimestamp(1_250L)
      val reentryDecision = harness.coordinator.onForegroundPackageObserved(restrictedPkg)
      assertTrue(reentryDecision!!.isBlocked)
      assertEquals(2, harness.blockingUiLauncher.requests.size)
    }

  @Test
  fun `allowlist and null or malformed packages are never blocked`() = runTest {
    val harness = createCoordinator(listOf(activeCommitment(mode = RestrictionMode.STRICT)))

    assertNull(harness.coordinator.onForegroundPackageObserved(null))
    assertNull(harness.coordinator.onForegroundPackageObserved("   "))
    assertNull(harness.coordinator.onForegroundPackageObserved("bad package!"))
    assertEquals(
      RestrictionReason.APP_ALLOWLISTED,
      harness.coordinator.onForegroundPackageObserved(allowedPkg)?.reason,
    )
    assertEquals(
      RestrictionReason.APP_NOT_RESTRICTED,
      harness.coordinator.onForegroundPackageObserved(neutralPkg)?.reason,
    )
    assertTrue(harness.blockingUiLauncher.requests.isEmpty())
  }

  @Test
  fun `scheduled commitment respects overnight midnight crossing window`() = runTest {
    val overnightWindow =
      ScheduleWindow(
        daysOfWeek = setOf(DomainDayOfWeek.FRIDAY),
        start = DomainTime(22, 0),
        end = DomainTime(6, 0),
      )
    val commitment =
      activeCommitment(
        mode = RestrictionMode.SCHEDULED,
        scheduleWindows = listOf(overnightWindow),
      )
    val harness = createCoordinator(listOf(commitment))

    // Friday 22:30 -> blocked
    harness.timeProvider.wallClock = baseTime
    assertTrue(harness.coordinator.onForegroundPackageObserved(restrictedPkg)!!.isBlocked)

    // Saturday 02:00 (still inside Friday overnight window) -> blocked
    harness.timeProvider.wallClock =
      WallClockPoint(DomainDate(2026, 11, 14), DomainTime(2, 0), baseTime.epochMillis + 12_600_000L)
    harness.timeProvider.monotonic = MonotonicTimestamp(5_000L)
    assertTrue(harness.coordinator.onForegroundPackageObserved(restrictedPkg)!!.isBlocked)

    // Saturday 08:00 (outside schedule) -> allowed
    harness.timeProvider.wallClock =
      WallClockPoint(DomainDate(2026, 11, 14), DomainTime(8, 0), baseTime.epochMillis + 34_200_000L)
    val outside = harness.coordinator.onForegroundPackageObserved(restrictedPkg)!!
    assertTrue(outside.isAllowed)
    assertEquals(RestrictionReason.OUTSIDE_SCHEDULE, outside.reason)
  }

  @Test
  fun `hybrid commitment evaluates persisted daily allowance and resets on midnight rollover`() =
    runTest {
      val morning = WallClockPoint(DomainDate(2026, 11, 13), DomainTime(10, 0), 1_794_564_000_000L)
      val commitment =
        activeCommitment(
          mode = RestrictionMode.HYBRID,
          scheduleWindows = emptyList(),
          allowanceMinutes = 30,
        )
      val usageRepo = FakeDailyUsageRepository()
      usageRepo.setUsage(DailyUsage(commitment.id, DomainDate(2026, 11, 13), consumed = 30.minutes))

      val harness =
        createCoordinator(
          commitments = listOf(commitment),
          timeProvider = FakeTimeProvider(initialWallClock = morning),
          usageRepository = usageRepo,
        )

      // Allowance exhausted today -> blocked
      val exhaustedDecision = harness.coordinator.onForegroundPackageObserved(restrictedPkg)!!
      assertEquals(RestrictionReason.DAILY_ALLOWANCE_EXHAUSTED, exhaustedDecision.reason)
      assertEquals(1, harness.blockingUiLauncher.requests.size)

      // Next local day after midnight -> allowance resets cleanly
      harness.timeProvider.wallClock =
        WallClockPoint(DomainDate(2026, 11, 14), DomainTime(0, 5), morning.epochMillis + 50_700_000L)
      harness.timeProvider.monotonic = MonotonicTimestamp(5_000L)
      val nextDayDecision = harness.coordinator.onForegroundPackageObserved(restrictedPkg)!!
      assertTrue(nextDayDecision.isAllowed)
      assertEquals(RestrictionReason.WITHIN_DAILY_ALLOWANCE, nextDayDecision.reason)
      assertEquals(1, harness.blockingUiLauncher.requests.size)
    }

  @Test
  fun `completed or terminated commitment stops blocking`() = runTest {
    val active = activeCommitment(mode = RestrictionMode.STRICT)
    val repo = FakeCommitmentRepository(listOf(active))
    val harness = createCoordinator(commitmentRepository = repo)
    harness.coordinator.onServiceConnected()

    assertTrue(harness.coordinator.onForegroundPackageObserved(restrictedPkg)!!.isBlocked)
    assertEquals(1, harness.blockingUiLauncher.requests.size)

    val terminated =
      (CommitmentStateMachine.emergencyTerminate(
          commitment = active,
          confirmedWarning = true,
          pinVerification = com.example.domain.lifecycle.PinVerificationResult.NOT_PROVIDED,
          now = baseTime,
        ) as CommitmentResult.Success)
        .commitment
    repo.saveCommitment(terminated)

    val afterTermination = harness.coordinator.onForegroundPackageObserved(restrictedPkg)!!
    assertTrue(afterTermination.isAllowed)
    assertEquals(RestrictionReason.NO_ACTIVE_COMMITMENT, afterTermination.reason)
    assertEquals(1, harness.blockingUiLauncher.requests.size)
  }

  @Test
  fun `unavailable rule state fails safely without blocking and recovers when restored`() =
    runTest {
      val active = activeCommitment(mode = RestrictionMode.STRICT)
      val repo = FakeCommitmentRepository(listOf(active))
      val harness = createCoordinator(commitmentRepository = repo)
      harness.coordinator.onServiceConnected()
      assertEquals(EnforcementHealthStatus.ACTIVE, harness.healthMonitor.healthState.value.healthStatus)

      // Inject persistence failure
      repo.observeFailure = PersistenceError.StorageFailure("Simulated DB failure")
      val duringFailure = harness.coordinator.onForegroundPackageObserved(restrictedPkg)
      assertNull(duringFailure)
      assertEquals(
        EnforcementHealthStatus.UNAVAILABLE,
        harness.healthMonitor.healthState.value.healthStatus,
      )
      assertTrue(harness.blockingUiLauncher.requests.isEmpty())

      // Recover persistence
      repo.observeFailure = null
      val recovered = harness.coordinator.onForegroundPackageObserved(restrictedPkg)
      assertTrue(recovered!!.isBlocked)
      assertEquals(EnforcementHealthStatus.ACTIVE, harness.healthMonitor.healthState.value.healthStatus)
      assertEquals(1, harness.blockingUiLauncher.requests.size)
    }

  @Test
  fun `service disconnect and interrupt update health status and record non-sensitive event`() =
    runTest {
      val active = activeCommitment(mode = RestrictionMode.STRICT)
      val harness = createCoordinator(listOf(active))

      harness.coordinator.onServiceConnected()
      assertEquals(EnforcementHealthStatus.ACTIVE, harness.healthMonitor.healthState.value.healthStatus)

      harness.coordinator.onServiceInterrupted()
      assertEquals(
        EnforcementHealthStatus.ENABLED_SERVICE_DISCONNECTED,
        harness.healthMonitor.healthState.value.healthStatus,
      )
      assertTrue(
        harness.securityEventRepository.recordedEvents.any {
          it.eventType == SecurityEventType.SERVICE_INTERRUPTED
        }
      )
      // Verify no restricted package name was leaked into the SecurityEvent summary
      harness.securityEventRepository.recordedEvents.forEach { event ->
        assertFalse(event.summary.orEmpty().contains(restrictedPkg))
      }

      harness.coordinator.onServiceConnected()
      assertEquals(EnforcementHealthStatus.ACTIVE, harness.healthMonitor.healthState.value.healthStatus)

      harness.coordinator.onServiceDisconnectedOrDestroyed()
      assertEquals(
        EnforcementHealthStatus.ENABLED_SERVICE_DISCONNECTED,
        harness.healthMonitor.healthState.value.healthStatus,
      )
    }

  private data class CoordinatorHarness(
    val coordinator: AppRestrictionCoordinator,
    val commitmentRepository: FakeCommitmentRepository,
    val usageRepository: FakeDailyUsageRepository,
    val securityEventRepository: FakeSecurityEventRepository,
    val timeProvider: FakeTimeProvider,
    val healthMonitor: FakeEnforcementHealthMonitor,
    val blockingUiLauncher: FakeBlockingUiLauncher,
  )
}
