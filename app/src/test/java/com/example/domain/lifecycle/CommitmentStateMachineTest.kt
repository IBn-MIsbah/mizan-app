package com.example.domain.lifecycle

import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.CompletionNextStep
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentStateMachineTest {

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(9, 0, 0),
      epochMillis = 1_794_560_400_000L,
      utcOffsetMinutes = 0,
      zoneId = "UTC",
    )

  private val defaultRules =
    CommitmentRules(
      mode = RestrictionMode.HYBRID,
      restrictedApps = setOf(RestrictedApp(AppId("com.instagram.android"), "Instagram")),
      requirePinForEmergencyTermination = false,
    )

  private fun createDraft(rules: CommitmentRules = defaultRules): Commitment.Draft =
    CommitmentStateMachine.createDraft(
      id = CommitmentId("commit-1"),
      rules = rules,
      now = baseTime,
    )

  @Test
  fun `new commitment starts as draft and cannot be activated without explicit agreement`() {
    val draft: Commitment = createDraft()
    assertTrue(draft is Commitment.Draft)

    val result = CommitmentStateMachine.activate(draft, agreedToExactRules = false, now = baseTime)

    assertEquals(
      CommitmentResult.Failure(CommitmentTransitionError.ExplicitAgreementRequired),
      result,
    )
  }

  @Test
  fun `explicit agreement activates commitment for seven days`() {
    val draft = createDraft()

    val result = CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseTime)

    val active = (result as CommitmentResult.Success).commitment
    assertEquals(baseTime, active.activatedAt)
    assertEquals(
      baseTime.epochMillis + 7L * WallClockPoint.MILLIS_PER_DAY,
      active.endsAt.epochMillis,
    )
    assertEquals(DomainDate(2026, 11, 20), active.endsAt.date)
  }

  @Test
  fun `allowance and rules cannot be modified once commitment is active`() {
    val draft = createDraft()
    val updatedDraft =
      (CommitmentStateMachine.updateDraftRules(
          draft,
          defaultRules.copy(dailyAllowance = 15.minutes),
        ) as CommitmentResult.Success)
        .commitment
    assertEquals(15.minutes, updatedDraft.rules.dailyAllowance)

    val active =
      (CommitmentStateMachine.activate(
          updatedDraft,
          agreedToExactRules = true,
          now = baseTime,
        ) as CommitmentResult.Success)
        .commitment

    val lockedResult =
      CommitmentStateMachine.updateDraftRules(
        active,
        defaultRules.copy(dailyAllowance = 45.minutes),
      )

    assertEquals(
      CommitmentResult.Failure(CommitmentTransitionError.RulesLockedWhileNotDraft),
      lockedResult,
    )
  }

  @Test
  fun `completion is rejected before end boundary and succeeds at exact boundary`() {
    val active =
      (CommitmentStateMachine.activate(
          createDraft(),
          agreedToExactRules = true,
          now = baseTime,
        ) as CommitmentResult.Success)
        .commitment

    val oneMillisBeforeEnd =
      active.endsAt.copy(epochMillis = active.endsAt.epochMillis - 1L)
    val earlyResult = CommitmentStateMachine.completeIfReachedEnd(active, oneMillisBeforeEnd)
    assertEquals(
      CommitmentResult.Failure(CommitmentTransitionError.CommitmentPeriodNotElapsed),
      earlyResult,
    )

    val exactBoundaryResult = CommitmentStateMachine.completeIfReachedEnd(active, active.endsAt)
    val completed = (exactBoundaryResult as CommitmentResult.Success).commitment
    assertEquals(active.endsAt, completed.completedAt)
    assertNull(completed.nextStepChoice)
  }

  @Test
  fun `completion does not auto renew and completed commitment cannot be reactivated`() {
    val active =
      (CommitmentStateMachine.activate(
          createDraft(),
          agreedToExactRules = true,
          now = baseTime,
        ) as CommitmentResult.Success)
        .commitment
    val completed =
      (CommitmentStateMachine.completeIfReachedEnd(active, active.endsAt) as CommitmentResult.Success)
        .commitment

    val chosen =
      (CommitmentStateMachine.chooseNextStep(
          completed,
          CompletionNextStep.BEGIN_ANOTHER_DETOX,
        ) as CommitmentResult.Success)
        .commitment
    assertEquals(CompletionNextStep.BEGIN_ANOTHER_DETOX, chosen.nextStepChoice)

    val reactivateAttempt =
      CommitmentStateMachine.activate(chosen, agreedToExactRules = true, now = active.endsAt)
    assertTrue(reactivateAttempt is CommitmentResult.Failure)
  }

  @Test
  fun `emergency termination requires warning confirmation and differs from normal completion`() {
    val active =
      (CommitmentStateMachine.activate(
          createDraft(),
          agreedToExactRules = true,
          now = baseTime,
        ) as CommitmentResult.Success)
        .commitment
    val midPoint = baseTime.plusElapsedDays(2)

    val unconfirmed =
      CommitmentStateMachine.emergencyTerminate(
        commitment = active,
        confirmedWarning = false,
        pinVerification = PinVerificationResult.NOT_PROVIDED,
        now = midPoint,
      )
    assertEquals(
      CommitmentResult.Failure(CommitmentTransitionError.WarningConfirmationRequired),
      unconfirmed,
    )

    val terminated =
      (CommitmentStateMachine.emergencyTerminate(
          commitment = active,
          confirmedWarning = true,
          pinVerification = PinVerificationResult.NOT_PROVIDED,
          now = midPoint,
        ) as CommitmentResult.Success)
        .commitment
    assertEquals(midPoint, terminated.terminatedAt)
    assertFalse(terminated.pinVerified)
    val baseCommitment: Commitment = terminated
    assertTrue(baseCommitment is Commitment.EmergencyTerminated)
    assertFalse(baseCommitment is Commitment.Completed)
  }

  @Test
  fun `pin protected emergency termination requires verified pin`() {
    val pinDraft = createDraft(defaultRules.copy(requirePinForEmergencyTermination = true))
    val active =
      (CommitmentStateMachine.activate(
          pinDraft,
          agreedToExactRules = true,
          now = baseTime,
        ) as CommitmentResult.Success)
        .commitment
    val midPoint = baseTime.plusElapsedDays(1)

    assertEquals(
      CommitmentResult.Failure(CommitmentTransitionError.PinAuthorizationRequired),
      CommitmentStateMachine.emergencyTerminate(
        commitment = active,
        confirmedWarning = true,
        pinVerification = PinVerificationResult.NOT_PROVIDED,
        now = midPoint,
      ),
    )

    assertEquals(
      CommitmentResult.Failure(CommitmentTransitionError.PinAuthorizationRequired),
      CommitmentStateMachine.emergencyTerminate(
        commitment = active,
        confirmedWarning = true,
        pinVerification = PinVerificationResult.FAILED,
        now = midPoint,
      ),
    )

    val terminated =
      (CommitmentStateMachine.emergencyTerminate(
          commitment = active,
          confirmedWarning = true,
          pinVerification = PinVerificationResult.VERIFIED,
          now = midPoint,
        ) as CommitmentResult.Success)
        .commitment
    assertTrue(terminated.pinVerified)
  }

  @Test
  fun `invalid state transitions are rejected`() {
    val draft = createDraft()
    assertTrue(
      CommitmentStateMachine.completeIfReachedEnd(draft, baseTime.plusElapsedDays(7))
        is CommitmentResult.Failure
    )
    assertTrue(
      CommitmentStateMachine.emergencyTerminate(
        draft,
        confirmedWarning = true,
        pinVerification = PinVerificationResult.VERIFIED,
        now = baseTime,
      ) is CommitmentResult.Failure
    )
    assertTrue(
      CommitmentStateMachine.chooseNextStep(draft, CompletionNextStep.FINISH)
        is CommitmentResult.Failure
    )
  }
}
