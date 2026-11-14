package com.example.domain.lifecycle

import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.CompletionNextStep
import com.example.domain.time.WallClockPoint

enum class PinVerificationResult {
  VERIFIED,
  FAILED,
  NOT_PROVIDED,
}

sealed interface CommitmentTransitionError {
  data object ExplicitAgreementRequired : CommitmentTransitionError

  data object RulesLockedWhileNotDraft : CommitmentTransitionError

  data class InvalidStateTransition(
    val fromState: String,
    val attemptedAction: String,
  ) : CommitmentTransitionError

  data object CommitmentPeriodNotElapsed : CommitmentTransitionError

  data object WarningConfirmationRequired : CommitmentTransitionError

  data object PinAuthorizationRequired : CommitmentTransitionError

  data object TimestampPrecedesActivation : CommitmentTransitionError
}

sealed interface CommitmentResult<out T : Commitment> {
  data class Success<out T : Commitment>(val commitment: T) : CommitmentResult<T>

  data class Failure(val error: CommitmentTransitionError) : CommitmentResult<Nothing>
}

/**
 * Pure, deterministic state machine governing MIZAN commitment lifecycle transitions.
 */
object CommitmentStateMachine {

  fun createDraft(
    id: CommitmentId,
    rules: CommitmentRules,
    now: WallClockPoint,
  ): Commitment.Draft = Commitment.Draft(id = id, createdAt = now, rules = rules)

  fun updateDraftRules(
    commitment: Commitment,
    newRules: CommitmentRules,
  ): CommitmentResult<Commitment.Draft> {
    if (commitment !is Commitment.Draft) {
      return CommitmentResult.Failure(CommitmentTransitionError.RulesLockedWhileNotDraft)
    }
    return CommitmentResult.Success(commitment.copy(rules = newRules))
  }

  fun activate(
    commitment: Commitment,
    agreedToExactRules: Boolean,
    now: WallClockPoint,
  ): CommitmentResult<Commitment.Active> {
    if (commitment !is Commitment.Draft) {
      return CommitmentResult.Failure(
        CommitmentTransitionError.InvalidStateTransition(
          fromState = commitment::class.simpleName ?: "Unknown",
          attemptedAction = "activate",
        )
      )
    }
    if (!agreedToExactRules) {
      return CommitmentResult.Failure(CommitmentTransitionError.ExplicitAgreementRequired)
    }
    val endsAt = now.plusElapsedDays(Commitment.INITIAL_DURATION_DAYS)
    return CommitmentResult.Success(
      Commitment.Active(
        id = commitment.id,
        createdAt = commitment.createdAt,
        rules = commitment.rules,
        activatedAt = now,
        endsAt = endsAt,
      )
    )
  }

  fun completeIfReachedEnd(
    commitment: Commitment,
    now: WallClockPoint,
  ): CommitmentResult<Commitment.Completed> {
    if (commitment !is Commitment.Active) {
      return CommitmentResult.Failure(
        CommitmentTransitionError.InvalidStateTransition(
          fromState = commitment::class.simpleName ?: "Unknown",
          attemptedAction = "complete",
        )
      )
    }
    if (!commitment.hasReachedEnd(now)) {
      return CommitmentResult.Failure(CommitmentTransitionError.CommitmentPeriodNotElapsed)
    }
    return CommitmentResult.Success(
      Commitment.Completed(
        id = commitment.id,
        createdAt = commitment.createdAt,
        rules = commitment.rules,
        activatedAt = commitment.activatedAt,
        endsAt = commitment.endsAt,
        completedAt = now,
        nextStepChoice = null,
      )
    )
  }

  fun chooseNextStep(
    commitment: Commitment,
    choice: CompletionNextStep,
  ): CommitmentResult<Commitment.Completed> {
    if (commitment !is Commitment.Completed) {
      return CommitmentResult.Failure(
        CommitmentTransitionError.InvalidStateTransition(
          fromState = commitment::class.simpleName ?: "Unknown",
          attemptedAction = "chooseNextStep",
        )
      )
    }
    return CommitmentResult.Success(commitment.copy(nextStepChoice = choice))
  }

  fun emergencyTerminate(
    commitment: Commitment,
    confirmedWarning: Boolean,
    pinVerification: PinVerificationResult,
    now: WallClockPoint,
  ): CommitmentResult<Commitment.EmergencyTerminated> {
    if (commitment !is Commitment.Active) {
      return CommitmentResult.Failure(
        CommitmentTransitionError.InvalidStateTransition(
          fromState = commitment::class.simpleName ?: "Unknown",
          attemptedAction = "emergencyTerminate",
        )
      )
    }
    if (!confirmedWarning) {
      return CommitmentResult.Failure(CommitmentTransitionError.WarningConfirmationRequired)
    }
    if (now.epochMillis < commitment.activatedAt.epochMillis) {
      return CommitmentResult.Failure(CommitmentTransitionError.TimestampPrecedesActivation)
    }
    val pinVerified = pinVerification == PinVerificationResult.VERIFIED
    if (commitment.rules.requirePinForEmergencyTermination && !pinVerified) {
      return CommitmentResult.Failure(CommitmentTransitionError.PinAuthorizationRequired)
    }
    return CommitmentResult.Success(
      Commitment.EmergencyTerminated(
        id = commitment.id,
        createdAt = commitment.createdAt,
        rules = commitment.rules,
        activatedAt = commitment.activatedAt,
        plannedEndsAt = commitment.endsAt,
        terminatedAt = now,
        pinVerified = pinVerified,
      )
    )
  }
}
