package com.example.domain.time

import com.example.domain.evaluator.RestrictionEvaluator
import com.example.domain.evaluator.RestrictionReason
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ScheduleWindow
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTimeProvider(
  var wallClock: WallClockPoint,
  var monotonic: MonotonicTimestamp,
) : TimeProvider {
  override fun currentWallClock(): WallClockPoint = wallClock

  override fun currentMonotonic(): MonotonicTimestamp = monotonic
}

class DomainTimeTypesTest {

  @Test
  fun `domain date computes day of week and leap year rollover accurately`() {
    assertEquals(DomainDayOfWeek.FRIDAY, DomainDate(2026, 11, 13).dayOfWeek())
    assertEquals(DomainDate(2024, 2, 29), DomainDate(2024, 2, 28).plusDays(1))
    assertEquals(DomainDate(2024, 3, 1), DomainDate(2024, 2, 29).plusDays(1))
    assertEquals(DomainDate(2025, 3, 1), DomainDate(2025, 2, 28).plusDays(1))
    assertEquals(DomainDate(2027, 1, 2), DomainDate(2026, 12, 30).plusDays(3))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `invalid leap day on non leap year is rejected`() {
    DomainDate(2025, 2, 29)
  }

  @Test
  fun `wall clock jumps do not corrupt monotonic duration calculations within same boot`() {
    val clock =
      FakeTimeProvider(
        wallClock = WallClockPoint(DomainDate(2026, 11, 13), DomainTime(12, 0), 1_794_571_200_000L),
        monotonic = MonotonicTimestamp(100_000L),
      )

    val startMonotonic = clock.currentMonotonic()
    // Simulate user jumping device wall clock backwards by 2 hours while monotonic advances 15 minutes
    clock.wallClock =
      WallClockPoint(DomainDate(2026, 11, 13), DomainTime(10, 0), 1_794_564_000_000L)
    clock.monotonic = MonotonicTimestamp(100_000L + 15.minutes.inWholeMilliseconds)

    val elapsed = clock.currentMonotonic().elapsedSince(startMonotonic)
    assertEquals(15.minutes, elapsed)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `monotonic timestamp rejects non monotonic subtraction across simulated reboot`() {
    val preReboot = MonotonicTimestamp(500_000L)
    val postReboot = MonotonicTimestamp(12_000L)
    postReboot.elapsedSince(preReboot)
  }

  @Test
  fun `supplied fixed time provider yields deterministic evaluation outcomes`() {
    val fixedNow =
      WallClockPoint(DomainDate(2026, 11, 13), DomainTime(10, 30), 1_794_565_800_000L)
    val provider = FakeTimeProvider(fixedNow, MonotonicTimestamp(5_000L))
    val instagram = AppId("com.instagram.android")
    val rules =
      CommitmentRules(
        mode = RestrictionMode.SCHEDULED,
        restrictedApps = setOf(RestrictedApp(instagram, "Instagram")),
        scheduleWindows =
          listOf(
            ScheduleWindow(
              daysOfWeek = setOf(DomainDayOfWeek.FRIDAY),
              start = DomainTime(9, 0),
              end = DomainTime(12, 0),
            )
          ),
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("det-1"), rules, provider.currentWallClock())
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = provider.currentWallClock())
          as CommitmentResult.Success)
        .commitment

    val first = RestrictionEvaluator.evaluate(active, instagram, provider.currentWallClock())
    val second = RestrictionEvaluator.evaluate(active, instagram, provider.currentWallClock())

    assertEquals(first, second)
    assertTrue(first.isBlocked)
    assertEquals(RestrictionReason.SCHEDULED_WINDOW, first.reason)
  }
}
