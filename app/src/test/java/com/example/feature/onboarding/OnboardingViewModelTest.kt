package com.example.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import com.example.MainDispatcherRule
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.InstalledAppsResult
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.testing.FakeAccountabilityMetadataRepository
import com.example.testing.FakeAccountabilityPinRepository
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDomainFilterRepository
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeInstalledAppRepository
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUsageAccessMonitor
import com.example.testing.FakeUserPreferencesRepository
import com.example.testing.FakeVpnFilterMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

  private fun createViewModel(
    prefsRepo: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    commitmentRepo: FakeCommitmentRepository = FakeCommitmentRepository(),
    installedAppRepo: FakeInstalledAppRepository =
      FakeInstalledAppRepository(
        InstalledAppsResult.Success(
          listOf(InstalledAppInfo(AppId("com.example.social"), "Social", isSystemEssential = false))
        )
      ),
    domainFilterRepo: FakeDomainFilterRepository = FakeDomainFilterRepository(),
    metadataRepo: FakeAccountabilityMetadataRepository = FakeAccountabilityMetadataRepository(),
    pinRepo: FakeAccountabilityPinRepository = FakeAccountabilityPinRepository(),
    securityEventRepo: FakeSecurityEventRepository = FakeSecurityEventRepository(),
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
  ): OnboardingViewModel =
    OnboardingViewModel(
      userPreferencesRepository = prefsRepo,
      commitmentRepository = commitmentRepo,
      installedAppRepository = installedAppRepo,
      domainFilterRepository = domainFilterRepo,
      accountabilityMetadataRepository = metadataRepo,
      accountabilityPinRepository = pinRepo,
      securityEventRepository = securityEventRepo,
      enforcementHealthMonitor = FakeEnforcementHealthMonitor(),
      usageAccessMonitor = FakeUsageAccessMonitor(),
      vpnFilterMonitor = FakeVpnFilterMonitor(),
      timeProvider = FakeTimeProvider(),
      idGenerator = FakeIdGenerator(),
      savedStateHandle = savedStateHandle,
    )

  @Test
  fun `skipOnboarding persists preference and emits navigation effect without activating commitment`() =
    runTest {
      val prefsRepo = FakeUserPreferencesRepository()
      val commitmentRepo = FakeCommitmentRepository()
      val viewModel = createViewModel(prefsRepo = prefsRepo, commitmentRepo = commitmentRepo)
      val job = launch { viewModel.uiState.collect {} }
      advanceUntilIdle()

      viewModel.selectMode(RestrictionMode.STRICT)
      viewModel.onCustomRestrictedLabelChange("Social")
      viewModel.onCustomRestrictedPackageChange("com.example.social")
      viewModel.addCustomRestrictedApp()
      advanceUntilIdle()

      viewModel.skipOnboarding()
      advanceUntilIdle()

      assertTrue(prefsRepo.preferences.first().onboardingCompleted)
      assertEquals(OnboardingEffect.NavigateToDashboard, viewModel.effects.first())
      val stored = (commitmentRepo.observeAllCommitments().first() as PersistenceResult.Success).data
      assertEquals(1, stored.size)
      assertTrue(stored.single() is Commitment.Draft)
      job.cancel()
    }

  @Test
  fun `completeOnboarding failure surfaces error and keeps onboarding incomplete`() = runTest {
    val prefsRepo = FakeUserPreferencesRepository()
    prefsRepo.writeFailure = PersistenceError.StorageFailure("Disk full")
    val viewModel = createViewModel(prefsRepo = prefsRepo)
    val job = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    viewModel.completeOnboarding()
    advanceUntilIdle()

    assertFalse(prefsRepo.preferences.first().onboardingCompleted)
    val state = viewModel.uiState.value as OnboardingUiState.Ready
    assertEquals("Disk full", state.errorMessage)
    job.cancel()
  }

  @Test
  fun `five stage onboarding saves stage to SavedStateHandle, validates setup, and requires explicit agreement`() =
    runTest {
      val prefsRepo = FakeUserPreferencesRepository()
      val commitmentRepo = FakeCommitmentRepository()
      val securityRepo = FakeSecurityEventRepository()
      val savedStateHandle = SavedStateHandle()
      val viewModel =
        createViewModel(
          prefsRepo = prefsRepo,
          commitmentRepo = commitmentRepo,
          securityEventRepo = securityRepo,
          savedStateHandle = savedStateHandle,
        )
      val job = launch { viewModel.uiState.collect {} }
      advanceUntilIdle()

      assertEquals(
        OnboardingStage.INTRODUCTION,
        (viewModel.uiState.value as OnboardingUiState.Ready).stage,
      )

      viewModel.advanceFromIntroduction()
      advanceUntilIdle()
      assertEquals(
        OnboardingStage.PERMISSIONS,
        (viewModel.uiState.value as OnboardingUiState.Ready).stage,
      )

      viewModel.advanceFromPermissions()
      advanceUntilIdle()
      assertEquals(
        OnboardingStage.CONFIGURATION,
        (viewModel.uiState.value as OnboardingUiState.Ready).stage,
      )
      assertEquals(
        OnboardingStage.CONFIGURATION.name,
        savedStateHandle[OnboardingViewModel.KEY_ONBOARDING_STAGE],
      )

      // Empty configuration cannot advance to Review
      viewModel.advanceFromConfiguration()
      advanceUntilIdle()
      var ready = viewModel.uiState.value as OnboardingUiState.Ready
      assertEquals(OnboardingStage.CONFIGURATION, ready.stage)
      assertTrue(ready.editor.validationMessages.isNotEmpty())

      // Configure Strict app restriction -> advance to Review
      viewModel.selectMode(RestrictionMode.STRICT)
      viewModel.toggleInstalledRestrictedApp(
        InstalledAppInfo(AppId("com.example.social"), "Social", isSystemEssential = false)
      )
      advanceUntilIdle()
      viewModel.advanceFromConfiguration()
      advanceUntilIdle()
      ready = viewModel.uiState.value as OnboardingUiState.Ready
      assertEquals(OnboardingStage.REVIEW, ready.stage)

      // Recreating ViewModel with same SavedStateHandle restores REVIEW stage
      val restoredVm =
        createViewModel(
          prefsRepo = prefsRepo,
          commitmentRepo = commitmentRepo,
          securityEventRepo = securityRepo,
          savedStateHandle = savedStateHandle,
        )
      val restoredJob = launch { restoredVm.uiState.collect {} }
      advanceUntilIdle()
      assertEquals(
        OnboardingStage.REVIEW,
        (restoredVm.uiState.value as OnboardingUiState.Ready).stage,
      )

      // Step back to Configuration works cleanly
      assertTrue(restoredVm.goBackStage())
      advanceUntilIdle()
      assertEquals(
        OnboardingStage.CONFIGURATION,
        (restoredVm.uiState.value as OnboardingUiState.Ready).stage,
      )
      restoredVm.advanceFromConfiguration()
      advanceUntilIdle()
      restoredVm.advanceFromReview()
      advanceUntilIdle()
      assertEquals(
        OnboardingStage.AGREEMENT,
        (restoredVm.uiState.value as OnboardingUiState.Ready).stage,
      )

      // Cannot activate without explicit checkbox agreement
      restoredVm.activateCommitment()
      advanceUntilIdle()
      assertFalse(prefsRepo.preferences.first().onboardingCompleted)
      assertTrue(
        (restoredVm.uiState.value as OnboardingUiState.Ready).errorMessage != null
      )

      // Check agreement -> activates 7-day commitment, logs event, completes onboarding
      restoredVm.setAgreementChecked(true)
      restoredVm.activateCommitment()
      advanceUntilIdle()

      assertTrue(prefsRepo.preferences.first().onboardingCompleted)
      assertEquals(OnboardingEffect.NavigateToDashboard, restoredVm.effects.first())
      val stored = (commitmentRepo.observeAllCommitments().first() as PersistenceResult.Success).data
      assertTrue(stored.any { it is Commitment.Active })
      assertTrue(
        securityRepo.recordedEvents.any { it.eventType == SecurityEventType.COMMITMENT_ACTIVATED }
      )

      restoredJob.cancel()
      job.cancel()
    }
}
