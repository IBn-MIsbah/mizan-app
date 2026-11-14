package com.example.domain.lifecycle

import com.example.domain.model.CommitmentId
import com.example.domain.model.RemovalRequest
import com.example.domain.model.RemovalRequestId
import com.example.domain.model.RemovalRequestStatus
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalRequestStateMachineTest {

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(10, 0, 0),
      epochMillis = 1_794_564_000_000L,
    )

  private fun createPending(bootToken: String = "boot:1", monoStartMs: Long = 1_000L) =
    RemovalRequestStateMachine.createRequest(
      id = RemovalRequestId("rem-1"),
      commitmentId = CommitmentId("c-1"),
      nowWall = baseTime,
      nowMonotonic = MonotonicTimestamp(monoStartMs),
      bootSessionToken = bootToken,
    )

  @Test
  fun `cooling-off blocks execution before 24 hours and allows after 24 hours`() {
    val request = createPending()

    val twelveHoursLater =
      WallClockPoint(
        date = DomainDate(2026, 11, 13),
        time = DomainTime(22, 0, 0),
        epochMillis = baseTime.epochMillis + 12.hours.inWholeMilliseconds,
      )
    val earlyEval =
      RemovalRequestStateMachine.evaluate(
        request = request,
        nowWall = twelveHoursLater,
        nowMonotonic = MonotonicTimestamp(1_000L + 12.hours.inWholeMilliseconds),
        currentBootSessionToken = "boot:1",
      )
    assertFalse(earlyEval.isReadyToExecute)
    assertEquals(12.hours, earlyEval.remainingDuration)
    assertEquals(RemovalRequestStatus.PENDING, earlyEval.updatedRequest.status)

    val earlyExecute =
      RemovalRequestStateMachine.execute(
        request = request,
        nowWall = twelveHoursLater,
        nowMonotonic = MonotonicTimestamp(1_000L + 12.hours.inWholeMilliseconds),
        currentBootSessionToken = "boot:1",
      )
    assertEquals(RemovalActionResult.Failure(RemovalActionError.DelayNotElapsed), earlyExecute)

    val twentyFourHoursLater = baseTime.plusElapsedDays(1)
    val readyExecute =
      RemovalRequestStateMachine.execute(
        request = request,
        nowWall = twentyFourHoursLater,
        nowMonotonic = MonotonicTimestamp(1_000L + RemovalRequest.COOLING_OFF_MILLIS),
        currentBootSessionToken = "boot:1",
      )
    assertTrue(readyExecute is RemovalActionResult.Success)
    val executed = (readyExecute as RemovalActionResult.Success).request
    assertEquals(RemovalRequestStatus.EXECUTED, executed.status)
    assertNotNull(executed.resolvedAt)
  }

  @Test
  fun `user can cancel request anytime before execution`() {
    val request = createPending()
    val cancelled =
      RemovalRequestStateMachine.cancel(request, baseTime.copy(epochMillis = baseTime.epochMillis + 60_000L))
    assertTrue(cancelled is RemovalActionResult.Success)
    val resolved = (cancelled as RemovalActionResult.Success).request
    assertEquals(RemovalRequestStatus.CANCELLED, resolved.status)

    val executeAfterCancel =
      RemovalRequestStateMachine.execute(
        request = resolved,
        nowWall = baseTime.plusElapsedDays(2),
        nowMonotonic = MonotonicTimestamp(1_000L + RemovalRequest.COOLING_OFF_MILLIS * 2),
        currentBootSessionToken = "boot:1",
      )
    assertEquals(RemovalActionResult.Failure(RemovalActionError.RequestNotOpen), executeAfterCancel)
  }

  @Test
  fun `same-boot forward wall clock jump and backward clock change are flagged conservatively`() {
    val request = createPending(bootToken = "boot:1", monoStartMs = 10_000L)

    // Same boot: user moved wall clock ahead 25 hours while monotonic elapsed only 5 seconds
    val sameBootJump =
      RemovalRequestStateMachine.execute(
        request = request,
        nowWall = baseTime.plusElapsedDays(2),
        nowMonotonic = MonotonicTimestamp(15_000L),
        currentBootSessionToken = "boot:1",
      )
    assertEquals(RemovalActionResult.Failure(RemovalActionError.ClockAnomalyBlocked), sameBootJump)

    // Backward clock shift prior to request creation
    val backwardWall =
      baseTime.copy(epochMillis = baseTime.epochMillis - 60_000L)
    val backwardEval =
      RemovalRequestStateMachine.evaluate(
        request = request,
        nowWall = backwardWall,
        nowMonotonic = MonotonicTimestamp(20_000L),
        currentBootSessionToken = "boot:1",
      )
    assertTrue(backwardEval.clockAnomalyDetected)
    assertFalse(backwardEval.isReadyToExecute)

    // Cross-reboot after 24h wall clock relies on wall clock conservatively and permits execution
    val crossBootReady =
      RemovalRequestStateMachine.execute(
        request = request,
        nowWall = baseTime.plusElapsedDays(1),
        nowMonotonic = MonotonicTimestamp(5_000L),
        currentBootSessionToken = "boot:2",
      )
    assertTrue(crossBootReady is RemovalActionResult.Success)
  }
}
