package com.example.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import com.example.MainDispatcherRule
import com.example.domain.repository.PersistenceError
import com.example.testing.FakeUserPreferencesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `completeOnboarding persists preference and emits navigation effect`() = runTest {
    val prefsRepo = FakeUserPreferencesRepository()
    val viewModel = OnboardingViewModel(prefsRepo, SavedStateHandle())

    viewModel.completeOnboarding()
    advanceUntilIdle()

    assertTrue(prefsRepo.preferences.first().onboardingCompleted)
    assertEquals(OnboardingEffect.NavigateToDashboard, viewModel.effects.first())
  }

  @Test
  fun `completeOnboarding failure surfaces error and keeps onboarding incomplete`() = runTest {
    val prefsRepo = FakeUserPreferencesRepository()
    prefsRepo.writeFailure = PersistenceError.StorageFailure("Disk full")
    val viewModel = OnboardingViewModel(prefsRepo, SavedStateHandle())

    viewModel.completeOnboarding()
    advanceUntilIdle()

    assertFalse(prefsRepo.preferences.first().onboardingCompleted)
    val state = viewModel.uiState.value as OnboardingUiState.Ready
    assertEquals("Disk full", state.errorMessage)
  }
}
