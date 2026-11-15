package com.example.domain.lifecycle

import com.example.domain.filter.DomainNormalizationResult
import com.example.domain.filter.DomainNormalizer
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DomainRuleType
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ScheduleWindow
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime

sealed interface RulesValidationError {
  data object RestrictedAppsRequired : RulesValidationError

  data class ConflictingAppSelections(val overlappingPackageNames: Set<String>) :
    RulesValidationError

  data object ScheduleWindowsRequiredForScheduledMode : RulesValidationError

  data object ScheduleWindowDaysRequired : RulesValidationError

  data object ScheduleWindowZeroLength : RulesValidationError

  data object DuplicateScheduleWindow : RulesValidationError

  data object DailyAllowanceOutOfRange : RulesValidationError

  data class InvalidBlockedDomains(val rawDomains: Set<String>) : RulesValidationError

  data class ConflictingDomainRules(val overlappingDomains: Set<String>) : RulesValidationError

  data object WebsiteFilteringTargetsRequired : RulesValidationError
}

sealed interface RulesValidationResult {
  data object Valid : RulesValidationResult

  data class Invalid(val errors: List<RulesValidationError>) : RulesValidationResult
}

/**
 * Pure domain configuration validator for [CommitmentRules] and candidate [ScheduleWindow] inputs.
 *
 * Ensures configuration validity is enforced in the domain layer rather than duplicated in UI code.
 */
object CommitmentRulesValidator {

  fun validateCandidateWindow(
    daysOfWeek: Set<DomainDayOfWeek>,
    start: DomainTime,
    end: DomainTime,
    existingWindows: List<ScheduleWindow> = emptyList(),
  ): List<RulesValidationError> {
    val errors = mutableListOf<RulesValidationError>()
    if (daysOfWeek.isEmpty()) {
      errors += RulesValidationError.ScheduleWindowDaysRequired
    }
    if (start == end) {
      errors += RulesValidationError.ScheduleWindowZeroLength
    }
    if (errors.isEmpty()) {
      val candidate = ScheduleWindow(daysOfWeek = daysOfWeek, start = start, end = end)
      if (existingWindows.any { it == candidate }) {
        errors += RulesValidationError.DuplicateScheduleWindow
      }
    }
    return errors
  }

  fun validateForActivation(rules: CommitmentRules): RulesValidationResult {
    val errors = mutableListOf<RulesValidationError>()

    val hasWebsiteTargets =
      rules.websiteFilteringEnabled &&
        (rules.blockedDomains.any { it.ruleType == DomainRuleType.BLOCK } ||
          rules.blockedDomainCategories.isNotEmpty())

    if (rules.restrictedApps.isEmpty() && !hasWebsiteTargets && !rules.blockYouTubeShorts) {
      errors += RulesValidationError.RestrictedAppsRequired
    }

    if (
      rules.websiteFilteringEnabled &&
        rules.blockedDomains.none { it.ruleType == DomainRuleType.BLOCK } &&
        rules.blockedDomainCategories.isEmpty()
    ) {
      errors += RulesValidationError.WebsiteFilteringTargetsRequired
    }

    val invalidDomains = mutableSetOf<String>()
    val blockHosts = mutableSetOf<String>()
    val allowHosts = mutableSetOf<String>()

    for (entry in rules.blockedDomains) {
      when (val normalized = DomainNormalizer.normalize(entry.domain.value)) {
        is DomainNormalizationResult.Invalid -> invalidDomains += entry.domain.value
        is DomainNormalizationResult.Valid -> {
          when (entry.ruleType) {
            DomainRuleType.BLOCK -> blockHosts += normalized.normalizedHost
            DomainRuleType.ALLOW -> allowHosts += normalized.normalizedHost
          }
        }
      }
    }

    if (invalidDomains.isNotEmpty()) {
      errors += RulesValidationError.InvalidBlockedDomains(invalidDomains)
    }

    val overlappingDomains = blockHosts.intersect(allowHosts)
    if (overlappingDomains.isNotEmpty()) {
      errors += RulesValidationError.ConflictingDomainRules(overlappingDomains)
    }

    val restrictedIds: Set<AppId> = rules.restrictedApps.map { it.appId }.toSet()
    val allowedIds: Set<AppId> = rules.allowedApps.map { it.appId }.toSet()
    val overlap = restrictedIds.intersect(allowedIds)
    if (overlap.isNotEmpty()) {
      errors +=
        RulesValidationError.ConflictingAppSelections(
          overlappingPackageNames = overlap.map { it.packageName }.toSet()
        )
    }

    if (rules.mode == RestrictionMode.SCHEDULED && rules.scheduleWindows.isEmpty()) {
      errors += RulesValidationError.ScheduleWindowsRequiredForScheduledMode
    }

    val distinctWindows = rules.scheduleWindows.distinct()
    if (distinctWindows.size != rules.scheduleWindows.size) {
      errors += RulesValidationError.DuplicateScheduleWindow
    }

    if (
      !rules.dailyAllowance.isFinite() ||
        rules.dailyAllowance.isNegative() ||
        rules.dailyAllowance > CommitmentRules.MAX_DAILY_ALLOWANCE
    ) {
      errors += RulesValidationError.DailyAllowanceOutOfRange
    }

    return if (errors.isEmpty()) {
      RulesValidationResult.Valid
    } else {
      RulesValidationResult.Invalid(errors)
    }
  }
}
