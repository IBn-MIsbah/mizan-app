package com.example.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class RestrictionMode {
  STRICT,
  SCHEDULED,
  HYBRID,
}

/**
 * Immutable rules configuration for a MIZAN commitment.
 *
 * - Default hybrid shared daily allowance is 30 minutes per local calendar day.
 * - In [RestrictionMode.STRICT], strict blocking overrides any allowance.
 * - In [RestrictionMode.HYBRID], scheduled windows block immediately while active, and outside
 *   scheduled windows restricted apps share [dailyAllowance] per local calendar day.
 * - Rules are locked once a commitment becomes [Commitment.Active].
 */
data class CommitmentRules(
  val mode: RestrictionMode,
  val restrictedApps: Set<RestrictedApp>,
  val allowedApps: Set<AllowedApp> = emptySet(),
  val scheduleWindows: List<ScheduleWindow> = emptyList(),
  val dailyAllowance: Duration = DEFAULT_DAILY_ALLOWANCE,
  val requirePinForEmergencyTermination: Boolean = false,
  val websiteFilteringEnabled: Boolean = false,
  val blockedDomains: Set<BlockedDomain> = emptySet(),
  val blockedDomainCategories: Set<DomainCategory> = emptySet(),
  val blockYouTubeShorts: Boolean = false,
) {
  init {
    require(dailyAllowance.isFinite() && !dailyAllowance.isNegative()) {
      "dailyAllowance must be finite and non-negative, got $dailyAllowance"
    }
    require(dailyAllowance <= MAX_DAILY_ALLOWANCE) {
      "dailyAllowance cannot exceed 24 hours, got $dailyAllowance"
    }
  }

  fun isAllowlisted(appId: AppId): Boolean = allowedApps.any { it.appId == appId }

  fun isRestricted(appId: AppId): Boolean = restrictedApps.any { it.appId == appId }

  fun isWithinScheduledWindow(
    date: com.example.domain.time.DomainDate,
    time: com.example.domain.time.DomainTime,
  ): Boolean = scheduleWindows.any { it.isActiveAt(date, time) }

  fun hasConfiguredWebsiteFiltering(): Boolean =
    websiteFilteringEnabled && (blockedDomains.isNotEmpty() || blockedDomainCategories.isNotEmpty())

  companion object {
    val DEFAULT_DAILY_ALLOWANCE: Duration = 30.minutes
    val MAX_DAILY_ALLOWANCE: Duration = 24.hours
  }
}
