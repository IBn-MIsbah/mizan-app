package com.example.feature.commitments

import com.example.MainDispatcherRule
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.repository.InstalledAppsResult
import com.example.domain.repository.PersistenceResult
import com.example.testing.FakeAccountabilityMetadataRepository
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDomainFilterRepository
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeInstalledAppRepository
import com.example.testing.FakeTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentsViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val timeProvider = FakeTimeProvider()
  private val idGenerator = FakeIdGenerator()
  private val sampleSocialApp =
    InstalledAppInfo(
      appId = AppId("com.example.social"),
      displayLabel = "Sample Social",
      isSystemEssential = false,
    )
  private val sampleDialerApp =
    InstalledAppInfo(
      appId = AppId("com.android.dialer"),
      displayLabel = "Phone",
      isSystemEssential = true,
    )
  private val installedRepo =
    FakeInstalledAppRepository(
      InstalledAppsResult.Success(listOf(sampleSocialApp, sampleDialerApp))
    )

  @Test
  fun `invalid configuration blocks navigation to review`() = runTest {
    val repo = FakeCommitmentRepository()
    val viewModel =
      CommitmentsViewModel(
        repo,
        installedRepo,
        FakeDomainFilterRepository(),
        FakeAccountabilityMetadataRepository(),
        FakeEnforcementHealthMonitor(),
        timeProvider,
        idGenerator,
      )
    val job = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    // Scheduled mode with zero restricted apps and zero schedule windows
    viewModel.selectMode(RestrictionMode.SCHEDULED)
    viewModel.continueToReview()
    advanceUntilIdle()

    val state = viewModel.uiState.value as CommitmentSetupUiState.Editing
    assertTrue(state.editor.validationMessages.isNotEmpty())
    assertTrue((repo.observeAllCommitments().first() as PersistenceResult.Success).data.isEmpty())
    job.cancel()
  }

  @Test
  fun `valid installed app configuration saves draft with stable package names`() = runTest {
    val repo = FakeCommitmentRepository()
    val viewModel =
      CommitmentsViewModel(
        repo,
        installedRepo,
        FakeDomainFilterRepository(),
        FakeAccountabilityMetadataRepository(),
        FakeEnforcementHealthMonitor(),
        timeProvider,
        idGenerator,
      )
    val job = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    viewModel.toggleInstalledRestrictedApp(sampleSocialApp)
    viewModel.toggleInstalledAllowedApp(sampleDialerApp)
    viewModel.addScheduleWindow()
    viewModel.setDailyAllowanceMinutes(45)
    viewModel.continueToReview()
    advanceUntilIdle()

    val effect = viewModel.effects.first()
    assertTrue(effect is CommitmentSetupEffect.NavigateToReview)
    val saved =
      (repo.observeAllCommitments().first() as PersistenceResult.Success).data.single()
        as Commitment.Draft
    assertEquals(45L, saved.rules.dailyAllowance.inWholeMinutes)
    assertTrue(saved.rules.isRestricted(sampleSocialApp.appId))
    assertTrue(saved.rules.isAllowlisted(sampleDialerApp.appId))
    job.cancel()
  }

  @Test
  fun `active commitment locks setup editing`() = runTest {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Sample Social")),
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-1"), rules, timeProvider.wallClock)
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
          as CommitmentResult.Success)
        .commitment
    val repo = FakeCommitmentRepository(listOf(active))
    val viewModel =
      CommitmentsViewModel(
        repo,
        installedRepo,
        FakeDomainFilterRepository(),
        FakeAccountabilityMetadataRepository(),
        FakeEnforcementHealthMonitor(),
        timeProvider,
        idGenerator,
      )
    val job = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value is CommitmentSetupUiState.ActiveLocked)
    job.cancel()
  }
}
