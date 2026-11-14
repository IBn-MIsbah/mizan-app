package com.example.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.core.common.toDisplayMessage
import com.example.core.navigation.MizanRoute
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface OnboardingUiState {
  data class Ready(
    val revisitFromSettings: Boolean,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
  ) : OnboardingUiState
}

sealed interface OnboardingEffect {
  data object NavigateToDashboard : OnboardingEffect
}

@HiltViewModel
class OnboardingViewModel
@Inject
constructor(
  private val userPreferencesRepository: UserPreferencesRepository,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private val revisitFromSettings: Boolean =
    runCatching { savedStateHandle.toRoute<MizanRoute.Onboarding>().revisitFromSettings }
      .getOrElse { savedStateHandle.get<Boolean>("revisitFromSettings") ?: false }

  private val _uiState =
    MutableStateFlow<OnboardingUiState>(
      OnboardingUiState.Ready(revisitFromSettings = revisitFromSettings)
    )
  val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

  private val _effects = Channel<OnboardingEffect>(Channel.BUFFERED)
  val effects = _effects.receiveAsFlow()

  fun completeOnboarding() {
    val current = _uiState.value as? OnboardingUiState.Ready ?: return
    if (current.isSubmitting) return

    _uiState.value = current.copy(isSubmitting = true, errorMessage = null)
    viewModelScope.launch {
      when (val result = userPreferencesRepository.setOnboardingCompleted(true)) {
        is PersistenceResult.Success -> {
          _uiState.value = current.copy(isSubmitting = false, errorMessage = null)
          _effects.send(OnboardingEffect.NavigateToDashboard)
        }
        is PersistenceResult.Failure -> {
          _uiState.value =
            current.copy(isSubmitting = false, errorMessage = result.error.toDisplayMessage())
        }
      }
    }
  }
}
