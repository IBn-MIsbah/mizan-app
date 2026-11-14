package com.example.feature.blocking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.evaluator.RestrictionEvaluator
import com.example.domain.evaluator.RestrictionReason
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ThemePreference
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DailyUsageRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class BlockingTarget(
  val packageName: String,
  val fallbackLabel: String?,
)

sealed interface BlockingUiState {
  data class Loading(val themePreference: ThemePreference = ThemePreference.SYSTEM) :
    BlockingUiState

  data class Blocked(
    val themePreference: ThemePreference,
    val appLabel: String,
    val packageName: String,
    val reason: RestrictionReason,
    val mode: RestrictionMode,
    val commitmentEndsAt: WallClockPoint,
  ) : BlockingUiState

  data class ShouldDismiss(val themePreference: ThemePreference = ThemePreference.SYSTEM) :
    BlockingUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BlockingViewModel
@Inject
constructor(
  private val commitmentRepository: CommitmentRepository,
  private val dailyUsageRepository: DailyUsageRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val timeProvider: TimeProvider,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val targetFlow =
    MutableStateFlow(
      BlockingTarget(
        packageName = savedStateHandle.get<String>(BlockingActivity.EXTRA_BLOCKED_PACKAGE).orEmpty(),
        fallbackLabel = savedStateHandle.get<String>(BlockingActivity.EXTRA_BLOCKED_APP_LABEL),
      )
    )

  val uiState: StateFlow<BlockingUiState> =
    combine(
        targetFlow,
        commitmentRepository.observeActiveCommitment(),
        userPreferencesRepository.preferences,
      ) { target, commitmentResult, prefs ->
        Triple(target, commitmentResult, prefs.themePreference)
      }
      .flatMapLatest { (target, commitmentResult, themePreference) ->
        if (target.packageName.isBlank()) {
          return@flatMapLatest flowOf(BlockingUiState.ShouldDismiss(themePreference))
        }
        when (commitmentResult) {
          is PersistenceResult.Failure ->
            flowOf(BlockingUiState.ShouldDismiss(themePreference))
          is PersistenceResult.Success -> {
            val active = commitmentResult.data as? Commitment.Active
              ?: return@flatMapLatest flowOf(BlockingUiState.ShouldDismiss(themePreference))

            val now = timeProvider.currentWallClock()
            if (active.rules.mode == RestrictionMode.HYBRID) {
              dailyUsageRepository.observeDailyUsage(active.id, now.date).let { usageFlow ->
                combine(usageFlow, flowOf(Unit)) { usageResult, _ ->
                  val currentNow = timeProvider.currentWallClock()
                  val dailyUsage =
                    when (usageResult) {
                      is PersistenceResult.Success -> usageResult.data
                      is PersistenceResult.Failure -> null
                    }
                  if (usageResult is PersistenceResult.Failure) {
                    BlockingUiState.ShouldDismiss(themePreference)
                  } else {
                    evaluateForState(target, active, currentNow, dailyUsage, themePreference)
                  }
                }
              }
            } else {
              flowOf(evaluateForState(target, active, now, null, themePreference))
            }
          }
        }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BlockingUiState.Loading(),
      )

  fun updateTarget(packageName: String, appLabel: String?) {
    targetFlow.value = BlockingTarget(packageName = packageName, fallbackLabel = appLabel)
  }

  private fun evaluateForState(
    target: BlockingTarget,
    active: Commitment.Active,
    now: WallClockPoint,
    dailyUsage: com.example.domain.model.DailyUsage?,
    themePreference: ThemePreference,
  ): BlockingUiState {
    val decision =
      RestrictionEvaluator.evaluate(
        commitment = active,
        appId = AppId(target.packageName),
        wallClock = now,
        dailyUsage = dailyUsage,
      )
    if (!decision.isBlocked) {
      return BlockingUiState.ShouldDismiss(themePreference)
    }
    val resolvedLabel =
      active.rules.restrictedApps
        .firstOrNull { it.appId.packageName == target.packageName }
        ?.displayLabel
        ?: target.fallbackLabel?.takeIf { it.isNotBlank() }
        ?: target.packageName

    return BlockingUiState.Blocked(
      themePreference = themePreference,
      appLabel = resolvedLabel,
      packageName = target.packageName,
      reason = decision.reason,
      mode = active.rules.mode,
      commitmentEndsAt = active.endsAt,
    )
  }
}
