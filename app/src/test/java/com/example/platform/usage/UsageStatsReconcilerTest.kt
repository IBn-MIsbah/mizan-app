package com.example.platform.usage

import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AllowedApp
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageEventId
import com.example.domain.model.UsageMeasurementSource
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import com.example.platform.health.UsageAccessPermissionStatus
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUsageAccessMonitor
import com.example.testing.FakeUsageStatsHistorySource
import com.example.testing.createTestUsageReconciler
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsReconcilerTest {

  private val ownPkg = "com.ibnmisbah.mizan"
  private val igPkg = "com.instagram.android"
  private val tiktokPkg = "com.zhiliaoapp.musically"
  private val dialerPkg = "com.android.dialer"

  private val startEpochMs = 1_794_556_800_000L // 2026-11-13T08:00:00Z
  private val activatedWall =
    WallClockPoint(DomainDate(2026, 11, 13), DomainTime(8, 0, 0), startEpochMs)

  private fun activeCommitment(mode: RestrictionMode = RestrictionMode.HYBRID): Commitment.Active {
    val rules =
      CommitmentRules(
        mode = mode,
        restrictedApps =
          setOf(
            RestrictedApp(AppId(igPkg), "Instagram"),
            RestrictedApp(AppId(tiktokPkg), "TikTok"),
          ),
        allowedApps = setOf(AllowedApp(AppId(dialerPkg), "Phone")),
        dailyAllowance = 30.minutes,
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-rec"), rules, activatedWall)
    return (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = activatedWall)
        as CommitmentResult.Success)
      .commitment
  }

  @Test
  fun `returns PermissionDisabled or Unavailable when Usage Access is missing`() = runTest {
    val monitor = FakeUsageAccessMonitor(UsageAccessPermissionStatus.DISABLED)
    val reconciler = createTestUsageReconciler(usageAccessMonitor = monitor)
    val active = activeCommitment()

    assertEquals(ReconcileResult.PermissionDisabled, reconciler.reconcileActiveCommitment(active))

    monitor.systemStatus = UsageAccessPermissionStatus.UNAVAILABLE
    assertEquals(ReconcileResult.PermissionUnavailable, reconciler.reconcileActiveCommitment(active))
  }

  @Test
  fun `ignores non-active or strict commitments cleanly`() = runTest {
    val monitor = FakeUsageAccessMonitor(UsageAccessPermissionStatus.ENABLED)
    val reconciler = createTestUsageReconciler(usageAccessMonitor = monitor)

    assertEquals(ReconcileResult.NoActiveCommitment, reconciler.reconcileActiveCommitment(null))

    val strictResult =
      reconciler.reconcileActiveCommitment(activeCommitment(mode = RestrictionMode.STRICT))
    assertEquals(
      ReconcileResult.Reconciled(addedIntervals = 0, addedDuration = Duration.ZERO, partialCoverage = false),
      strictResult,
    )
  }

  @Test
  fun `defensively handles orphan pauses duplicate resumes dangling opens and screen off`() = runTest {
    val nowWall =
      WallClockPoint(DomainDate(2026, 11, 13), DomainTime(10, 0, 0), startEpochMs + 2.hours.inWholeMilliseconds)
    val timeProvider = FakeTimeProvider(nowWall)
    val monitor = FakeUsageAccessMonitor(UsageAccessPermissionStatus.ENABLED)
    val historySource =
      FakeUsageStatsHistorySource(
        events =
          listOf(
            // Orphan pause before any foreground event -> ignored, marks partialCoverage
            OsUsageEvent(igPkg, OsUsageEventType.BACKGROUND, startEpochMs + 1.minutes.inWholeMilliseconds),
            // Duplicate resume on igPkg: [10m..15m] closed by switch to MIZAN
            OsUsageEvent(igPkg, OsUsageEventType.FOREGROUND, startEpochMs + 10.minutes.inWholeMilliseconds),
            OsUsageEvent(igPkg, OsUsageEventType.FOREGROUND, startEpochMs + 12.minutes.inWholeMilliseconds),
            OsUsageEvent(ownPkg, OsUsageEventType.FOREGROUND, startEpochMs + 15.minutes.inWholeMilliseconds),
            // tiktokPkg closed by SCREEN_OFF: [20m..24m]
            OsUsageEvent(tiktokPkg, OsUsageEventType.FOREGROUND, startEpochMs + 20.minutes.inWholeMilliseconds),
            OsUsageEvent(tiktokPkg, OsUsageEventType.SCREEN_OFF, startEpochMs + 24.minutes.inWholeMilliseconds),
            // Allowlisted dialer is NOT tracked
            OsUsageEvent(dialerPkg, OsUsageEventType.FOREGROUND, startEpochMs + 30.minutes.inWholeMilliseconds),
            OsUsageEvent(dialerPkg, OsUsageEventType.BACKGROUND, startEpochMs + 40.minutes.inWholeMilliseconds),
            // Dangling unclosed igPkg open at 50m -> NOT fabricated up to now, marks partialCoverage
            OsUsageEvent(igPkg, OsUsageEventType.FOREGROUND, startEpochMs + 50.minutes.inWholeMilliseconds),
          )
      )
    val usageRepo = FakeDailyUsageRepository()
    val reconciler =
      createTestUsageReconciler(
        usageAccessMonitor = monitor,
        historySource = historySource,
        dailyUsageRepository = usageRepo,
        timeProvider = timeProvider,
        ownPackageName = ownPkg,
      )

    val result = reconciler.reconcileActiveCommitment(activeCommitment()) as ReconcileResult.Reconciled
    assertEquals(2, result.addedIntervals)
    assertEquals(9.minutes, result.addedDuration)
    assertTrue(result.partialCoverage)

    val stored =
      (usageRepo.getDailyUsage(CommitmentId("c-rec"), nowWall.date) as PersistenceResult.Success).data
    assertEquals(9.minutes, stored.consumed)
    assertEquals(UsageCoverageStatus.INCOMPLETE, stored.coverageStatus)

    // Running reconciliation a second time with same history produces 0 additional duration
    val secondRun = reconciler.reconcileActiveCommitment(activeCommitment()) as ReconcileResult.Reconciled
    assertEquals(0, secondRun.addedIntervals)
    assertEquals(Duration.ZERO, secondRun.addedDuration)
  }

  @Test
  fun `subtracts existing accessibility intervals without double counting or overwriting`() = runTest {
    val nowWall =
      WallClockPoint(DomainDate(2026, 11, 13), DomainTime(10, 0, 0), startEpochMs + 2.hours.inWholeMilliseconds)
    val timeProvider = FakeTimeProvider(nowWall)
    val monitor = FakeUsageAccessMonitor(UsageAccessPermissionStatus.ENABLED)
    val usageRepo = FakeDailyUsageRepository()
    val active = activeCommitment()

    // Accessibility already recorded [10m..16m] = 6 minutes
    usageRepo.recordUsageEvent(
      active,
      UsageEvent(
        eventId = UsageEventId("a11y-1"),
        appId = AppId(igPkg),
        foregroundDuration = 6.minutes,
        isForeground = true,
        localDate = nowWall.date,
        source = UsageMeasurementSource.ACCESSIBILITY_INTERVAL,
        startedAtEpochMillis = startEpochMs + 10.minutes.inWholeMilliseconds,
        endedAtEpochMillis = startEpochMs + 16.minutes.inWholeMilliseconds,
      ),
    )

    // OS UsageStats reports [8m..18m] = 10 minutes overlapping the 6-minute measured window
    val historySource =
      FakeUsageStatsHistorySource(
        events =
          listOf(
            OsUsageEvent(igPkg, OsUsageEventType.FOREGROUND, startEpochMs + 8.minutes.inWholeMilliseconds),
            OsUsageEvent(igPkg, OsUsageEventType.BACKGROUND, startEpochMs + 18.minutes.inWholeMilliseconds),
          )
      )
    val reconciler =
      createTestUsageReconciler(
        usageAccessMonitor = monitor,
        historySource = historySource,
        dailyUsageRepository = usageRepo,
        timeProvider = timeProvider,
      )

    val result = reconciler.reconcileActiveCommitment(active) as ReconcileResult.Reconciled
    // Only uncovered edges [8m..10m] (2m) and [16m..18m] (2m) are filled = 4m added!
    assertEquals(2, result.addedIntervals)
    assertEquals(4.minutes, result.addedDuration)
    assertFalse(result.partialCoverage)

    val total =
      (usageRepo.getDailyUsage(active.id, nowWall.date) as PersistenceResult.Success).data
    assertEquals(10.minutes, total.consumed)
    assertEquals(3, total.intervals.size)
    // Original higher-confidence Accessibility interval remains intact
    assertTrue(
      total.intervals.any {
        it.source == UsageMeasurementSource.ACCESSIBILITY_INTERVAL &&
          it.startedAtEpochMillis == startEpochMs + 10.minutes.inWholeMilliseconds &&
          it.endedAtEpochMillis == startEpochMs + 16.minutes.inWholeMilliseconds
      }
    )
  }

  @Test
  fun `lookback clipping marks partial coverage and midnight spanning OS interval splits across days`() = runTest {
    val active = activeCommitment()
    // 2026-11-16 09:00 UTC with lookback=2 days -> clipped because commitment activated on Nov 13
    val dayFourEpochMs = startEpochMs + 3L * WallClockPoint.MILLIS_PER_DAY + 3_600_000L
    val nowWall = WallClockPoint(DomainDate(2026, 11, 16), DomainTime(9, 0, 0), dayFourEpochMs)
    val timeProvider = FakeTimeProvider(nowWall)
    val monitor = FakeUsageAccessMonitor(UsageAccessPermissionStatus.ENABLED)

    // Midnight between Nov 15 and Nov 16 UTC = 1_794_787_200_000L
    val nov16MidnightMs = 1_794_787_200_000L
    val historySource =
      FakeUsageStatsHistorySource(
        events =
          listOf(
            OsUsageEvent(igPkg, OsUsageEventType.FOREGROUND, nov16MidnightMs - 5.minutes.inWholeMilliseconds),
            OsUsageEvent(igPkg, OsUsageEventType.BACKGROUND, nov16MidnightMs + 10.minutes.inWholeMilliseconds),
          )
      )
    val usageRepo = FakeDailyUsageRepository()
    val reconciler =
      createTestUsageReconciler(
        usageAccessMonitor = monitor,
        historySource = historySource,
        dailyUsageRepository = usageRepo,
        timeProvider = timeProvider,
      )

    val result =
      reconciler.reconcileActiveCommitment(active, lookbackDays = 2) as ReconcileResult.Reconciled
    assertEquals(2, result.addedIntervals)
    assertEquals(15.minutes, result.addedDuration)
    assertTrue(result.partialCoverage)

    val nov15 =
      (usageRepo.getDailyUsage(active.id, DomainDate(2026, 11, 15)) as PersistenceResult.Success).data
    val nov16 =
      (usageRepo.getDailyUsage(active.id, DomainDate(2026, 11, 16)) as PersistenceResult.Success).data
    assertEquals(5.minutes, nov15.consumed)
    assertEquals(10.minutes, nov16.consumed)
    assertEquals(UsageCoverageStatus.INCOMPLETE, nov16.coverageStatus)
  }
}
