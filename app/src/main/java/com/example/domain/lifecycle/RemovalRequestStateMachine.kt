package com.example.domain.lifecycle

import com.example.domain.model.CommitmentId
import com.example.domain.model.RemovalRequest
import com.example.domain.model.RemovalRequestId
import com.example.domain.model.RemovalRequestStatus
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class RemovalEvaluation(
  val updatedRequest: RemovalRequest,
  val isReadyToExecute: Boolean,
  val remainingDuration: Duration,
  val clockAnomalyDetected: Boolean,
)

sealed interface RemovalActionError {
  data object RequestNotOpen : RemovalActionError

  data object DelayNotElapsed : RemovalActionError

  data object ClockAnomalyBlocked : RemovalActionError
}

sealed interface RemovalActionResult {
  data class Success(val request: RemovalRequest) : RemovalActionResult

  data class Failure(val error: RemovalActionError) : RemovalActionResult
}

/**
 * Pure state machine governing the optional 24-hour voluntary removal request.
 */
object RemovalRequestStateMachine {

  const val FORWARD_CLOCK_TOLERANCE_MS: Long = 120_000L

  fun createRequest(
    id: RemovalRequestId,
    commitmentId: CommitmentId?,
    nowWall: WallClockPoint,
    nowMonotonic: MonotonicTimestamp,
    bootSessionToken: String,
  ): RemovalRequest =
    RemovalRequest(
      id = id,
      commitmentId = commitmentId,
      status = RemovalRequestStatus.PENDING,
      requestedAt = nowWall,
      unlocksAt = nowWall.plusElapsedDays(1),
      requestedMonotonicMillis = nowMonotonic.elapsedMillis,
      bootSessionToken = bootSessionToken,
      resolvedAt = null,
      clockAnomalyObserved = false,
    )

  fun evaluate(
    request: RemovalRequest,
    nowWall: WallClockPoint,
    nowMonotonic: MonotonicTimestamp,
    currentBootSessionToken: String,
  ): RemovalEvaluation {
    if (
      request.status == RemovalRequestStatus.CANCELLED ||
        request.status == RemovalRequestStatus.EXECUTED
    ) {
      return RemovalEvaluation(
        updatedRequest = request,
        isReadyToExecute = false,
        remainingDuration = Duration.ZERO,
        clockAnomalyDetected = request.clockAnomalyObserved,
      )
    }

    val wallBackwardAnomaly = nowWall.epochMillis < request.requestedAt.epochMillis
    val sameBoot = currentBootSessionToken == request.bootSessionToken
    val monotonicValid = sameBoot && nowMonotonic.elapsedMillis >= request.requestedMonotonicMillis
    val monoElapsedMs =
      if (monotonicValid) nowMonotonic.elapsedMillis - request.requestedMonotonicMillis else -1L
    val wallElapsedMs = nowWall.epochMillis - request.requestedAt.epochMillis

    val forwardClockJumpAnomaly =
      monotonicValid &&
        wallElapsedMs - monoElapsedMs > FORWARD_CLOCK_TOLERANCE_MS &&
        wallElapsedMs >= RemovalRequest.COOLING_OFF_MILLIS &&
        monoElapsedMs < RemovalRequest.COOLING_OFF_MILLIS

    val anomalyDetected = request.clockAnomalyObserved || wallBackwardAnomaly || forwardClockJumpAnomaly

    val effectiveRemainingMs =
      when {
        wallBackwardAnomaly -> RemovalRequest.COOLING_OFF_MILLIS
        forwardClockJumpAnomaly -> (RemovalRequest.COOLING_OFF_MILLIS - monoElapsedMs).coerceAtLeast(1L)
        nowWall.epochMillis >= request.unlocksAt.epochMillis -> 0L
        else -> (request.unlocksAt.epochMillis - nowWall.epochMillis).coerceAtLeast(0L)
      }

    val ready = effectiveRemainingMs == 0L && !wallBackwardAnomaly && !forwardClockJumpAnomaly
    val nextStatus =
      if (ready) RemovalRequestStatus.READY_TO_EXECUTE else RemovalRequestStatus.PENDING

    return RemovalEvaluation(
      updatedRequest =
        request.copy(
          status = nextStatus,
          clockAnomalyObserved = anomalyDetected,
        ),
      isReadyToExecute = ready,
      remainingDuration = effectiveRemainingMs.milliseconds,
      clockAnomalyDetected = anomalyDetected,
    )
  }

  fun cancel(
    request: RemovalRequest,
    nowWall: WallClockPoint,
  ): RemovalActionResult {
    if (
      request.status != RemovalRequestStatus.PENDING &&
        request.status != RemovalRequestStatus.READY_TO_EXECUTE
    ) {
      return RemovalActionResult.Failure(RemovalActionError.RequestNotOpen)
    }
    return RemovalActionResult.Success(
      request.copy(
        status = RemovalRequestStatus.CANCELLED,
        resolvedAt = nowWall,
      )
    )
  }

  fun execute(
    request: RemovalRequest,
    nowWall: WallClockPoint,
    nowMonotonic: MonotonicTimestamp,
    currentBootSessionToken: String,
  ): RemovalActionResult {
    if (
      request.status != RemovalRequestStatus.PENDING &&
        request.status != RemovalRequestStatus.READY_TO_EXECUTE
    ) {
      return RemovalActionResult.Failure(RemovalActionError.RequestNotOpen)
    }
    val eval = evaluate(request, nowWall, nowMonotonic, currentBootSessionToken)
    if (!eval.isReadyToExecute) {
      val error =
        if (eval.clockAnomalyDetected && nowWall.epochMillis >= request.unlocksAt.epochMillis) {
          RemovalActionError.ClockAnomalyBlocked
        } else {
          RemovalActionError.DelayNotElapsed
        }
      return RemovalActionResult.Failure(error)
    }
    return RemovalActionResult.Success(
      eval.updatedRequest.copy(
        status = RemovalRequestStatus.EXECUTED,
        resolvedAt = nowWall,
      )
    )
  }
}
