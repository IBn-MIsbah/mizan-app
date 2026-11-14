package com.example.feature.completion

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.core.common.IdGenerator
import com.example.core.common.toDisplayMessage
import com.example.core.navigation.MizanRoute
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.CommitmentTransitionError
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.CompletionNextStep
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import com.example.platform.vpn.VpnFilterMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface CommitmentCompletionUiState {
  data object Loading : CommitmentCompletionUiState

  data class Error(val message: String) : CommitmentCompletionUiState

  data class PeriodNotYetElapsed(
    val active: Commitment.Active,
    val endsAt: WallClockPoint,
  ) : CommitmentCompletionUiState

  data class Ready(
    val completed: Commitment.Completed,
    val isSubmitting: Boolean = false,
    val disciplineModeRecordedNote: Boolean = false,
    val actionError: String? = null,
  ) : CommitmentCompletionUiState
}

sealed interface CommitmentCompletionEffect {
  data object NavigateToDashboard : CommitmentCompletionEffect

  data object NavigateToSetup : CommitmentCompletionEffect
}

@HiltViewModel
class CommitmentCompletionViewModel
@Inject
constructor(
  private val commitmentRepository: CommitmentRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val vpnFilterMonitor: VpnFilterMonitor,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val commitmentIdArg: String =
    runCatching { savedStateHandle.toRoute<MizanRoute.CommitmentCompletion>().commitmentId }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
      ?: savedStateHandle.get<String>("commitmentId").orEmpty()

  private val _uiState =
    MutableStateFlow<CommitmentCompletionUiState>(CommitmentCompletionUiState.Loading)
  val uiState: StateFlow<CommitmentCompletionUiState> = _uiState.asStateFlow()

  private val _effects = Channel<CommitmentCompletionEffect>(Channel.BUFFERED)
  val effects = _effects.receiveAsFlow()

  init {
    loadOrTransitionToCompleted()
  }

  fun loadOrTransitionToCompleted() {
    if (commitmentIdArg.isBlank()) {
      _uiState.value = CommitmentCompletionUiState.Error("Missing commitment identifier.")
      return
    }
    _uiState.value = CommitmentCompletionUiState.Loading
    viewModelScope.launch {
      when (val result = commitmentRepository.getCommitment(CommitmentId(commitmentIdArg))) {
        is PersistenceResult.Failure -> {
          _uiState.value = CommitmentCompletionUiState.Error(result.error.toDisplayMessage())
        }
        is PersistenceResult.Success -> {
          when (val commitment = result.data) {
            null -> {
              _uiState.value = CommitmentCompletionUiState.Error("Commitment record not found.")
            }
            is Commitment.Completed -> {
              _uiState.value =
                CommitmentCompletionUiState.Ready(
                  completed = commitment,
                  disciplineModeRecordedNote =
                    commitment.nextStepChoice == CompletionNextStep.ENTER_DISCIPLINE_MODE,
                )
            }
            is Commitment.Active -> {
              val now = timeProvider.currentWallClock()
              when (
                val transition =
                  CommitmentStateMachine.completeIfReachedEnd(commitment = commitment, now = now)
              ) {
                is CommitmentResult.Failure -> {
                  if (transition.error is CommitmentTransitionError.CommitmentPeriodNotElapsed) {
                    _uiState.value =
                      CommitmentCompletionUiState.PeriodNotYetElapsed(
                        active = commitment,
                        endsAt = commitment.endsAt,
                      )
                  } else {
                    _uiState.value =
                      CommitmentCompletionUiState.Error("Unable to complete this commitment.")
                  }
                }
                is CommitmentResult.Success -> {
                  val completed = transition.commitment
                  when (val saveResult = commitmentRepository.saveCommitment(completed)) {
                    is PersistenceResult.Failure -> {
                      _uiState.value =
                        CommitmentCompletionUiState.Error(saveResult.error.toDisplayMessage())
                    }
                    is PersistenceResult.Success -> {
                      securityEventRepository.recordEvent(
                        SecurityEvent(
                          id = SecurityEventId(idGenerator.nextId()),
                          commitmentId = completed.id,
                          eventType = SecurityEventType.COMMITMENT_COMPLETED,
                          occurredAt = completed.completedAt,
                          summary = "7-day commitment period completed",
                        )
                      )
                      runCatching { vpnFilterMonitor.onCommitmentEnded() }
                      _uiState.value = CommitmentCompletionUiState.Ready(completed = completed)
                    }
                  }
                }
              }
            }
            is Commitment.Draft,
            is Commitment.EmergencyTerminated -> {
              _uiState.value =
                CommitmentCompletionUiState.Error(
                  "Only active or completed commitments can enter completion review."
                )
            }
          }
        }
      }
    }
  }

  fun selectNextStep(choice: CompletionNextStep) {
    val current = _uiState.value as? CommitmentCompletionUiState.Ready ?: return
    if (current.isSubmitting) return

    when (val updatedResult = CommitmentStateMachine.chooseNextStep(current.completed, choice)) {
      is CommitmentResult.Failure -> {
        _uiState.value =
          current.copy(actionError = "Unable to record completion choice.")
      }
      is CommitmentResult.Success -> {
        val updatedCompleted = updatedResult.commitment
        _uiState.value = current.copy(isSubmitting = true, actionError = null)
        viewModelScope.launch {
          when (val saveCompleted = commitmentRepository.saveCommitment(updatedCompleted)) {
            is PersistenceResult.Failure -> {
              _uiState.value =
                current.copy(isSubmitting = false, actionError = saveCompleted.error.toDisplayMessage())
            }
            is PersistenceResult.Success -> {
              when (choice) {
                CompletionNextStep.FINISH -> {
                  _uiState.value = current.copy(completed = updatedCompleted, isSubmitting = false)
                  _effects.send(CommitmentCompletionEffect.NavigateToDashboard)
                }
                CompletionNextStep.BEGIN_ANOTHER_DETOX -> {
                  val newDraft =
                    CommitmentStateMachine.createDraft(
                      id = CommitmentId(idGenerator.nextId()),
                      rules =
                        CommitmentRules(
                          mode = RestrictionMode.HYBRID,
                          restrictedApps = emptySet(),
                        ),
                      now = timeProvider.currentWallClock(),
                    )
                  when (val saveDraft = commitmentRepository.saveCommitment(newDraft)) {
                    is PersistenceResult.Failure -> {
                      _uiState.value =
                        current.copy(
                          completed = updatedCompleted,
                          isSubmitting = false,
                          actionError = saveDraft.error.toDisplayMessage(),
                        )
                    }
                    is PersistenceResult.Success -> {
                      _uiState.value =
                        current.copy(completed = updatedCompleted, isSubmitting = false)
                      _effects.send(CommitmentCompletionEffect.NavigateToSetup)
                    }
                  }
                }
                CompletionNextStep.ENTER_DISCIPLINE_MODE -> {
                  _uiState.value =
                    current.copy(
                      completed = updatedCompleted,
                      isSubmitting = false,
                      disciplineModeRecordedNote = true,
                    )
                }
              }
            }
          }
        }
      }
    }
  }
}
