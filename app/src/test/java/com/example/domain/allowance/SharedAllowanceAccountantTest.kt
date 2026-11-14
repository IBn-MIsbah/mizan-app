package com.example.domain.allowance

import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AllowedApp
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageEventId
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedAllowanceAccountantTest {

  private val instagram = AppId("com.instagram.android")
  private val tiktok = AppId("com.zhiliaoapp.musically")
  private val maps = AppId("com.google.android.apps.maps")

  private val dayOne = DomainDate(2026, 11, 13)
  private val dayTwo = DomainDate(2026, 11, 14)

  private val startPoint =
    WallClockPoint(date = dayOne, time = DomainTime(8, 0), epochMillis = 1_794_556_800_000L)

  private fun activeHybridCommitment(
    dailyAllowance: Duration = CommitmentRules.DEFAULT_DAILY_ALLOWANCE,
  ): Commitment.Active {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.HYBRID,
        restrictedApps =
          setOf(
            RestrictedApp(instagram, "Instagram"),
            RestrictedApp(tiktok, "TikTok"),
          ),
        allowedApps = setOf(AllowedApp(maps, "Maps")),
        dailyAllowance = dailyAllowance,
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-1"), rules, startPoint)
    return (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = startPoint)
        as CommitmentResult.Success)
      .commitment
  }

  @Test
  fun `default daily allowance is 30 minutes`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.HYBRID,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
      )
    assertEquals(30.minutes, rules.dailyAllowance)
  }

  @Test
  fun `usage from different restricted apps consumes same shared daily pool`() {
    val commitment = activeHybridCommitment()

    val r1 =
      SharedAllowanceAccountant.recordUsage(
        commitment = commitment,
        currentUsage = null,
        event = UsageEvent(UsageEventId("e1"), instagram, 12.minutes, isForeground = true, dayOne),
      ) as AllowanceRecordResult.Success

    val r2 =
      SharedAllowanceAccountant.recordUsage(
        commitment = commitment,
        currentUsage = r1.dailyUsage,
        event = UsageEvent(UsageEventId("e2"), tiktok, 18.minutes, isForeground = true, dayOne),
      ) as AllowanceRecordResult.Success

    assertEquals(30.minutes, r2.dailyUsage.consumed)
    assertEquals(Duration.ZERO, SharedAllowanceAccountant.remainingAllowance(commitment, r2.dailyUsage, dayOne))
    assertTrue(r2.dailyUsage.isExhausted(commitment.rules.dailyAllowance))
  }

  @Test
  fun `duplicate usage event id is ignored and not counted twice`() {
    val commitment = activeHybridCommitment()
    val event = UsageEvent(UsageEventId("dup-1"), instagram, 10.minutes, isForeground = true, dayOne)

    val first =
      (SharedAllowanceAccountant.recordUsage(commitment, null, event) as AllowanceRecordResult.Success)
    val second =
      (SharedAllowanceAccountant.recordUsage(commitment, first.dailyUsage, event)
        as AllowanceRecordResult.Success)

    assertEquals(AllowanceRecordOutcome.APPLIED, first.outcome)
    assertEquals(AllowanceRecordOutcome.DUPLICATE_EVENT_IGNORED, second.outcome)
    assertEquals(10.minutes, second.dailyUsage.consumed)
  }

  @Test
  fun `non foreground usage does not consume daily allowance`() {
    val commitment = activeHybridCommitment()
    val backgroundEvent =
      UsageEvent(UsageEventId("bg-1"), instagram, 20.minutes, isForeground = false, dayOne)

    val result =
      SharedAllowanceAccountant.recordUsage(commitment, null, backgroundEvent)
        as AllowanceRecordResult.Success

    assertEquals(AllowanceRecordOutcome.NON_FOREGROUND_IGNORED, result.outcome)
    assertEquals(Duration.ZERO, result.dailyUsage.consumed)
    assertEquals(30.minutes, SharedAllowanceAccountant.remainingAllowance(commitment, result.dailyUsage, dayOne))
  }

  @Test
  fun `local midnight reset applies when recording or querying on next calendar date`() {
    val commitment = activeHybridCommitment()
    val dayOneUsage = DailyUsage(commitment.id, dayOne, consumed = 30.minutes)

    assertEquals(
      30.minutes,
      SharedAllowanceAccountant.remainingAllowance(commitment, dayOneUsage, dayTwo),
    )

    val dayTwoResult =
      SharedAllowanceAccountant.recordUsage(
        commitment = commitment,
        currentUsage = dayOneUsage,
        event = UsageEvent(UsageEventId("d2-1"), tiktok, 7.minutes, isForeground = true, dayTwo),
      ) as AllowanceRecordResult.Success

    assertEquals(AllowanceRecordOutcome.RESET_FOR_NEW_DATE_AND_APPLIED, dayTwoResult.outcome)
    assertEquals(dayTwo, dayTwoResult.dailyUsage.date)
    assertEquals(7.minutes, dayTwoResult.dailyUsage.consumed)
    assertEquals(23.minutes, SharedAllowanceAccountant.remainingAllowance(commitment, dayTwoResult.dailyUsage, dayTwo))
  }

  @Test
  fun `out of order older date event is rejected`() {
    val commitment = activeHybridCommitment()
    val dayTwoUsage = DailyUsage(commitment.id, dayTwo, consumed = 5.minutes)

    val result =
      SharedAllowanceAccountant.recordUsage(
        commitment = commitment,
        currentUsage = dayTwoUsage,
        event = UsageEvent(UsageEventId("old-1"), instagram, 5.minutes, isForeground = true, dayOne),
      )

    assertEquals(
      AllowanceRecordResult.Failure(AllowanceError.OlderDateCannotOverwriteCurrentDay),
      result,
    )
  }

  @Test
  fun `strict mode overrides allowance`() {
    val strictRules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
        dailyAllowance = 30.minutes,
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-strict"), strictRules, startPoint)
    val strictCommitment =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = startPoint)
          as CommitmentResult.Success)
        .commitment

    assertEquals(
      Duration.ZERO,
      SharedAllowanceAccountant.remainingAllowance(strictCommitment, null, dayOne),
    )
    val result =
      SharedAllowanceAccountant.recordUsage(
        commitment = strictCommitment,
        currentUsage = null,
        event = UsageEvent(UsageEventId("s-1"), instagram, 5.minutes, isForeground = true, dayOne),
      ) as AllowanceRecordResult.Success
    assertEquals(AllowanceRecordOutcome.STRICT_MODE_OVERRIDES_ALLOWANCE, result.outcome)
    assertEquals(Duration.ZERO, result.dailyUsage.consumed)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `negative usage duration is rejected`() {
    UsageEvent(UsageEventId("neg"), instagram, (-1).minutes, isForeground = true, dayOne)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `negative daily allowance is rejected`() {
    CommitmentRules(
      mode = RestrictionMode.HYBRID,
      restrictedApps = emptySet(),
      dailyAllowance = (-5).minutes,
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `daily allowance exceeding 24 hours is rejected`() {
    CommitmentRules(
      mode = RestrictionMode.HYBRID,
      restrictedApps = emptySet(),
      dailyAllowance = 25.hours,
    )
  }
}
