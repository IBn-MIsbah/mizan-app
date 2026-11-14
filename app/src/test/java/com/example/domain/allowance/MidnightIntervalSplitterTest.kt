package com.example.domain.allowance

import com.example.domain.evaluator.RestrictionEvaluator
import com.example.domain.evaluator.RestrictionReason
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DailyUsage
import com.example.domain.model.RecordedUsageInterval
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageEventId
import com.example.domain.model.UsageMeasurementSource
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import java.util.Calendar
import java.util.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidnightIntervalSplitterTest {

  private val utcTimeProvider =
    object : TimeProvider {
      override fun currentWallClock(): WallClockPoint = wallClockAt(0L)

      override fun currentMonotonic(): MonotonicTimestamp = MonotonicTimestamp(0L)

      override fun wallClockAt(epochMillis: Long): WallClockPoint {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        return WallClockPoint(
          date =
            DomainDate(
              cal.get(Calendar.YEAR),
              cal.get(Calendar.MONTH) + 1,
              cal.get(Calendar.DAY_OF_MONTH),
            ),
          time =
            DomainTime(
              cal.get(Calendar.HOUR_OF_DAY),
              cal.get(Calendar.MINUTE),
              cal.get(Calendar.SECOND),
            ),
          epochMillis = epochMillis,
          utcOffsetMinutes = 0,
          zoneId = "UTC",
        )
      }
    }

  private fun dstAwareTimeProvider(zoneId: String): TimeProvider =
    object : TimeProvider {
      private val tz = TimeZone.getTimeZone(zoneId)

      override fun currentWallClock(): WallClockPoint = wallClockAt(0L)

      override fun currentMonotonic(): MonotonicTimestamp = MonotonicTimestamp(0L)

      override fun wallClockAt(epochMillis: Long): WallClockPoint {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = epochMillis
        return WallClockPoint(
          date =
            DomainDate(
              cal.get(Calendar.YEAR),
              cal.get(Calendar.MONTH) + 1,
              cal.get(Calendar.DAY_OF_MONTH),
            ),
          time =
            DomainTime(
              cal.get(Calendar.HOUR_OF_DAY),
              cal.get(Calendar.MINUTE),
              cal.get(Calendar.SECOND),
            ),
          epochMillis = epochMillis,
          utcOffsetMinutes = tz.getOffset(epochMillis) / 60_000,
          zoneId = tz.id,
        )
      }

      override fun nextLocalMidnightEpochMillis(after: WallClockPoint): Long {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = after.epochMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
      }
    }

  @Test
  fun `same day interval emits a single slice`() {
    val startMs = 1_794_560_400_000L // 2026-11-13T09:00:00Z
    val start = utcTimeProvider.wallClockAt(startMs)

    val slices = MidnightIntervalSplitter.splitByLocalMidnight(start, 15.minutes, utcTimeProvider)

    assertEquals(1, slices.size)
    assertEquals(DomainDate(2026, 11, 13), slices.single().localDate)
    assertEquals(15.minutes, slices.single().duration)
    assertEquals(startMs, slices.single().startedAtEpochMillis)
    assertEquals(startMs + 15.minutes.inWholeMilliseconds, slices.single().endedAtEpochMillis)
  }

  @Test
  fun `zero or negative duration returns empty slices`() {
    val start = utcTimeProvider.wallClockAt(1_794_560_400_000L)
    assertTrue(MidnightIntervalSplitter.splitByLocalMidnight(start, Duration.ZERO, utcTimeProvider).isEmpty())
    assertTrue(MidnightIntervalSplitter.splitByLocalMidnight(start, (-5).minutes, utcTimeProvider).isEmpty())
  }

  @Test
  fun `interval crossing single midnight splits accurately into two days`() {
    // 2026-11-13 23:50:00 UTC + 25m -> 10m on 2026-11-13, 15m on 2026-11-14
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
      set(2026, Calendar.NOVEMBER, 13, 23, 50, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val start = utcTimeProvider.wallClockAt(cal.timeInMillis)

    val slices = MidnightIntervalSplitter.splitByLocalMidnight(start, 25.minutes, utcTimeProvider)

    assertEquals(2, slices.size)
    assertEquals(DomainDate(2026, 11, 13), slices[0].localDate)
    assertEquals(10.minutes, slices[0].duration)
    assertEquals(DomainDate(2026, 11, 14), slices[1].localDate)
    assertEquals(15.minutes, slices[1].duration)
    assertEquals(slices[0].endedAtEpochMillis, slices[1].startedAtEpochMillis)
  }

  @Test
  fun `interval spanning multiple midnights splits into all three calendar days`() {
    // 2026-11-13 23:00:00 UTC + 26h -> 1h (Nov 13), 24h (Nov 14), 1h (Nov 15)
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
      set(2026, Calendar.NOVEMBER, 13, 23, 0, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val start = utcTimeProvider.wallClockAt(cal.timeInMillis)

    val slices = MidnightIntervalSplitter.splitByLocalMidnight(start, 26.hours, utcTimeProvider)

    assertEquals(3, slices.size)
    assertEquals(DomainDate(2026, 11, 13), slices[0].localDate)
    assertEquals(1.hours, slices[0].duration)
    assertEquals(DomainDate(2026, 11, 14), slices[1].localDate)
    assertEquals(24.hours, slices[1].duration)
    assertEquals(DomainDate(2026, 11, 15), slices[2].localDate)
    assertEquals(1.hours, slices[2].duration)
  }

  @Test
  fun `dst aware timezone computes 23h spring forward day length at midnight boundary`() {
    // America/New_York spring forward on 2026-03-08 (midnight to midnight is 23h)
    val nyProvider = dstAwareTimeProvider("America/New_York")
    val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
      set(2026, Calendar.MARCH, 7, 23, 30, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val start = nyProvider.wallClockAt(cal.timeInMillis)

    // 24 hours from 2026-03-07 23:30 EST -> 30m (Mar 7) + 23h (Mar 8) + 30m (Mar 9)
    val slices = MidnightIntervalSplitter.splitByLocalMidnight(start, 24.hours, nyProvider)

    assertEquals(3, slices.size)
    assertEquals(DomainDate(2026, 3, 7), slices[0].localDate)
    assertEquals(30.minutes, slices[0].duration)
    assertEquals(DomainDate(2026, 3, 8), slices[1].localDate)
    assertEquals(23.hours, slices[1].duration)
    assertEquals(DomainDate(2026, 3, 9), slices[2].localDate)
    assertEquals(30.minutes, slices[2].duration)
  }

  @Test
  fun `overlap filter subtracts overlapping existing intervals and preserves non-overlapping slices`() {
    val pkg = AppId("com.instagram.android")
    val otherPkg = AppId("com.zhiliaoapp.musically")
    val existing =
      listOf(
        RecordedUsageInterval(UsageEventId("i1"), pkg, 100L, 200L, 100.milliseconds, UsageMeasurementSource.ACCESSIBILITY_INTERVAL),
        RecordedUsageInterval(UsageEventId("i2"), pkg, 300L, 400L, 100.milliseconds, UsageMeasurementSource.USAGE_STATS_RECONCILED),
        // Different package must NOT subtract from candidate pkg
        RecordedUsageInterval(UsageEventId("i3"), otherPkg, 50L, 500L, 450.milliseconds, UsageMeasurementSource.ACCESSIBILITY_INTERVAL),
      )

    val uncovered =
      IntervalOverlapFilter.subtractExisting(
        candidateStartMs = 50L,
        candidateEndMs = 450L,
        packageName = pkg,
        existingIntervals = existing,
      )

    assertEquals(
      listOf(
        EpochInterval(50L, 100L),
        EpochInterval(200L, 300L),
        EpochInterval(400L, 450L),
      ),
      uncovered,
    )

    // Completely covered candidate produces empty list
    assertTrue(
      IntervalOverlapFilter.subtractExisting(110L, 190L, pkg, existing).isEmpty()
    )
  }

  @Test
  fun `hybrid shared pool across two apps transitions evaluator to exhausted`() {
    val appA = AppId("com.instagram.android")
    val appB = AppId("com.reddit.frontpage")
    val now = utcTimeProvider.wallClockAt(1_794_560_400_000L)
    val rules =
      CommitmentRules(
        mode = RestrictionMode.HYBRID,
        restrictedApps = setOf(RestrictedApp(appA, "Instagram"), RestrictedApp(appB, "Reddit")),
        dailyAllowance = 20.minutes,
      )
    val active =
      (CommitmentStateMachine.activate(
          CommitmentStateMachine.createDraft(CommitmentId("c-hyb"), rules, now),
          agreedToExactRules = true,
          now = now,
        ) as CommitmentResult.Success)
        .commitment

    val e1 =
      UsageEvent(
        eventId = UsageEventId("e1"),
        appId = appA,
        foregroundDuration = 12.minutes,
        isForeground = true,
        localDate = now.date,
        source = UsageMeasurementSource.ACCESSIBILITY_INTERVAL,
        startedAtEpochMillis = now.epochMillis,
        endedAtEpochMillis = now.epochMillis + 12.minutes.inWholeMilliseconds,
      )
    val u1 =
      (SharedAllowanceAccountant.recordUsage(active, null, e1) as AllowanceRecordResult.Success)
        .dailyUsage.copy(coverageStatus = UsageCoverageStatus.INCOMPLETE, incompleteReason = "Earlier gap")

    // Under 20m -> allowed
    assertEquals(
      RestrictionReason.WITHIN_DAILY_ALLOWANCE,
      RestrictionEvaluator.evaluate(active, appB, now, u1).reason,
    )

    // Second app consumes remaining 8m -> reaches 20m shared pool AND preserves INCOMPLETE marker
    val e2 =
      UsageEvent(
        eventId = UsageEventId("e2"),
        appId = appB,
        foregroundDuration = 8.minutes,
        isForeground = true,
        localDate = now.date,
        source = UsageMeasurementSource.USAGE_STATS_RECONCILED,
        startedAtEpochMillis = now.epochMillis + 20.minutes.inWholeMilliseconds,
        endedAtEpochMillis = now.epochMillis + 28.minutes.inWholeMilliseconds,
      )
    val u2 =
      (SharedAllowanceAccountant.recordUsage(active, u1, e2) as AllowanceRecordResult.Success)
        .dailyUsage

    assertEquals(20.minutes, u2.consumed)
    assertEquals(2, u2.intervals.size)
    assertEquals(UsageCoverageStatus.INCOMPLETE, u2.coverageStatus)

    val finalDecision = RestrictionEvaluator.evaluate(active, appA, now, u2)
    assertTrue(finalDecision.isBlocked)
    assertEquals(RestrictionReason.DAILY_ALLOWANCE_EXHAUSTED, finalDecision.reason)
  }
}
