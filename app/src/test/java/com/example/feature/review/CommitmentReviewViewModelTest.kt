package com.example.feature.review

import androidx.lifecycle.SavedStateHandle
import com.example.MainDispatcherRule
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUserPreferencesRepository
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
class CommitmentReviewViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val timeProvider = FakeTimeProvider()
  private val rules =
    CommitmentRules(
      mode = RestrictionMode.STRICT,
      restrictedApps = setOf(RestrictedApp(AppId("com.instagram.android"), "Instagram")),
    )

  @Test
  fun `activation fails without explicit agreement and succeeds once checked`() = runTest {
    val draft = CommitmentStateMachine.createDraft(CommitmentId("draft-1"), rules, timeProvider.wallClock)
    val repo = FakeCommitmentRepository(listOf(draft))
    val securityRepo = FakeSecurityEventRepository()
    val viewModel =
      CommitmentReviewViewModel(
        commitmentRepository = repo,
        securityEventRepository = securityRepo,
        userPreferencesRepository = FakeUserPreferencesRepository(),
        vpnFilterMonitor = FakeVpnFilterMonitor(),
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        savedStateHandle = SavedStateHandle(mapOf("commitmentId" to "draft-1")),
      )
    advanceUntilIdle()

    val ready = viewModel.uiState.value as CommitmentReviewUiState.Ready
    assertEquals(draft, ready.draft)

    // Attempt activation without checking agreement
    viewModel.activateCommitment()
    advanceUntilIdle()

    val afterRejected = viewModel.uiState.value as CommitmentReviewUiState.Ready
    assertNotNull(afterRejected.activationError)
    assertTrue(
      (repo.getCommitment(draft.id) as PersistenceResult.Success).data is Commitment.Draft
    )

    // Agree explicitly and activate
    viewModel.setAgreementChecked(true)
    viewModel.activateCommitment()
    advanceUntilIdle()

    val saved = (repo.getCommitment(draft.id) as PersistenceResult.Success).data
    assertTrue(saved is Commitment.Active)
    assertEquals(1, securityRepo.recordedEvents.size)
    assertEquals(SecurityEventType.COMMITMENT_ACTIVATED, securityRepo.recordedEvents.first().eventType)
    assertEquals(CommitmentReviewEffect.NavigateToDashboard, viewModel.effects.first())
  }

  @Test
  fun `save failure during activation surfaces error and does not leave false active state`() = runTest {
    val draft = CommitmentStateMachine.createDraft(CommitmentId("draft-2"), rules, timeProvider.wallClock)
    val repo = FakeCommitmentRepository(listOf(draft))
    repo.saveFailure = PersistenceError.StorageFailure("Write failed")
    val securityRepo = FakeSecurityEventRepository()
    val viewModel =
      CommitmentReviewViewModel(
        commitmentRepository = repo,
        securityEventRepository = securityRepo,
        userPreferencesRepository = FakeUserPreferencesRepository(),
        vpnFilterMonitor = FakeVpnFilterMonitor(),
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        savedStateHandle = SavedStateHandle(mapOf("commitmentId" to "draft-2")),
      )
    advanceUntilIdle()

    viewModel.setAgreementChecked(true)
    viewModel.activateCommitment()
    advanceUntilIdle()

    val state = viewModel.uiState.value as CommitmentReviewUiState.Ready
    assertEquals("Write failed", state.activationError)
    assertTrue(
      (repo.getCommitment(draft.id) as PersistenceResult.Success).data is Commitment.Draft
    )
    assertTrue(securityRepo.recordedEvents.isEmpty())
  }
}
