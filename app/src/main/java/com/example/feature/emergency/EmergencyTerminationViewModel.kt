package com.example.feature.emergency

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
import com.example.domain.lifecycle.PinVerificationResult
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.pin.AccountabilityPinRepository
import com.example.domain.pin.PinVerificationStatus
import com.example.domain.repository.AccountabilityMetadataRepository
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.time.TimeProvider
import com.example.platform.vpn.VpnFilterMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface EmergencyTerminationUiState {
  data object Loading : EmergencyTerminationUiState

  data class Error(val message: String) : EmergencyTerminationUiState

  data class BlockedByUnavailablePinVerification(val active: Commitment.Active) :
    EmergencyTerminationUiState

  data class Ready(
    val active: Commitment.Active,
    val requiresPin: Boolean = false,
    val pinInput: String = "",
    val lockoutRemainingSeconds: Long = 0L,
    val confirmedWarning: Boolean = false,
    val isSubmitting: Boolean = false,
    val terminationError: String? = null,
  ) : EmergencyTerminationUiState
}

sealed interface EmergencyTerminationEffect {
  data object NavigateToDashboard : EmergencyTerminationEffect
}

@HiltViewModel
class EmergencyTerminationViewModel
@Inject
constructor(
  private val commitmentRepository: CommitmentRepository,
  private val accountabilityMetadataRepository: AccountabilityMetadataRepository,
  private val accountabilityPinRepository: AccountabilityPinRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val vpnFilterMonitor: VpnFilterMonitor,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val commitmentIdArg: String =
    runCatching { savedStateHandle.toRoute<MizanRoute.EmergencyTermination>().commitmentId }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
      ?: savedStateHandle.get<String>("commitmentId").orEmpty()

  private val _uiState =
    MutableStateFlow<EmergencyTerminationUiState>(EmergencyTerminationUiState.Loading)
  val uiState: StateFlow<EmergencyTerminationUiState> = _uiState.asStateFlow()

  private val _effects = Channel<EmergencyTerminationEffect>(Channel.BUFFERED)
  val effects = _effects.receiveAsFlow()

  init {
    loadCommitment()
  }

  fun loadCommitment() {
    if (commitmentIdArg.isBlank()) {
      _uiState.value = EmergencyTerminationUiState.Error("Missing commitment identifier.")
      return
    }
    _uiState.value = EmergencyTerminationUiState.Loading
    viewModelScope.launch {
      val commitmentResult = commitmentRepository.getCommitment(CommitmentId(commitmentIdArg))
      val metadataResult = accountabilityMetadataRepository.observeMetadata().first()

      when (commitmentResult) {
        is PersistenceResult.Failure -> {
          _uiState.value =
            EmergencyTerminationUiState.Error(commitmentResult.error.toDisplayMessage())
        }
        is PersistenceResult.Success -> {
          val commitment = commitmentResult.data
          if (commitment !is Commitment.Active) {
            _uiState.value =
              EmergencyTerminationUiState.Error(
                "Emergency termination applies only to active commitments."
              )
            return@launch
          }
          val metadataPinEnabled =
            metadataResult is PersistenceResult.Success && metadataResult.data.isPinEnabled
          val pinRequired =
            commitment.rules.requirePinForEmergencyTermination || metadataPinEnabled
          if (pinRequired) {
            val available = runCatching { accountabilityPinRepository.isCredentialAvailable() }.getOrDefault(false)
            if (!available) {
              // Fail closed: never allow emergency termination without a verifiable Keystore PIN credential.
              _uiState.value =
                EmergencyTerminationUiState.BlockedByUnavailablePinVerification(commitment)
              return@launch
            }
            val lockoutMs =
              runCatching { accountabilityPinRepository.currentLockoutRemainingMillis() }
                .getOrDefault(0L)
            _uiState.value =
              EmergencyTerminationUiState.Ready(
                active = commitment,
                requiresPin = true,
                lockoutRemainingSeconds = millisToSecondsCeil(lockoutMs),
              )
          } else {
            _uiState.value = EmergencyTerminationUiState.Ready(active = commitment)
          }
        }
      }
    }
  }

  fun setPinInput(value: String) {
    val current = _uiState.value as? EmergencyTerminationUiState.Ready ?: return
    val sanitized = value.filter { it.isDigit() }.take(8)
    _uiState.value = current.copy(pinInput = sanitized, terminationError = null)
  }

  fun setWarningConfirmed(confirmed: Boolean) {
    val current = _uiState.value as? EmergencyTerminationUiState.Ready ?: return
    _uiState.value = current.copy(confirmedWarning = confirmed, terminationError = null)
  }

  fun confirmTermination() {
    val current = _uiState.value as? EmergencyTerminationUiState.Ready ?: return
    if (current.isSubmitting) return

    if (!current.confirmedWarning) {
      _uiState.value =
        current.copy(
          terminationError =
            "Explicit confirmation is required before terminating an active commitment."
        )
      return
    }

    if (current.requiresPin && current.pinInput.isBlank()) {
      _uiState.value =
        current.copy(
          terminationError = "Enter your Accountability PIN to authorize emergency termination."
        )
      return
    }

    _uiState.value = current.copy(isSubmitting = true, terminationError = null)
    viewModelScope.launch {
      val pinVerificationResult =
        if (!current.requiresPin) {
          PinVerificationResult.NOT_PROVIDED
        } else {
          when (val verifyStatus = accountabilityPinRepository.verifyPin(current.pinInput)) {
            PinVerificationStatus.Verified -> PinVerificationResult.VERIFIED
            is PinVerificationStatus.Failed -> {
              val lockoutSec = millisToSecondsCeil(verifyStatus.lockoutRemainingMillis)
              val message =
                if (lockoutSec > 0L) {
                  "Too many incorrect PIN attempts. Wait ${lockoutSec}s before trying again."
                } else {
                  "Incorrect Accountability PIN. ${verifyStatus.attemptsRemainingBeforeCooldown} attempt(s) left before cooldown."
                }
              _uiState.value =
                current.copy(
                  isSubmitting = false,
                  pinInput = "",
                  lockoutRemainingSeconds = lockoutSec,
                  terminationError = message,
                )
              return@launch
            }
            is PinVerificationStatus.LockedOut -> {
              val lockoutSec = millisToSecondsCeil(verifyStatus.remainingMillis)
              _uiState.value =
                current.copy(
                  isSubmitting = false,
                  pinInput = "",
                  lockoutRemainingSeconds = lockoutSec,
                  terminationError =
                    "Accountability PIN verification is cooling down (${lockoutSec}s remaining).",
                )
              return@launch
            }
            PinVerificationStatus.NotConfigured,
            is PinVerificationStatus.Unavailable -> {
              _uiState.value =
                EmergencyTerminationUiState.BlockedByUnavailablePinVerification(current.active)
              return@launch
            }
          }
        }

      val now = timeProvider.currentWallClock()
      when (
        val result =
          CommitmentStateMachine.emergencyTerminate(
            commitment = current.active,
            confirmedWarning = current.confirmedWarning,
            pinVerification = pinVerificationResult,
            now = now,
          )
      ) {
        is CommitmentResult.Failure -> {
          val message =
            when (result.error) {
              CommitmentTransitionError.WarningConfirmationRequired ->
                "Explicit confirmation is required before terminating an active commitment."
              CommitmentTransitionError.PinAuthorizationRequired ->
                "Valid Accountability PIN verification is required before terminating this commitment."
              CommitmentTransitionError.TimestampPrecedesActivation ->
                "Device clock appears earlier than activation time; refusing invalid termination timestamp."
              else -> "Unable to perform emergency termination."
            }
          _uiState.value =
            current.copy(isSubmitting = false, pinInput = "", terminationError = message)
        }
        is CommitmentResult.Success -> {
          val terminated = result.commitment
          when (val saveResult = commitmentRepository.saveCommitment(terminated)) {
            is PersistenceResult.Failure -> {
              _uiState.value =
                current.copy(
                  isSubmitting = false,
                  pinInput = "",
                  terminationError = saveResult.error.toDisplayMessage(),
                )
            }
            is PersistenceResult.Success -> {
              securityEventRepository.recordEvent(
                SecurityEvent(
                  id = SecurityEventId(idGenerator.nextId()),
                  commitmentId = terminated.id,
                  eventType = SecurityEventType.EMERGENCY_TERMINATED,
                  occurredAt = terminated.terminatedAt,
                  summary = "Commitment ended early via emergency termination",
                )
              )
              runCatching { vpnFilterMonitor.onCommitmentEnded() }
              _uiState.value = current.copy(isSubmitting = false, pinInput = "")
              _effects.send(EmergencyTerminationEffect.NavigateToDashboard)
            }
          }
        }
      }
    }
  }

  private fun millisToSecondsCeil(millis: Long): Long =
    if (millis <= 0L) 0L else ceil(millis / 1000.0).toLong().coerceAtLeast(1L)
}
