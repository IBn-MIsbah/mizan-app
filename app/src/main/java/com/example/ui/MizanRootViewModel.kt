package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppPreferences
import com.example.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface MainUiState {
  data object Loading : MainUiState

  data class Ready(val preferences: AppPreferences) : MainUiState
}

@HiltViewModel
class MizanRootViewModel
@Inject
constructor(
  userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  val uiState: StateFlow<MainUiState> =
    userPreferencesRepository.preferences
      .map<AppPreferences, MainUiState> { MainUiState.Ready(it) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState.Loading,
      )
}
