package com.example.domain.model

import com.example.domain.time.WallClockPoint

enum class CompletionNextStep {
  FINISH,
  BEGIN_ANOTHER_DETOX,
  ENTER_DISCIPLINE_MODE,
}

/**
 * Immutable commitment lifecycle state modeled as a sealed hierarchy so invalid combinations
 * (such as an active commitment without activation/end timestamps or mutable rules while active)
 * cannot be represented.
 */
sealed interface Commitment {
  val id: CommitmentId
  val createdAt: WallClockPoint
  val rules: CommitmentRules

  data class Draft(
    override val id: CommitmentId,
    override val createdAt: WallClockPoint,
    override val rules: CommitmentRules,
  ) : Commitment

  data class Active(
    override val id: CommitmentId,
    override val createdAt: WallClockPoint,
    override val rules: CommitmentRules,
    val activatedAt: WallClockPoint,
    val endsAt: WallClockPoint,
  ) : Commitment {
    init {
      require(endsAt.epochMillis > activatedAt.epochMillis) {
        "Active commitment endsAt must be strictly after activatedAt"
      }
    }

    fun hasReachedEnd(now: WallClockPoint): Boolean =
      now.epochMillis >= endsAt.epochMillis
  }

  data class Completed(
    override val id: CommitmentId,
    override val createdAt: WallClockPoint,
    override val rules: CommitmentRules,
    val activatedAt: WallClockPoint,
    val endsAt: WallClockPoint,
    val completedAt: WallClockPoint,
    val nextStepChoice: CompletionNextStep? = null,
  ) : Commitment {
    init {
      require(completedAt.epochMillis >= endsAt.epochMillis) {
        "Completed commitment completedAt must be at or after endsAt"
      }
    }
  }

  data class EmergencyTerminated(
    override val id: CommitmentId,
    override val createdAt: WallClockPoint,
    override val rules: CommitmentRules,
    val activatedAt: WallClockPoint,
    val plannedEndsAt: WallClockPoint,
    val terminatedAt: WallClockPoint,
    val pinVerified: Boolean,
  ) : Commitment {
    init {
      require(terminatedAt.epochMillis >= activatedAt.epochMillis) {
        "Emergency termination timestamp cannot precede activatedAt"
      }
      if (rules.requirePinForEmergencyTermination) {
        require(pinVerified) {
          "EmergencyTerminated requires pinVerified = true when accountability PIN is enabled"
        }
      }
    }
  }

  companion object {
    const val INITIAL_DURATION_DAYS: Int = 7
  }
}
