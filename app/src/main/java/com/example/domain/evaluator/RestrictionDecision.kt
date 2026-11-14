package com.example.domain.evaluator

enum class RestrictionReason {
  NO_ACTIVE_COMMITMENT,
  COMMITMENT_PERIOD_ELAPSED,
  APP_ALLOWLISTED,
  APP_NOT_RESTRICTED,
  STRICT_MODE,
  SCHEDULED_WINDOW,
  OUTSIDE_SCHEDULE,
  WITHIN_DAILY_ALLOWANCE,
  DAILY_ALLOWANCE_EXHAUSTED,
}

data class RestrictionDecision(
  val isBlocked: Boolean,
  val reason: RestrictionReason,
) {
  val isAllowed: Boolean
    get() = !isBlocked

  companion object {
    fun allow(reason: RestrictionReason): RestrictionDecision =
      RestrictionDecision(isBlocked = false, reason = reason)

    fun block(reason: RestrictionReason): RestrictionDecision =
      RestrictionDecision(isBlocked = true, reason = reason)
  }
}
