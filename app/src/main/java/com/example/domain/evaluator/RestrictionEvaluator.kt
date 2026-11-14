package com.example.domain.evaluator

import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictionMode
import com.example.domain.time.WallClockPoint

/**
 * Central, pure domain evaluator for app restriction checks.
 *
 * Evaluation precedence:
 * 1. Inactive commitments (null, Draft, Completed, EmergencyTerminated) -> Allowed (no active commitment).
 * 2. Active commitments past their 7-day end boundary -> Allowed (commitment period elapsed).
 * 3. Explicitly allowlisted apps -> Allowed (allowlist takes precedence over restrictions).
 * 4. Apps not in [CommitmentRules.restrictedApps] -> Allowed (app not restricted).
 * 5. Mode-specific evaluation:
 *    - [RestrictionMode.STRICT]: Blocked by strict mode (overrides any allowance).
 *    - [RestrictionMode.SCHEDULED]: Blocked inside `[start, end)`, allowed outside schedule.
 *    - [RestrictionMode.HYBRID]: Blocked during any active schedule window; outside schedule windows,
 *      allowed while shared daily allowance remains for [wallClock.date], and blocked once exhausted.
 *      If [dailyUsage] belongs to a prior calendar date, local-midnight reset applies (0 consumed).
 */
object RestrictionEvaluator {

  fun evaluate(
    commitment: Commitment?,
    appId: AppId,
    wallClock: WallClockPoint,
    dailyUsage: DailyUsage? = null,
  ): RestrictionDecision {
    if (commitment !is Commitment.Active) {
      return RestrictionDecision.allow(RestrictionReason.NO_ACTIVE_COMMITMENT)
    }

    if (commitment.hasReachedEnd(wallClock)) {
      return RestrictionDecision.allow(RestrictionReason.COMMITMENT_PERIOD_ELAPSED)
    }

    val rules = commitment.rules
    if (rules.isAllowlisted(appId)) {
      return RestrictionDecision.allow(RestrictionReason.APP_ALLOWLISTED)
    }

    if (!rules.isRestricted(appId)) {
      return RestrictionDecision.allow(RestrictionReason.APP_NOT_RESTRICTED)
    }

    return when (rules.mode) {
      RestrictionMode.STRICT -> RestrictionDecision.block(RestrictionReason.STRICT_MODE)

      RestrictionMode.SCHEDULED -> {
        if (rules.isWithinScheduledWindow(wallClock.date, wallClock.time)) {
          RestrictionDecision.block(RestrictionReason.SCHEDULED_WINDOW)
        } else {
          RestrictionDecision.allow(RestrictionReason.OUTSIDE_SCHEDULE)
        }
      }

      RestrictionMode.HYBRID -> {
        if (rules.isWithinScheduledWindow(wallClock.date, wallClock.time)) {
          RestrictionDecision.block(RestrictionReason.SCHEDULED_WINDOW)
        } else {
          val effectiveUsage = effectiveUsageForDate(commitment, dailyUsage, wallClock)
          if (effectiveUsage.isExhausted(rules.dailyAllowance)) {
            RestrictionDecision.block(RestrictionReason.DAILY_ALLOWANCE_EXHAUSTED)
          } else {
            RestrictionDecision.allow(RestrictionReason.WITHIN_DAILY_ALLOWANCE)
          }
        }
      }
    }
  }

  private fun effectiveUsageForDate(
    commitment: Commitment.Active,
    dailyUsage: DailyUsage?,
    wallClock: WallClockPoint,
  ): DailyUsage {
    if (dailyUsage == null || dailyUsage.commitmentId != commitment.id || dailyUsage.date != wallClock.date) {
      return DailyUsage.empty(commitmentId = commitment.id, date = wallClock.date)
    }
    return dailyUsage
  }
}
