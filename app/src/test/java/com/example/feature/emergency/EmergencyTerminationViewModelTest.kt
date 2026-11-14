package com.example.feature.emergency

import androidx.lifecycle.SavedStateHandle
import com.example.MainDispatcherRule
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.PersistenceResult
import com.example.testing.FakeAccountabilityMetadataRepository
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeVpnFilterMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyTerminationViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val timeProvider = FakeTimeProvider()

  private fun createActive(requirePin: Boolean): Commitment.Active {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = setOf(RestrictedApp(AppId("com.instagram.android"), "Instagram")),
        requirePinForEmergencyTermination = requirePin,
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-1"), rules, timeProvider.wallClock)
    return (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
        as CommitmentResult.Success)
      .commitment
  }

  @Test
  fun `emergency termination requires explicit warning confirmation`() = runTest {
    val active = createActive(requirePin = false)
    val repo = FakeCommitmentRepository(listOf(active))
    val securityRepo = FakeSecurityEventRepository()
    val viewModel =
      EmergencyTerminationViewModel(
        commitmentRepository = repo,
        accountabilityMetadataRepository = FakeAccountabilityMetadataRepository(),
        securityEventRepository = securityRepo,
        vpnFilterMonitor = FakeVpnFilterMonitor(),
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        savedStateHandle = SavedStateHandle(mapOf("commitmentId" to active.id.value)),
      )
    advanceUntilIdle()

    viewModel.confirmTermination()
    advanceUntilIdle()

    val rejected = viewModel.uiState.value as EmergencyTerminationUiState.Ready
    assertNotNull(rejected.terminationError)
    assertTrue(
      (repo.getCommitment(active.id) as PersistenceResult.Success).data is Commitment.Active
    )

    viewModel.setWarningConfirmed(true)
    viewModel.confirmTermination()
    advanceUntilIdle()

    assertTrue(
      (repo.getCommitment(active.id) as PersistenceResult.Success).data
        is Commitment.EmergencyTerminated
    )
    assertEquals(
      SecurityEventType.EMERGENCY_TERMINATED,
      securityRepo.recordedEvents.single().eventType,
    )
    assertEquals(EmergencyTerminationEffect.NavigateToDashboard, viewModel.effects.first())
  }

  @Test
  fun `pin protected commitment fails closed and never bypasses verification`() = runTest {
    val active = createActive(requirePin = true)
    val repo = FakeCommitmentRepository(listOf(active))
    val viewModel =
      EmergencyTerminationViewModel(
        commitmentRepository = repo,
        accountabilityMetadataRepository =
          FakeAccountabilityMetadataRepository(AccountabilityMetadata(isPinEnabled = true)),
        securityEventRepository = FakeSecurityEventRepository(),
        vpnFilterMonitor = FakeVpnFilterMonitor(),
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        savedStateHandle = SavedStateHandle(mapOf("commitmentId" to active.id.value)),
      )
    advanceUntilIdle()

    assertTrue(
      viewModel.uiState.value is EmergencyTerminationUiState.BlockedByUnavailablePinVerification
    )
    viewModel.confirmTermination()
    advanceUntilIdle()
    assertTrue(
      (repo.getCommitment(active.id) as PersistenceResult.Success).data is Commitment.Active
    )
  }
}
