package com.example.domain.allowance

import com.example.domain.model.Commitment
import com.example.domain.model.DailyUsage
import com.example.domain.model.RecordedUsageInterval
import com.example.domain.model.RestrictionMode
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageMeasurementSource
import com.example.domain.time.DomainDate
import kotlin.time.Duration

enum class AllowanceRecordOutcome {
  APPLIED,
  RESET_FOR_NEW_DATE_AND_APPLIED,
  DUPLICATE_EVENT_IGNORED,
  NON_FOREGROUND_IGNORED,
  NON_RESTRICTED_APP_IGNORED,
  ALLOWLISTED_APP_IGNORED,
  STRICT_MODE_OVERRIDES_ALLOWANCE,
}

sealed interface AllowanceError {
  data object CommitmentNotActive : AllowanceError

  data object UsageCommitmentMismatch : AllowanceError

  data object OlderDateCannotOverwriteCurrentDay : AllowanceError
}

sealed interface AllowanceRecordResult {
  data class Success(
    val dailyUsage: DailyUsage,
    val outcome: AllowanceRecordOutcome,
  ) : AllowanceRecordResult

  data class Failure(val error: AllowanceError) : AllowanceRecordResult
}

/**
 * Pure coordinator for shared daily allowance accounting.
 *
 * Rules enforced:
 * - Only [Commitment.Active] commitments can accrue usage against their shared pool.
 * - All restricted apps under the commitment share one [DailyUsage] pool per local [DomainDate].
 * - Resets automatically at local midnight when [UsageEvent.localDate] advances past [DailyUsage.date].
 * - Rejects out-of-order older dates relative to [currentUsage.date].
 * - Counts foreground usage only (`isForeground == true`).
 * - Prevents double-counting via [UsageEvent.eventId].
 * - Strict blocking overrides allowance; allowlisted or non-restricted apps do not consume the pool.
 */
object SharedAllowanceAccountant {

  fun usageForDate(
    commitment: Commitment.Active,
    currentUsage: DailyUsage?,
    date: DomainDate,
  ): DailyUsage {
    if (currentUsage == null || currentUsage.commitmentId != commitment.id || currentUsage.date != date) {
      return DailyUsage.empty(commitmentId = commitment.id, date = date)
    }
    return currentUsage
  }

  fun remainingAllowance(
    commitment: Commitment.Active,
    currentUsage: DailyUsage?,
    date: DomainDate,
  ): Duration {
    if (commitment.rules.mode == RestrictionMode.STRICT) {
      return Duration.ZERO
    }
    val effective = usageForDate(commitment, currentUsage, date)
    return effective.remainingAllowance(commitment.rules.dailyAllowance)
  }

  fun recordUsage(
    commitment: Commitment,
    currentUsage: DailyUsage?,
    event: UsageEvent,
  ): AllowanceRecordResult {
    if (commitment !is Commitment.Active) {
      return AllowanceRecordResult.Failure(AllowanceError.CommitmentNotActive)
    }
    if (currentUsage != null && currentUsage.commitmentId != commitment.id) {
      return AllowanceRecordResult.Failure(AllowanceError.UsageCommitmentMismatch)
    }
    if (currentUsage != null && event.localDate < currentUsage.date) {
      return AllowanceRecordResult.Failure(AllowanceError.OlderDateCannotOverwriteCurrentDay)
    }

    val rolledOverForNewDate = currentUsage == null || currentUsage.date != event.localDate
    val baseline =
      if (rolledOverForNewDate) {
        DailyUsage.empty(commitmentId = commitment.id, date = event.localDate)
      } else {
        currentUsage!!
      }

    if (commitment.rules.mode == RestrictionMode.STRICT) {
      return AllowanceRecordResult.Success(
        dailyUsage = baseline,
        outcome = AllowanceRecordOutcome.STRICT_MODE_OVERRIDES_ALLOWANCE,
      )
    }

    if (commitment.rules.isAllowlisted(event.appId)) {
      return AllowanceRecordResult.Success(
        dailyUsage = baseline,
        outcome = AllowanceRecordOutcome.ALLOWLISTED_APP_IGNORED,
      )
    }

    if (!commitment.rules.isRestricted(event.appId)) {
      return AllowanceRecordResult.Success(
        dailyUsage = baseline,
        outcome = AllowanceRecordOutcome.NON_RESTRICTED_APP_IGNORED,
      )
    }

    if (!event.isForeground) {
      return AllowanceRecordResult.Success(
        dailyUsage = baseline,
        outcome = AllowanceRecordOutcome.NON_FOREGROUND_IGNORED,
      )
    }

    if (event.eventId in baseline.recordedEventIds) {
      return AllowanceRecordResult.Success(
        dailyUsage = baseline,
        outcome = AllowanceRecordOutcome.DUPLICATE_EVENT_IGNORED,
      )
    }

    val interval =
      RecordedUsageInterval(
        eventId = event.eventId,
        packageName = event.appId,
        startedAtEpochMillis = event.startedAtEpochMillis,
        endedAtEpochMillis = event.endedAtEpochMillis,
        duration = event.foregroundDuration,
        source = event.source,
      )
    val nextIntervals = baseline.intervals + interval
    val nextCoverage =
      if (baseline.coverageStatus == UsageCoverageStatus.INCOMPLETE) {
        UsageCoverageStatus.INCOMPLETE
      } else {
        val hasA11y = nextIntervals.any { it.source == UsageMeasurementSource.ACCESSIBILITY_INTERVAL }
        val hasReconciled = nextIntervals.any { it.source == UsageMeasurementSource.USAGE_STATS_RECONCILED }
        when {
          hasA11y && hasReconciled -> UsageCoverageStatus.MEASURED_AND_RECONCILED
          !hasA11y && hasReconciled -> UsageCoverageStatus.RECONCILED_ONLY
          else -> UsageCoverageStatus.MEASURED
        }
      }

    val updated =
      baseline.copy(
        consumed = baseline.consumed + event.foregroundDuration,
        recordedEventIds = baseline.recordedEventIds + event.eventId,
        coverageStatus = nextCoverage,
        intervals = nextIntervals,
      )

    return AllowanceRecordResult.Success(
      dailyUsage = updated,
      outcome =
        if (rolledOverForNewDate && currentUsage != null) {
          AllowanceRecordOutcome.RESET_FOR_NEW_DATE_AND_APPLIED
        } else {
          AllowanceRecordOutcome.APPLIED
        },
    )
  }
}
