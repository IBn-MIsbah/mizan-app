package com.example.domain.lifecycle

import com.example.domain.model.AllowedApp
import com.example.domain.model.AllowlistCategory
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ScheduleWindow
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentRulesValidatorTest {

  private val instagram = RestrictedApp(AppId("com.instagram.android"), "Instagram")
  private val phone = AllowedApp(AppId("com.android.dialer"), "Phone", AllowlistCategory.EMERGENCY_PHONE)
  private val validWindow =
    ScheduleWindow(
      daysOfWeek = setOf(DomainDayOfWeek.MONDAY),
      start = DomainTime(9, 0),
      end = DomainTime(17, 0),
    )

  @Test
  fun `empty restricted apps fails domain activation validation`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = emptySet(),
      )

    val result = CommitmentRulesValidator.validateForActivation(rules)

    assertTrue(result is RulesValidationResult.Invalid)
    assertTrue(
      RulesValidationError.RestrictedAppsRequired in (result as RulesValidationResult.Invalid).errors
    )
  }

  @Test
  fun `overlapping restricted and allowed app fails validation`() {
    val conflictPkg = AppId("com.example.overlap")
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = setOf(RestrictedApp(conflictPkg, "Overlap")),
        allowedApps = setOf(AllowedApp(conflictPkg, "Overlap", AllowlistCategory.WORK)),
      )

    val result = CommitmentRulesValidator.validateForActivation(rules)

    assertTrue(result is RulesValidationResult.Invalid)
    val errors = (result as RulesValidationResult.Invalid).errors
    assertTrue(errors.any { it is RulesValidationError.ConflictingAppSelections })
  }

  @Test
  fun `scheduled mode without schedule windows fails validation`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.SCHEDULED,
        restrictedApps = setOf(instagram),
        scheduleWindows = emptyList(),
      )

    val result = CommitmentRulesValidator.validateForActivation(rules)

    assertTrue(result is RulesValidationResult.Invalid)
    assertTrue(
      RulesValidationError.ScheduleWindowsRequiredForScheduledMode in
        (result as RulesValidationResult.Invalid).errors
    )
  }

  @Test
  fun `candidate schedule window validation rejects empty days zero length and duplicates`() {
    val emptyDaysErrors =
      CommitmentRulesValidator.validateCandidateWindow(
        daysOfWeek = emptySet(),
        start = DomainTime(9, 0),
        end = DomainTime(17, 0),
      )
    assertEquals(listOf(RulesValidationError.ScheduleWindowDaysRequired), emptyDaysErrors)

    val zeroLengthErrors =
      CommitmentRulesValidator.validateCandidateWindow(
        daysOfWeek = setOf(DomainDayOfWeek.MONDAY),
        start = DomainTime(10, 0),
        end = DomainTime(10, 0),
      )
    assertEquals(listOf(RulesValidationError.ScheduleWindowZeroLength), zeroLengthErrors)

    val duplicateErrors =
      CommitmentRulesValidator.validateCandidateWindow(
        daysOfWeek = setOf(DomainDayOfWeek.MONDAY),
        start = DomainTime(9, 0),
        end = DomainTime(17, 0),
        existingWindows = listOf(validWindow),
      )
    assertEquals(listOf(RulesValidationError.DuplicateScheduleWindow), duplicateErrors)
  }

  @Test
  fun `valid hybrid configuration passes domain validation`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.HYBRID,
        restrictedApps = setOf(instagram),
        allowedApps = setOf(phone),
        scheduleWindows = listOf(validWindow),
        dailyAllowance = 30.minutes,
      )

    val result = CommitmentRulesValidator.validateForActivation(rules)

    assertEquals(RulesValidationResult.Valid, result)
  }
}
