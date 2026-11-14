package com.example.platform

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.MainDispatcherRule
import com.example.core.designsystem.component.VpnFilterHealthCard
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.AppPreferences
import com.example.domain.model.BlockedDomain
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DomainCategory
import com.example.domain.model.DomainName
import com.example.domain.model.DomainRuleType
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.repository.PersistenceResult
import com.example.feature.commitments.CommitmentSetupUiState
import com.example.feature.commitments.CommitmentsScreen
import com.example.feature.commitments.CommitmentsViewModel
import com.example.feature.completion.CommitmentCompletionViewModel
import com.example.feature.emergency.EmergencyTerminationViewModel
import com.example.feature.review.CommitmentReviewViewModel
import com.example.platform.enforcement.AppRestrictionCoordinator
import com.example.platform.vpn.AndroidVpnFilterMonitor
import com.example.platform.vpn.DomainFilterVpnService
import com.example.platform.vpn.VpnFilterSnapshot
import com.example.platform.vpn.VpnServiceLifecycleStatus
import com.example.testing.FakeBlockingUiLauncher
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeDomainFilterRepository
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeInstalledAppRepository
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUserPreferencesRepository
import com.example.testing.FakeVpnFilterMonitor
import com.example.testing.createTestUsageReconciler
import com.example.testing.createTestUsageTracker
import com.example.ui.theme.MizanTheme
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VpnFilterAndUiRobolectricTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  @get:Rule val composeRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val timeProvider = FakeTimeProvider()

  @Test
  fun `manifest registers DomainFilterVpnService with BIND_VPN_SERVICE specialUse FGS and required network permissions`() {
    val pm = context.packageManager
    val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    val permissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertTrue(Manifest.permission.INTERNET in permissions)
    assertTrue(Manifest.permission.ACCESS_NETWORK_STATE in permissions)
    assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      assertTrue(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in permissions)
    }

    val vpnComponent = ComponentName(context.packageName, DomainFilterVpnService::class.java.name)
    val serviceInfo = pm.getServiceInfo(vpnComponent, PackageManager.GET_META_DATA)
    assertEquals(Manifest.permission.BIND_VPN_SERVICE, serviceInfo.permission)
    assertFalse(serviceInfo.exported)
    assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, serviceInfo.foregroundServiceType)
    assertNotNull(serviceInfo.metaData)
    assertFalse(serviceInfo.metaData.getBoolean("android.net.VpnService.SUPPORTS_ALWAYS_ON", true))
  }

  @Test
  fun `vpn monitor snapshot state transitions are honest and never report ACTIVE unless tunnel established`() {
    val activeCommitment =
      (CommitmentStateMachine.activate(
          CommitmentStateMachine.createDraft(
            id = CommitmentId("c-vpn-status"),
            rules =
              CommitmentRules(
                mode = RestrictionMode.STRICT,
                restrictedApps = emptySet(),
                websiteFilteringEnabled = true,
                blockedDomains = setOf(BlockedDomain(DomainName("social.example"), ruleType = DomainRuleType.BLOCK)),
                blockedDomainCategories = setOf(DomainCategory.SOCIAL_MEDIA),
              ),
            now = timeProvider.wallClock,
          ),
          agreedToExactRules = true,
          now = timeProvider.wallClock,
        ) as CommitmentResult.Success)
        .commitment

    val notConfigured =
      AndroidVpnFilterMonitor.computeSnapshot(
        activeCommitment = null,
        hasConsented = false,
        stoppedByUser = false,
        datasets = DatasetSeedDefaults.defaultMetadata(),
        isPermissionPrepared = false,
        isStarting = false,
        isTunnelEstablished = false,
        permissionDenied = false,
        wasRevoked = false,
        anotherVpnActive = false,
        isPrivateDnsActive = false,
        upstreamDnsCount = 0,
        lastError = null,
        nowEpochMillis = 1L,
      )
    assertEquals(VpnServiceLifecycleStatus.NOT_CONFIGURED, notConfigured.status)

    val startingNotActiveYet =
      AndroidVpnFilterMonitor.computeSnapshot(
        activeCommitment = activeCommitment,
        hasConsented = true,
        stoppedByUser = false,
        datasets = DatasetSeedDefaults.defaultMetadata(),
        isPermissionPrepared = true,
        isStarting = true,
        isTunnelEstablished = false,
        permissionDenied = false,
        wasRevoked = false,
        anotherVpnActive = false,
        isPrivateDnsActive = true,
        upstreamDnsCount = 1,
        lastError = null,
        nowEpochMillis = 2L,
      )
    assertEquals(VpnServiceLifecycleStatus.STARTING, startingNotActiveYet.status)
    assertTrue(startingNotActiveYet.hasMissingOrIncompleteDataset)
    assertTrue(startingNotActiveYet.isPrivateDnsActive)

    val anotherVpnConflict =
      AndroidVpnFilterMonitor.computeSnapshot(
        activeCommitment = activeCommitment,
        hasConsented = true,
        stoppedByUser = false,
        datasets = DatasetSeedDefaults.defaultMetadata(),
        isPermissionPrepared = true,
        isStarting = false,
        isTunnelEstablished = false,
        permissionDenied = false,
        wasRevoked = true,
        anotherVpnActive = true,
        isPrivateDnsActive = false,
        upstreamDnsCount = 0,
        lastError = null,
        nowEpochMillis = 3L,
      )
    assertEquals(VpnServiceLifecycleStatus.ANOTHER_VPN_ACTIVE, anotherVpnConflict.status)

    val activeEstablished =
      AndroidVpnFilterMonitor.computeSnapshot(
        activeCommitment = activeCommitment,
        hasConsented = true,
        stoppedByUser = false,
        datasets = DatasetSeedDefaults.defaultMetadata(),
        isPermissionPrepared = true,
        isStarting = false,
        isTunnelEstablished = true,
        permissionDenied = false,
        wasRevoked = false,
        anotherVpnActive = false,
        isPrivateDnsActive = false,
        upstreamDnsCount = 2,
        lastError = null,
        nowEpochMillis = 4L,
      )
    assertEquals(VpnServiceLifecycleStatus.ACTIVE, activeEstablished.status)
  }

  @Test
  fun `commitment activation respects consent and permission while completion and emergency termination stop VPN`() =
    runTest {
      val draft =
        CommitmentStateMachine.createDraft(
          id = CommitmentId("c-lifecycle-vpn"),
          rules =
            CommitmentRules(
              mode = RestrictionMode.STRICT,
              restrictedApps = emptySet(),
              websiteFilteringEnabled = true,
              blockedDomains = setOf(BlockedDomain(DomainName("social.example"), ruleType = DomainRuleType.BLOCK)),
            ),
          now = timeProvider.wallClock,
        )

      // 1. Activate WITHOUT consent -> VPN must NOT auto-start
      val repo1 = FakeCommitmentRepository(listOf(draft))
      val vpnMonitor1 = FakeVpnFilterMonitor()
      val reviewVm1 =
        CommitmentReviewViewModel(
          commitmentRepository = repo1,
          securityEventRepository = FakeSecurityEventRepository(),
          userPreferencesRepository = FakeUserPreferencesRepository(AppPreferences(websiteFilteringConsented = false)),
          vpnFilterMonitor = vpnMonitor1,
          timeProvider = timeProvider,
          idGenerator = FakeIdGenerator(),
          savedStateHandle = SavedStateHandle(mapOf("commitmentId" to draft.id.value)),
        )
      advanceUntilIdle()
      reviewVm1.setAgreementChecked(true)
      reviewVm1.activateCommitment()
      advanceUntilIdle()
      assertEquals(0, vpnMonitor1.startRequests)

      // 2. Activate WITH consent + permission -> VPN starts; then completion stops VPN
      val repo2 = FakeCommitmentRepository(listOf(draft))
      val vpnMonitor2 = FakeVpnFilterMonitor()
      val reviewVm2 =
        CommitmentReviewViewModel(
          commitmentRepository = repo2,
          securityEventRepository = FakeSecurityEventRepository(),
          userPreferencesRepository = FakeUserPreferencesRepository(AppPreferences(websiteFilteringConsented = true)),
          vpnFilterMonitor = vpnMonitor2,
          timeProvider = timeProvider,
          idGenerator = FakeIdGenerator(),
          savedStateHandle = SavedStateHandle(mapOf("commitmentId" to draft.id.value)),
        )
      advanceUntilIdle()
      reviewVm2.setAgreementChecked(true)
      reviewVm2.activateCommitment()
      advanceUntilIdle()
      assertEquals(1, vpnMonitor2.startRequests)

      val active =
        (repo2.getCommitment(draft.id) as PersistenceResult.Success).data
          as com.example.domain.model.Commitment.Active
      timeProvider.wallClock = active.endsAt
      val completionVm =
        CommitmentCompletionViewModel(
          commitmentRepository = repo2,
          securityEventRepository = FakeSecurityEventRepository(),
          vpnFilterMonitor = vpnMonitor2,
          timeProvider = timeProvider,
          idGenerator = FakeIdGenerator(),
          savedStateHandle = SavedStateHandle(mapOf("commitmentId" to active.id.value)),
        )
      advanceUntilIdle()
      assertEquals(1, vpnMonitor2.commitmentEndedCalls)

      // 3. Emergency termination also stops VPN cleanly
      val repo3 = FakeCommitmentRepository(listOf(active))
      val vpnMonitor3 = FakeVpnFilterMonitor()
      val emergencyVm =
        EmergencyTerminationViewModel(
          commitmentRepository = repo3,
          accountabilityMetadataRepository = com.example.testing.FakeAccountabilityMetadataRepository(),
          securityEventRepository = FakeSecurityEventRepository(),
          vpnFilterMonitor = vpnMonitor3,
          timeProvider = timeProvider,
          idGenerator = FakeIdGenerator(),
          savedStateHandle = SavedStateHandle(mapOf("commitmentId" to active.id.value)),
        )
      advanceUntilIdle()
      emergencyVm.setWarningConfirmed(true)
      emergencyVm.confirmTermination()
      advanceUntilIdle()
      assertEquals(1, vpnMonitor3.commitmentEndedCalls)
    }

  @Test
  fun `app restriction blocking and foreground usage tracking continue normally when VPN is denied or errored`() =
    runTest {
      val draft =
        CommitmentStateMachine.createDraft(
          id = CommitmentId("c-indep-1"),
          rules =
            CommitmentRules(
              mode = RestrictionMode.STRICT,
              restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Social")),
              websiteFilteringEnabled = true,
              blockedDomains = setOf(BlockedDomain(DomainName("social.example"), ruleType = DomainRuleType.BLOCK)),
            ),
          now = timeProvider.wallClock,
        )
      val active =
        (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
            as CommitmentResult.Success)
          .commitment
      val commitmentRepo = FakeCommitmentRepository(listOf(active))
      val dailyUsageRepo = FakeDailyUsageRepository()
      val vpnMonitor = FakeVpnFilterMonitor().apply { markPermissionDenied() }
      assertEquals(VpnServiceLifecycleStatus.PERMISSION_DENIED, vpnMonitor.vpnState.value.status)

      val blockingLauncher = FakeBlockingUiLauncher()
      val securityRepo = FakeSecurityEventRepository()
      val idGen = FakeIdGenerator()
      val dispatchers = FakeDispatcherProvider()
      val coordinator =
        AppRestrictionCoordinator(
          commitmentRepository = commitmentRepo,
          dailyUsageRepository = dailyUsageRepo,
          securityEventRepository = securityRepo,
          timeProvider = timeProvider,
          idGenerator = idGen,
          healthMonitor = FakeEnforcementHealthMonitor(),
          blockingUiLauncher = blockingLauncher,
          usageTracker =
            createTestUsageTracker(
              dailyUsageRepository = dailyUsageRepo,
              securityEventRepository = securityRepo,
              timeProvider = timeProvider,
              idGenerator = idGen,
              dispatcherProvider = dispatchers,
            ),
          usageReconciler =
            createTestUsageReconciler(
              dailyUsageRepository = dailyUsageRepo,
              timeProvider = timeProvider,
              dispatcherProvider = dispatchers,
            ),
          dispatcherProvider = dispatchers,
          ownPackageName = "com.ibnmisbah.mizan",
        )
      coordinator.onForegroundPackageObserved("com.example.social")
      assertEquals(1, blockingLauncher.requests.size)
      assertEquals("com.example.social", blockingLauncher.requests.single().packageName)
    }

  @Test
  fun `vpn health card gates start on consent displays warnings and shows stop when active`() {
    var consentClicks = 0
    var startClicks = 0
    var stopClicks = 0

    var cardSnapshot by
      mutableStateOf(
        VpnFilterSnapshot(
          status = VpnServiceLifecycleStatus.PERMISSION_REQUIRED,
          hasActiveWebsiteCommitment = true,
          hasConsented = false,
          isPrivateDnsActive = true,
          hasMissingOrIncompleteDataset = true,
          datasetWarningMessage = "Dataset missing warning",
          upstreamDnsCount = 1,
        )
      )

    composeRule.setContent {
      MizanTheme {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          VpnFilterHealthCard(
            vpnSnapshot = cardSnapshot,
            onConsentChanged = { consentClicks += 1 },
            onEnableAndStartVpn = { startClicks += 1 },
            onStopVpn = { stopClicks += 1 },
            testTagPrefix = "test_vpn",
          )
        }
      }
    }

    composeRule.onNodeWithTag("test_vpn_status_badge").assertTextContains("Permission Required")
    composeRule.onNodeWithTag("test_vpn_dataset_warning").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("test_vpn_private_dns_warning").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("test_vpn_start_button").performScrollTo().assertIsNotEnabled()
    composeRule.onNodeWithTag("test_vpn_consent_checkbox").performScrollTo().performClick()
    assertEquals(1, consentClicks)
    assertEquals(0, startClicks)

    cardSnapshot =
      cardSnapshot.copy(
        status = VpnServiceLifecycleStatus.ACTIVE,
        hasConsented = true,
        isPermissionPrepared = true,
        isTunnelEstablished = true,
      )
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("test_vpn_status_badge").performScrollTo().assertTextContains("Active")
    composeRule.onNodeWithTag("test_vpn_stop_button").performScrollTo().assertIsEnabled().performClick()
    assertEquals(1, stopClicks)
  }

  @Test
  fun `setup screen normalizes custom domain rules and catches invalid domain inputs`() = runTest {
    val repo = FakeCommitmentRepository()
    val viewModel =
      CommitmentsViewModel(
        commitmentRepository = repo,
        installedAppRepository = FakeInstalledAppRepository(),
        domainFilterRepository = FakeDomainFilterRepository(),
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
      )
    val collectJob = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    composeRule.setContent {
      MizanTheme {
        val state = viewModel.uiState.value
        if (state is CommitmentSetupUiState.Editing) {
          CommitmentsScreen(
            uiState = state,
            viewModel = viewModel,
            onNavigateToDashboard = {},
          )
        }
      }
    }

    viewModel.onCustomDomainInputChange("  Social.Example. ")
    viewModel.addCustomDomainRule(DomainRuleType.BLOCK)
    advanceUntilIdle()

    var editing = viewModel.uiState.value as CommitmentSetupUiState.Editing
    assertTrue(editing.editor.websiteFilteringEnabled)
    assertTrue(editing.editor.blockedDomains.any { it.domain.value == "social.example" && it.ruleType == DomainRuleType.BLOCK })

    viewModel.onCustomDomainInputChange("https://bad.url/path")
    viewModel.addCustomDomainRule(DomainRuleType.BLOCK)
    advanceUntilIdle()
    editing = viewModel.uiState.value as CommitmentSetupUiState.Editing
    assertTrue(editing.editor.validationMessages.isNotEmpty())

    collectJob.cancel()
  }
}
