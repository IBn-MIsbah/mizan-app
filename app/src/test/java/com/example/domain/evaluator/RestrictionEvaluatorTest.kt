package com.example.domain.evaluator

import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.PinVerificationResult
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
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionEvaluatorTest {

  private val instagram = AppId("com.instagram.android")
  private val phone = AppId("com.android.dialer")
  private val notes = AppId("com.example.notes")

  private val fridayNineAm =
    WallClockPoint(
      date = DomainDate(2026, 11, 13), // Friday
      time = DomainTime(9, 0, 0),
      epochMillis = 1_794_560_400_000L,
    )

  private val fridayTwoPm =
    fridayNineAm.copy(
      time = DomainTime(14, 0, 0),
      epochMillis = fridayNineAm.epochMillis + 5L * 3_600_000L,
    )

  private val workHoursWindow =
    ScheduleWindow(
      daysOfWeek = setOf(DomainDayOfWeek.FRIDAY),
      start = DomainTime(9, 0),
      end = DomainTime(12, 0),
    )

  private fun activeCommitment(rules: CommitmentRules): Commitment.Active {
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-1"), rules, fridayNineAm)
    return (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = fridayNineAm)
        as CommitmentResult.Success)
      .commitment
  }

  @Test
  fun `inactive null draft completed and terminated commitments do not enforce restrictions`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-1"), rules, fridayNineAm)
    val active = activeCommitment(rules)
    val completed =
      (CommitmentStateMachine.completeIfReachedEnd(active, active.endsAt) as CommitmentResult.Success)
        .commitment
    val terminated =
      (CommitmentStateMachine.emergencyTerminate(
          active,
          confirmedWarning = true,
          pinVerification = PinVerificationResult.NOT_PROVIDED,
          now = fridayTwoPm,
        ) as CommitmentResult.Success)
        .commitment

    listOf(null, draft, completed, terminated).forEach { commitment ->
      val decision = RestrictionEvaluator.evaluate(commitment, instagram, fridayNineAm)
      assertFalse(decision.isBlocked)
      assertEquals(RestrictionReason.NO_ACTIVE_COMMITMENT, decision.reason)
    }
  }

  @Test
  fun `active commitment past end boundary allows with commitment period elapsed`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
      )
    val active = activeCommitment(rules)

    val decision = RestrictionEvaluator.evaluate(active, instagram, active.endsAt)

    assertTrue(decision.isAllowed)
    assertEquals(RestrictionReason.COMMITMENT_PERIOD_ELAPSED, decision.reason)
  }

  @Test
  fun `strict mode blocks restricted app regardless of remaining allowance`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
        dailyAllowance = 30.minutes,
      )
    val active = activeCommitment(rules)
    val zeroUsage = DailyUsage.empty(active.id, fridayNineAm.date)

    val decision = RestrictionEvaluator.evaluate(active, instagram, fridayNineAm, zeroUsage)

    assertTrue(decision.isBlocked)
    assertEquals(RestrictionReason.STRICT_MODE, decision.reason)
  }

  @Test
  fun `scheduled mode blocks inside window and allows outside window`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.SCHEDULED,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
        scheduleWindows = listOf(workHoursWindow),
      )
    val active = activeCommitment(rules)

    val inside = RestrictionEvaluator.evaluate(active, instagram, fridayNineAm)
    assertTrue(inside.isBlocked)
    assertEquals(RestrictionReason.SCHEDULED_WINDOW, inside.reason)

    val outside = RestrictionEvaluator.evaluate(active, instagram, fridayTwoPm)
    assertTrue(outside.isAllowed)
    assertEquals(RestrictionReason.OUTSIDE_SCHEDULE, outside.reason)
  }

  @Test
  fun `hybrid mode blocks inside schedule even with remaining allowance`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.HYBRID,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
        scheduleWindows = listOf(workHoursWindow),
        dailyAllowance = 30.minutes,
      )
    val active = activeCommitment(rules)
    val freshUsage = DailyUsage.empty(active.id, fridayNineAm.date)

    val decision = RestrictionEvaluator.evaluate(active, instagram, fridayNineAm, freshUsage)

    assertTrue(decision.isBlocked)
    assertEquals(RestrictionReason.SCHEDULED_WINDOW, decision.reason)
  }

  @Test
  fun `hybrid mode allows outside schedule while allowance remains and blocks when exhausted`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.HYBRID,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
        scheduleWindows = listOf(workHoursWindow),
        dailyAllowance = 30.minutes,
      )
    val active = activeCommitment(rules)

    val partialUsage =
      DailyUsage(commitmentId = active.id, date = fridayTwoPm.date, consumed = 20.minutes)
    val allowed = RestrictionEvaluator.evaluate(active, instagram, fridayTwoPm, partialUsage)
    assertTrue(allowed.isAllowed)
    assertEquals(RestrictionReason.WITHIN_DAILY_ALLOWANCE, allowed.reason)

    val exhaustedUsage =
      DailyUsage(commitmentId = active.id, date = fridayTwoPm.date, consumed = 30.minutes)
    val blocked = RestrictionEvaluator.evaluate(active, instagram, fridayTwoPm, exhaustedUsage)
    assertTrue(blocked.isBlocked)
    assertEquals(RestrictionReason.DAILY_ALLOWANCE_EXHAUSTED, blocked.reason)
  }

  @Test
  fun `hybrid mode resets exhausted usage when wall clock advances to next local date`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.HYBRID,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
        dailyAllowance = 30.minutes,
      )
    val active = activeCommitment(rules)
    val yesterdayExhausted =
      DailyUsage(commitmentId = active.id, date = fridayTwoPm.date, consumed = 30.minutes)
    val saturday = fridayTwoPm.plusElapsedDays(1)

    val decision = RestrictionEvaluator.evaluate(active, instagram, saturday, yesterdayExhausted)

    assertTrue(decision.isAllowed)
    assertEquals(RestrictionReason.WITHIN_DAILY_ALLOWANCE, decision.reason)
  }

  @Test
  fun `allowlist takes precedence even if app is also marked restricted in strict mode`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = setOf(RestrictedApp(phone, "Phone")),
        allowedApps = setOf(AllowedApp(phone, "Phone", AllowlistCategory.EMERGENCY_PHONE)),
      )
    val active = activeCommitment(rules)

    val allowlistedDecision = RestrictionEvaluator.evaluate(active, phone, fridayNineAm)
    assertTrue(allowlistedDecision.isAllowed)
    assertEquals(RestrictionReason.APP_ALLOWLISTED, allowlistedDecision.reason)

    val unrestrictedDecision = RestrictionEvaluator.evaluate(active, notes, fridayNineAm)
    assertTrue(unrestrictedDecision.isAllowed)
    assertEquals(RestrictionReason.APP_NOT_RESTRICTED, unrestrictedDecision.reason)
  }
}
