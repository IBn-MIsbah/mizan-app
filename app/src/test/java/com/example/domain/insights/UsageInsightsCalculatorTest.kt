package com.example.domain.insights

import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageInsightsCalculatorTest {

  private val today = DomainDate(2026, 11, 20)
  private val startWall =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(9, 0, 0),
      epochMillis = 1_794_560_400_000L,
    )

  private fun activeHybrid(id: String = "c-1", allowanceMinutes: Int = 30) =
    (CommitmentStateMachine.activate(
      commitment =
        CommitmentStateMachine.createDraft(
          id = CommitmentId(id),
          rules =
            CommitmentRules(
              mode = RestrictionMode.HYBRID,
              restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Social")),
              dailyAllowance = allowanceMinutes.minutes,
            ),
          now = startWall,
        ),
      agreedToExactRules = true,
      now = startWall,
    ) as CommitmentResult.Success).commitment

  @Test
  fun `empty history produces zero counters and InsufficientData baseline`() {
    val result =
      UsageInsightsCalculator.compute(
        commitments = emptyList(),
        recentUsages = emptyList(),
        todayDate = today,
      )

    assertEquals(Duration.ZERO, result.todayUsage)
    assertEquals(0, result.todayBlockedAttempts)
    assertEquals(0, result.totalBlockedAttemptsRecorded)
    assertNull(result.consecutiveDaysWithinAllowance)
    assertNull(result.weeklyAverageDailyUsage)
    assertEquals(7, result.weeklyTrend.size)
    assertEquals(DomainDate(2026, 11, 14), result.weeklyTrend.first().date)
    assertEquals(today, result.weeklyTrend.last().date)
    assertEquals(
      BaselineComparison.InsufficientData(recordedDaysCount = 0, requiredDaysCount = 6),
      result.baselineComparison,
    )
  }

  @Test
  fun `computes consecutive hybrid streak blocked attempts and baseline comparison when 6+ days recorded`() {
    val active = activeHybrid()
    // Build 6 measured days (Nov 15..Nov 20):
    // First 3 days (prior baseline): 40m, 40m, 40m (avg 40m) -> note Nov 16 is over allowance
    // Recent 3 days (Nov 18, 19, 20): 20m, 15m, 10m (avg 15m, all <= 30m allowance) -> streak = 3
    val usages =
      listOf(
        DailyUsage(active.id, DomainDate(2026, 11, 15), consumed = 40.minutes, blockedAttempts = 1),
        DailyUsage(active.id, DomainDate(2026, 11, 16), consumed = 40.minutes, blockedAttempts = 2),
        DailyUsage(active.id, DomainDate(2026, 11, 17), consumed = 40.minutes, blockedAttempts = 0),
        DailyUsage(active.id, DomainDate(2026, 11, 18), consumed = 20.minutes, blockedAttempts = 1),
        DailyUsage(active.id, DomainDate(2026, 11, 19), consumed = 15.minutes, blockedAttempts = 3),
        DailyUsage(active.id, today, consumed = 10.minutes, blockedAttempts = 4),
      )

    val result =
      UsageInsightsCalculator.compute(
        commitments = listOf(active),
        recentUsages = usages,
        todayDate = today,
      )

    assertEquals(10.minutes, result.todayUsage)
    assertEquals(4, result.todayBlockedAttempts)
    assertEquals(11, result.activeCommitmentBlockedAttempts)
    assertEquals(11, result.totalBlockedAttemptsRecorded)
    assertEquals(3, result.consecutiveDaysWithinAllowance)
    assertEquals(165.minutes, result.weeklyTotalUsage)

    val baseline = result.baselineComparison
    assertTrue(baseline is BaselineComparison.Available)
    baseline as BaselineComparison.Available
    assertEquals(15.minutes, baseline.recentAverageDaily)
    assertEquals(40.minutes, baseline.priorBaselineAverageDaily)
    assertEquals(-25L, baseline.deltaMinutes)
    assertEquals(3, baseline.recentDaysCount)
    assertEquals(3, baseline.baselineDaysCount)
  }
}
