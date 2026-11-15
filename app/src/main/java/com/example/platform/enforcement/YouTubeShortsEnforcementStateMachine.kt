package com.example.platform.enforcement

import com.example.platform.accessibility.ShortsDetectionClassification
import com.example.platform.accessibility.ShortsDetectionResult

enum class YouTubeShortsEnforcementState {
  IDLE,
  POSSIBLE_SHORTS,
  SHORTS_CONFIRMED,
  RETURN_REQUESTED,
  FEEDBACK_SHOWN,
  COOLDOWN,
}

sealed interface YouTubeShortsAction {
  data object None : YouTubeShortsAction

  data class NavigateBackAndNotify(val showFeedback: Boolean) : YouTubeShortsAction
}

/**
 * Prevents duplicate `GLOBAL_ACTION_BACK` dispatches, infinite navigation loops, and toast/notice
 * spam across high-frequency accessibility window/content event bursts.
 */
class YouTubeShortsEnforcementStateMachine(
  private val backCooldownMillis: Long = DEFAULT_BACK_COOLDOWN_MILLIS,
  private val feedbackCooldownMillis: Long = DEFAULT_FEEDBACK_COOLDOWN_MILLIS,
  private val settleBeforeResetMillis: Long = DEFAULT_SETTLE_BEFORE_RESET_MILLIS,
) {

  var state: YouTubeShortsEnforcementState = YouTubeShortsEnforcementState.IDLE
    private set

  private var lastBackRequestedMonotonicMs: Long = Long.MIN_VALUE
  private var lastFeedbackShownMonotonicMs: Long = Long.MIN_VALUE

  fun reset() {
    state = YouTubeShortsEnforcementState.IDLE
    lastBackRequestedMonotonicMs = Long.MIN_VALUE
  }

  fun onAppLeftYouTube() {
    state = YouTubeShortsEnforcementState.IDLE
  }

  fun onDetectionEvaluated(
    detection: ShortsDetectionResult,
    nowMonotonicMs: Long,
  ): YouTubeShortsAction {
    when (detection.classification) {
      ShortsDetectionClassification.NOT_YOUTUBE -> {
        state = YouTubeShortsEnforcementState.IDLE
        return YouTubeShortsAction.None
      }

      ShortsDetectionClassification.NON_SHORTS_SCREEN -> {
        val elapsedSinceBack =
          if (lastBackRequestedMonotonicMs == Long.MIN_VALUE) {
            Long.MAX_VALUE
          } else {
            nowMonotonicMs - lastBackRequestedMonotonicMs
          }
        // Only reset after a brief settle period (or if never triggered) so transient layout
        // frames immediately following GLOBAL_ACTION_BACK settle cleanly without bouncing.
        if (elapsedSinceBack < 0L || elapsedSinceBack >= settleBeforeResetMillis) {
          state = YouTubeShortsEnforcementState.IDLE
        } else {
          state = YouTubeShortsEnforcementState.COOLDOWN
        }
        return YouTubeShortsAction.None
      }

      ShortsDetectionClassification.UNCERTAIN -> {
        val inCooldown = isWithinBackCooldown(nowMonotonicMs)
        state =
          if (inCooldown) {
            YouTubeShortsEnforcementState.COOLDOWN
          } else {
            YouTubeShortsEnforcementState.POSSIBLE_SHORTS
          }
        return YouTubeShortsAction.None
      }

      ShortsDetectionClassification.CONFIRMED_SHORTS_PLAYER -> {
        if (isWithinBackCooldown(nowMonotonicMs)) {
          state = YouTubeShortsEnforcementState.COOLDOWN
          return YouTubeShortsAction.None
        }

        state = YouTubeShortsEnforcementState.SHORTS_CONFIRMED
        state = YouTubeShortsEnforcementState.RETURN_REQUESTED
        lastBackRequestedMonotonicMs = nowMonotonicMs

        val shouldShowFeedback =
          lastFeedbackShownMonotonicMs == Long.MIN_VALUE ||
            (nowMonotonicMs - lastFeedbackShownMonotonicMs) !in 0 until feedbackCooldownMillis
        if (shouldShowFeedback) {
          state = YouTubeShortsEnforcementState.FEEDBACK_SHOWN
          lastFeedbackShownMonotonicMs = nowMonotonicMs
        }
        state = YouTubeShortsEnforcementState.COOLDOWN
        return YouTubeShortsAction.NavigateBackAndNotify(showFeedback = shouldShowFeedback)
      }
    }
  }

  private fun isWithinBackCooldown(nowMonotonicMs: Long): Boolean {
    if (lastBackRequestedMonotonicMs == Long.MIN_VALUE) return false
    val elapsed = nowMonotonicMs - lastBackRequestedMonotonicMs
    return elapsed in 0 until backCooldownMillis
  }

  companion object {
    const val DEFAULT_BACK_COOLDOWN_MILLIS: Long = 1_800L
    const val DEFAULT_FEEDBACK_COOLDOWN_MILLIS: Long = 4_000L
    const val DEFAULT_SETTLE_BEFORE_RESET_MILLIS: Long = 500L
  }
}
