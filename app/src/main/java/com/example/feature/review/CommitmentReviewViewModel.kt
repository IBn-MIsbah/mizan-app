package com.example.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.core.common.IdGenerator
import com.example.core.common.toDisplayMessage
import com.example.core.navigation.MizanRoute
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentRulesValidator
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.CommitmentTransitionError
import com.example.domain.lifecycle.RulesValidationResult
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.pin.AccountabilityPinRepository
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import com.example.platform.vpn.VpnFilterMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface CommitmentReviewUiState {
  data object Loading : CommitmentReviewUiState

  data class Error(val message: String) : CommitmentReviewUiState

  data class Ready(
    val draft: Commitment.Draft,
    val previewStart: WallClockPoint,
    val previewEnd: WallClockPoint,
    val hasActiveCommitmentConflict: Boolean,
    val agreedToExactRules: Boolean = false,
    val isSubmitting: Boolean = false,
    val activationError: String? = null,
  ) : CommitmentReviewUiState
}

sealed interface CommitmentReviewEffect {
  data object NavigateToDashboard : CommitmentReviewEffect
}

@HiltViewModel
class CommitmentReviewViewModel
@Inject
constructor(
  private val commitmentRepository: CommitmentRepository,
  private val accountabilityPinRepository: AccountabilityPinRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val vpnFilterMonitor: VpnFilterMonitor,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val commitmentIdArg: String =
    runCatching { savedStateHandle.toRoute<MizanRoute.CommitmentReview>().commitmentId }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
      ?: savedStateHandle.get<String>("commitmentId").orEmpty()

  private val _uiState = MutableStateFlow<CommitmentReviewUiState>(CommitmentReviewUiState.Loading)
  val uiState: StateFlow<CommitmentReviewUiState> = _uiState.asStateFlow()

  private val _effects = Channel<CommitmentReviewEffect>(Channel.BUFFERED)
  val effects = _effects.receiveAsFlow()

  init {
    loadReview()
  }

  fun loadReview() {
    if (commitmentIdArg.isBlank()) {
      _uiState.value = CommitmentReviewUiState.Error("Missing commitment draft identifier.")
      return
    }
    _uiState.value = CommitmentReviewUiState.Loading
    viewModelScope.launch {
      val draftResult = commitmentRepository.getCommitment(CommitmentId(commitmentIdArg))
      val activeResult = commitmentRepository.observeActiveCommitment().first()

      when (draftResult) {
        is PersistenceResult.Failure -> {
          _uiState.value = CommitmentReviewUiState.Error(draftResult.error.toDisplayMessage())
        }
        is PersistenceResult.Success -> {
          val commitment = draftResult.data
          if (commitment !is Commitment.Draft) {
            _uiState.value =
              CommitmentReviewUiState.Error(
                "Only draft commitments can be reviewed for activation."
              )
            return@launch
          }
          val hasConflict =
            activeResult is PersistenceResult.Success && activeResult.data != null
          val now = timeProvider.currentWallClock()
          _uiState.value =
            CommitmentReviewUiState.Ready(
              draft = commitment,
              previewStart = now,
              previewEnd = now.plusElapsedDays(Commitment.INITIAL_DURATION_DAYS),
              hasActiveCommitmentConflict = hasConflict,
            )
        }
      }
    }
  }

  fun setAgreementChecked(agreed: Boolean) {
    val current = _uiState.value as? CommitmentReviewUiState.Ready ?: return
    _uiState.value = current.copy(agreedToExactRules = agreed, activationError = null)
  }

  fun activateCommitment() {
    val current = _uiState.value as? CommitmentReviewUiState.Ready ?: return
    if (current.isSubmitting) return

    if (current.hasActiveCommitmentConflict) {
      _uiState.value =
        current.copy(
          activationError =
            "Another commitment is currently active. Complete or terminate it before activating a new one."
        )
      return
    }

    if (CommitmentRulesValidator.validateForActivation(current.draft.rules) !is RulesValidationResult.Valid) {
      _uiState.value =
        current.copy(
          activationError =
            "Draft configuration is incomplete or invalid. Return to Setup to fix rule issues."
        )
      return
    }

    val now = timeProvider.currentWallClock()
    when (
      val activationResult =
        CommitmentStateMachine.activate(
          commitment = current.draft,
          agreedToExactRules = current.agreedToExactRules,
          now = now,
        )
    ) {
      is CommitmentResult.Failure -> {
        _uiState.value =
          current.copy(activationError = mapTransitionError(activationResult.error))
      }
      is CommitmentResult.Success -> {
        val active = activationResult.commitment
        _uiState.value = current.copy(isSubmitting = true, activationError = null)
        viewModelScope.launch {
          if (active.rules.requirePinForEmergencyTermination) {
            val hasPinCredential = runCatching { accountabilityPinRepository.isCredentialAvailable() }.getOrDefault(false)
            if (!hasPinCredential) {
              _uiState.value =
                current.copy(
                  isSubmitting = false,
                  activationError =
                    "Accountability PIN protection is enabled, but no valid local PIN credential is available. Configure a PIN in Settings or turn off PIN protection in Setup.",
                )
              return@launch
            }
          }
          when (val saveResult = commitmentRepository.saveCommitment(active)) {
            is PersistenceResult.Failure -> {
              _uiState.value =
                current.copy(isSubmitting = false, activationError = saveResult.error.toDisplayMessage())
            }
            is PersistenceResult.Success -> {
              securityEventRepository.recordEvent(
                SecurityEvent(
                  id = SecurityEventId(idGenerator.nextId()),
                  commitmentId = active.id,
                  eventType = SecurityEventType.COMMITMENT_ACTIVATED,
                  occurredAt = active.activatedAt,
                  summary = "7-day commitment activated with explicit agreement",
                )
              )
              if (active.rules.hasConfiguredWebsiteFiltering()) {
                val prefs = runCatching { userPreferencesRepository.preferences.first() }.getOrNull()
                val canAutoStart =
                  prefs != null &&
                    prefs.websiteFilteringConsented &&
                    !prefs.vpnStoppedByUser &&
                    vpnFilterMonitor.prepareVpnIntent() == null
                if (canAutoStart) {
                  runCatching { vpnFilterMonitor.requestStartFiltering() }
                } else {
                  vpnFilterMonitor.refreshSystemStatus()
                }
              }
              _uiState.value = current.copy(isSubmitting = false)
              _effects.send(CommitmentReviewEffect.NavigateToDashboard)
            }
          }
        }
      }
    }
  }

  private fun mapTransitionError(error: CommitmentTransitionError): String =
    when (error) {
      CommitmentTransitionError.ExplicitAgreementRequired ->
        "Explicit agreement is required before activating this 7-day commitment."
      CommitmentTransitionError.RulesLockedWhileNotDraft ->
        "Rules can no longer be edited because this commitment is not a draft."
      is CommitmentTransitionError.InvalidStateTransition ->
        "Cannot activate from state ${error.fromState}."
      else -> "Unable to activate commitment."
    }
}
