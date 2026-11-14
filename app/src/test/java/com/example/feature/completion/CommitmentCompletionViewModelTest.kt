package com.example.feature.completion

import androidx.lifecycle.SavedStateHandle
import com.example.MainDispatcherRule
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.CompletionNextStep
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.PersistenceResult
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentCompletionViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val timeProvider = FakeTimeProvider()
  private val rules =
    CommitmentRules(
      mode = RestrictionMode.STRICT,
      restrictedApps = setOf(RestrictedApp(AppId("com.instagram.android"), "Instagram")),
    )

  private fun createActive(): Commitment.Active {
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-1"), rules, timeProvider.wallClock)
    return (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
        as CommitmentResult.Success)
      .commitment
  }

  @Test
  fun `completion rejects early call before 7 day boundary`() = runTest {
    val active = createActive()
    val repo = FakeCommitmentRepository(listOf(active))
    val viewModel =
      CommitmentCompletionViewModel(
        commitmentRepository = repo,
        securityEventRepository = FakeSecurityEventRepository(),
        vpnFilterMonitor = FakeVpnFilterMonitor(),
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        savedStateHandle = SavedStateHandle(mapOf("commitmentId" to active.id.value)),
      )
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value is CommitmentCompletionUiState.PeriodNotYetElapsed)
    assertTrue(
      (repo.getCommitment(active.id) as PersistenceResult.Success).data is Commitment.Active
    )
  }

  @Test
  fun `completion at end transitions without auto renew and begin another creates separate draft`() =
    runTest {
      val active = createActive()
      timeProvider.wallClock = active.endsAt
      val repo = FakeCommitmentRepository(listOf(active))
      val securityRepo = FakeSecurityEventRepository()
      val vpnMonitor = FakeVpnFilterMonitor()
      val viewModel =
        CommitmentCompletionViewModel(
          commitmentRepository = repo,
          securityEventRepository = securityRepo,
          vpnFilterMonitor = vpnMonitor,
          timeProvider = timeProvider,
          idGenerator = FakeIdGenerator(),
          savedStateHandle = SavedStateHandle(mapOf("commitmentId" to active.id.value)),
        )
      advanceUntilIdle()

      val ready = viewModel.uiState.value as CommitmentCompletionUiState.Ready
      assertEquals(null, ready.completed.nextStepChoice)
      assertEquals(SecurityEventType.COMMITMENT_COMPLETED, securityRepo.recordedEvents.single().eventType)

      viewModel.selectNextStep(CompletionNextStep.BEGIN_ANOTHER_DETOX)
      advanceUntilIdle()

      val all = (repo.observeAllCommitments().first() as PersistenceResult.Success).data
      assertEquals(2, all.size)
      val originalCompleted = all.first { it.id == active.id } as Commitment.Completed
      assertEquals(CompletionNextStep.BEGIN_ANOTHER_DETOX, originalCompleted.nextStepChoice)
      assertTrue(all.any { it is Commitment.Draft && it.id != active.id })
      assertEquals(CommitmentCompletionEffect.NavigateToSetup, viewModel.effects.first())
    }
}
