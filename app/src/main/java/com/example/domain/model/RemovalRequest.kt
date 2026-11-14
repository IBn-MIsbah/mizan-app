package com.example.domain.model

import com.example.domain.time.WallClockPoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@JvmInline
value class RemovalRequestId(val value: String) {
  init {
    require(value.isNotBlank()) { "RemovalRequestId must not be blank" }
  }
}

enum class RemovalRequestStatus {
  PENDING,
  READY_TO_EXECUTE,
  CANCELLED,
  EXECUTED,
}

/**
 * Optional 24-hour voluntary removal cooling-off request.
 *
 * This is a voluntary deterrent only — it never prevents OS uninstall, force-stop, ADB,
 * safe mode, or factory reset, and the user can cancel it at any time.
 */
data class RemovalRequest(
  val id: RemovalRequestId,
  val commitmentId: CommitmentId?,
  val status: RemovalRequestStatus,
  val requestedAt: WallClockPoint,
  val unlocksAt: WallClockPoint,
  val requestedMonotonicMillis: Long,
  val bootSessionToken: String,
  val resolvedAt: WallClockPoint? = null,
  val clockAnomalyObserved: Boolean = false,
) {
  init {
    require(bootSessionToken.isNotBlank()) { "bootSessionToken must not be blank" }
    require(unlocksAt.epochMillis >= requestedAt.epochMillis) {
      "unlocksAt must not precede requestedAt"
    }
  }

  companion object {
    val COOLING_OFF_DURATION: Duration = 24.hours
    val COOLING_OFF_MILLIS: Long = COOLING_OFF_DURATION.inWholeMilliseconds

    fun remainingDuration(remainingMillis: Long): Duration =
      remainingMillis.coerceAtLeast(0L).milliseconds
  }
}
