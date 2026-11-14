package com.example.domain.model

import com.example.domain.time.DomainDate
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration

enum class UsageMeasurementSource {
  ACCESSIBILITY_INTERVAL,
  USAGE_STATS_RECONCILED,
}

enum class UsageCoverageStatus {
  MEASURED,
  MEASURED_AND_RECONCILED,
  RECONCILED_ONLY,
  INCOMPLETE,
}

data class RecordedUsageInterval(
  val eventId: UsageEventId,
  val packageName: AppId,
  val startedAtEpochMillis: Long?,
  val endedAtEpochMillis: Long?,
  val duration: Duration,
  val source: UsageMeasurementSource,
) {
  init {
    require(duration.isFinite() && !duration.isNegative()) {
      "RecordedUsageInterval duration must be finite and non-negative, got $duration"
    }
    if (startedAtEpochMillis != null && endedAtEpochMillis != null) {
      require(endedAtEpochMillis >= startedAtEpochMillis) {
        "RecordedUsageInterval end ($endedAtEpochMillis) precedes start ($startedAtEpochMillis)"
      }
    }
  }
}

/**
 * Shared daily usage aggregate belonging strictly to a `(commitmentId, date)` pair.
 *
 * Intentionally contains no per-app allowance buckets so all restricted apps under a commitment
 * share one daily allowance pool.
 */
data class DailyUsage(
  val commitmentId: CommitmentId,
  val date: DomainDate,
  val consumed: Duration = Duration.ZERO,
  val recordedEventIds: Set<UsageEventId> = emptySet(),
  val coverageStatus: UsageCoverageStatus = UsageCoverageStatus.MEASURED,
  val incompleteReason: String? = null,
  val lastReconciledEpochMillis: Long? = null,
  val intervals: List<RecordedUsageInterval> = emptyList(),
  val blockedAttempts: Int = 0,
) {
  init {
    require(consumed.isFinite() && !consumed.isNegative()) {
      "DailyUsage consumed duration must be finite and non-negative, got $consumed"
    }
    require(blockedAttempts >= 0) {
      "DailyUsage blockedAttempts must be non-negative, got $blockedAttempts"
    }
  }

  fun remainingAllowance(dailyAllowance: Duration): Duration {
    require(dailyAllowance.isFinite() && !dailyAllowance.isNegative()) {
      "dailyAllowance must be finite and non-negative, got $dailyAllowance"
    }
    return (dailyAllowance - consumed).coerceAtLeast(Duration.ZERO)
  }

  fun isExhausted(dailyAllowance: Duration): Boolean =
    remainingAllowance(dailyAllowance) == Duration.ZERO

  companion object {
    fun empty(commitmentId: CommitmentId, date: DomainDate): DailyUsage =
      DailyUsage(commitmentId = commitmentId, date = date)
  }
}

/**
 * Already-measured foreground/background usage event input to domain accounting.
 * Domain accounting consumes [foregroundDuration] only when [isForeground] is true.
 */
data class UsageEvent(
  val eventId: UsageEventId,
  val appId: AppId,
  val foregroundDuration: Duration,
  val isForeground: Boolean,
  val localDate: DomainDate,
  val source: UsageMeasurementSource = UsageMeasurementSource.ACCESSIBILITY_INTERVAL,
  val startedAtEpochMillis: Long? = null,
  val endedAtEpochMillis: Long? = null,
  val zoneId: String = "UTC",
  val bootSessionToken: String? = null,
) {
  init {
    require(foregroundDuration.isFinite() && !foregroundDuration.isNegative()) {
      "UsageEvent duration must be finite and non-negative, got $foregroundDuration"
    }
    if (startedAtEpochMillis != null && endedAtEpochMillis != null) {
      require(endedAtEpochMillis >= startedAtEpochMillis) {
        "UsageEvent end ($endedAtEpochMillis) precedes start ($startedAtEpochMillis)"
      }
    }
    require(zoneId.isNotBlank()) { "zoneId must not be blank" }
  }
}

data class PersistedActiveUsageSession(
  val commitmentId: CommitmentId,
  val appId: AppId,
  val startedAtWallClock: WallClockPoint,
  val startedAtMonotonic: MonotonicTimestamp,
  val bootSessionToken: String,
) {
  init {
    require(bootSessionToken.isNotBlank()) { "bootSessionToken must not be blank" }
  }
}

data class UsageIntegrityCheckpoint(
  val lastWallClock: WallClockPoint,
  val lastMonotonic: MonotonicTimestamp,
  val bootSessionToken: String,
) {
  init {
    require(bootSessionToken.isNotBlank()) { "bootSessionToken must not be blank" }
  }
}
