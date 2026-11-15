package com.example.platform

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.MainDispatcherRule
import com.example.R
import com.example.core.common.DispatcherProvider
import com.example.core.designsystem.component.AccessibilityHealthCard
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.InstalledAppsResult
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import com.example.feature.blocking.BlockingActivity
import com.example.feature.commitments.CommitmentSetupUiState
import com.example.feature.commitments.CommitmentsScreen
import com.example.feature.commitments.CommitmentsViewModel
import com.example.platform.accessibility.AppRestrictionAccessibilityService
import com.example.platform.apps.PackageManagerInstalledAppRepository
import com.example.platform.health.AccessibilityPermissionStatus
import com.example.platform.health.AndroidEnforcementHealthMonitor
import com.example.platform.health.EnforcementHealthSnapshot
import com.example.platform.health.EnforcementHealthStatus
import com.example.testing.FakeAccountabilityMetadataRepository
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeDomainFilterRepository
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeInstalledAppRepository
import com.example.testing.FakeTimeProvider
import com.example.ui.theme.MizanTheme
import com.example.waitUntilTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MizanPlatformRobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `manifest declares accessibility service with minimal Shorts-aware window retrieval and without restricted broad permissions`() {
    val pm = context.packageManager
    val serviceComponent =
      ComponentName(context.packageName, AppRestrictionAccessibilityService::class.java.name)
    val serviceInfo =
      pm.getServiceInfo(serviceComponent, PackageManager.GET_META_DATA)

    assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, serviceInfo.permission)
    assertFalse(serviceInfo.exported)
    assertNotNull(serviceInfo.metaData)
    val xmlResId = serviceInfo.metaData.getInt("android.accessibilityservice")
    assertTrue(xmlResId != 0)

    val attrs = readAccessibilityXmlAttributes(xmlResId)
    assertEquals("true", attrs["canRetrieveWindowContent"])
    val eventMask = Integer.decode(attrs.getValue("accessibilityEventTypes"))
    assertEquals(
      android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      eventMask,
    )
    val flagMask = Integer.decode(attrs.getValue("accessibilityFlags"))
    assertTrue(
      (flagMask and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0
    )
    assertEquals(
      0,
      flagMask and
        android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS,
    )

    val pkgInfo =
      pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    val requestedPermissions = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertFalse(Manifest.permission.QUERY_ALL_PACKAGES in requestedPermissions)
    assertFalse(Manifest.permission.SYSTEM_ALERT_WINDOW in requestedPermissions)
  }

  @Test
  fun `manifest declares BlockingActivity as non-exported singleTop excluded from recents`() {
    val blockingComponent = ComponentName(context.packageName, BlockingActivity::class.java.name)
    val activityInfo = context.packageManager.getActivityInfo(blockingComponent, 0)

    assertFalse(activityInfo.exported)
    assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activityInfo.launchMode)
    assertTrue((activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0)
  }

  @Test
  fun `enforcement health monitor transitions across disabled disconnected active and unavailable and builds settings intent`() {
    val monitor = AndroidEnforcementHealthMonitor(context, FakeTimeProvider())

    assertEquals(EnforcementHealthStatus.DISABLED, monitor.healthState.value.healthStatus)
    assertEquals(
      Settings.ACTION_ACCESSIBILITY_SETTINGS,
      monitor.createAccessibilitySettingsIntent().action,
    )

    val component =
      ComponentName(context.packageName, AppRestrictionAccessibilityService::class.java.name)
    Settings.Secure.putString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      component.flattenToString(),
    )
    monitor.refreshSystemStatus()

    assertEquals(
      AccessibilityPermissionStatus.ENABLED,
      monitor.healthState.value.permissionStatus,
    )
    assertEquals(
      EnforcementHealthStatus.ENABLED_SERVICE_DISCONNECTED,
      monitor.healthState.value.healthStatus,
    )

    monitor.setServiceConnected(true)
    assertEquals(EnforcementHealthStatus.ACTIVE, monitor.healthState.value.healthStatus)

    monitor.setRuleStateAvailable(false)
    assertEquals(EnforcementHealthStatus.UNAVAILABLE, monitor.healthState.value.healthStatus)
  }

  @Test
  fun `installed app repository discovers launchable apps while excluding mizan and home launcher`() =
    runTest {
      val shadowPm = shadowOf(context.packageManager)
      val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

      // External launchable app
      shadowPm.addResolveInfoForIntent(
        launcherIntent,
        createFakeResolveInfo(
          packageName = "com.example.socialsample",
          activityName = "com.example.socialsample.MainActivity",
          label = "Social Sample",
        ),
      )
      // MIZAN itself in launcher list (must be excluded)
      shadowPm.addResolveInfoForIntent(
        launcherIntent,
        createFakeResolveInfo(
          packageName = context.packageName,
          activityName = "${context.packageName}.MainActivity",
          label = "MIZAN",
        ),
      )
      // Home launcher appearing under both HOME and LAUNCHER (must be excluded)
      val homeResolveInfo =
        createFakeResolveInfo(
          packageName = "com.example.homelauncher",
          activityName = "com.example.homelauncher.HomeActivity",
          label = "System Home",
        )
      shadowPm.addResolveInfoForIntent(homeIntent, homeResolveInfo)
      shadowPm.addResolveInfoForIntent(launcherIntent, homeResolveInfo)

      val repo =
        PackageManagerInstalledAppRepository(
          context = context,
          dispatcherProvider = FakeDispatcherProvider() as DispatcherProvider,
        )
      val result = repo.getLaunchableApps() as InstalledAppsResult.Success

      val social = result.apps.firstOrNull { it.appId.packageName == "com.example.socialsample" }
      assertNotNull(social)
      assertEquals("Social Sample", social?.displayLabel)
      assertFalse(result.apps.any { it.appId.packageName == context.packageName })
      assertFalse(result.apps.any { it.appId.packageName == "com.example.homelauncher" })
    }

  @Test
  fun `accessibility health card renders distinct states without false active claim`() {
    var openSettingsClicks = 0
    val disabledState =
      EnforcementHealthSnapshot(
        permissionStatus = AccessibilityPermissionStatus.DISABLED,
        isServiceConnected = false,
        ruleStateAvailable = true,
        healthStatus = EnforcementHealthStatus.DISABLED,
      )

    composeRule.setContent {
      MizanTheme {
        AccessibilityHealthCard(
          healthSnapshot = disabledState,
          onOpenAccessibilitySettings = { openSettingsClicks += 1 },
          testTagPrefix = "test_health",
        )
      }
    }

    composeRule.onNodeWithTag("test_health_status_badge").assertTextContains("Accessibility Disabled")
    composeRule
      .onNodeWithTag("test_health_open_accessibility_settings_button")
      .assertIsDisplayed()
      .performClick()
    assertEquals(1, openSettingsClicks)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `setup handles stale uninstalled selection gracefully without crashing`() = runTest {
    val timeProvider = FakeTimeProvider()
    val staleDraft =
      CommitmentStateMachine.createDraft(
        id = CommitmentId("draft-stale"),
        rules =
          CommitmentRules(
            mode = RestrictionMode.STRICT,
            restrictedApps = setOf(RestrictedApp(AppId("com.removed.social"), "Removed App")),
          ),
        now = timeProvider.wallClock,
      )
    val installedApp =
      InstalledAppInfo(
        appId = AppId("com.installed.reader"),
        displayLabel = "Installed Reader",
      )
    val viewModel =
      CommitmentsViewModel(
        commitmentRepository = FakeCommitmentRepository(listOf(staleDraft)),
        installedAppRepository = FakeInstalledAppRepository(InstalledAppsResult.Success(listOf(installedApp))),
        domainFilterRepository = FakeDomainFilterRepository(),
        accountabilityMetadataRepository = FakeAccountabilityMetadataRepository(),
        enforcementHealthMonitor = FakeEnforcementHealthMonitor(),
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
      )
    val collectJob = launch { viewModel.uiState.collect {} }
    advanceUntilIdle()

    val editingState = viewModel.uiState.value as CommitmentSetupUiState.Editing
    composeRule.setContent {
      MizanTheme {
        CommitmentsScreen(
          uiState = editingState,
          viewModel = viewModel,
          onNavigateToDashboard = {},
        )
      }
    }

    composeRule
      .onNodeWithTag("commitments_screen_list")
      .performScrollToNode(hasTestTag("setup_selected_restricted_com.removed.social"))
    composeRule
      .onNodeWithTag("setup_selected_restricted_com.removed.social")
      .assertTextContains("Not currently installed", substring = true)
    collectJob.cancel()
  }

  private fun readAccessibilityXmlAttributes(xmlResId: Int): Map<String, String> {
    val parser = context.resources.getXml(xmlResId)
    val result = mutableMapOf<String, String>()
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
      if (eventType == XmlPullParser.START_TAG && parser.name == "accessibility-service") {
        for (i in 0 until parser.attributeCount) {
          result[parser.getAttributeName(i)] = parser.getAttributeValue(i)
        }
      }
      eventType = parser.next()
    }
    parser.close()
    return result
  }

  private fun createFakeResolveInfo(
    packageName: String,
    activityName: String,
    label: String,
  ): android.content.pm.ResolveInfo {
    val appInfo =
      ApplicationInfo().apply {
        this.packageName = packageName
        this.name = label
      }
    val activityInfo =
      ActivityInfo().apply {
        this.packageName = packageName
        this.name = activityName
        this.applicationInfo = appInfo
        this.exported = true
        this.nonLocalizedLabel = label
      }
    return android.content.pm.ResolveInfo().apply {
      this.activityInfo = activityInfo
      this.nonLocalizedLabel = label
    }
  }
}

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = HiltTestApplication::class)
class BlockingActivityRobolectricTest {

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val composeRule = androidx.compose.ui.test.junit4.createEmptyComposeRule()

  @Inject lateinit var commitmentRepository: CommitmentRepository

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(9, 0, 0),
      epochMillis = 1_794_560_400_000L,
    )

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  @Test
  fun `blocking activity renders blocked app navigates home cleanly and dismisses on commitment termination`() {
    val draft =
      CommitmentStateMachine.createDraft(
        id = CommitmentId("c-block-1"),
        rules =
          CommitmentRules(
            mode = RestrictionMode.STRICT,
            restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Sample Social")),
          ),
        now = baseTime,
      )
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseTime)
          as CommitmentResult.Success)
        .commitment
    kotlinx.coroutines.runBlocking { commitmentRepository.saveCommitment(active) }

    val app = ApplicationProvider.getApplicationContext<Application>()
    val intent =
      Intent(app, BlockingActivity::class.java).apply {
        putExtra(BlockingActivity.EXTRA_BLOCKED_PACKAGE, "com.example.social")
        putExtra(BlockingActivity.EXTRA_BLOCKED_APP_LABEL, "Sample Social")
      }

    ActivityScenario.launch<BlockingActivity>(intent).use { scenario ->
      composeRule.waitUntilTag("blocking_main_card")
      composeRule.onNodeWithTag("blocking_app_title").assertTextContains("Sample Social")
      composeRule.onNodeWithTag("blocking_reason_text").assertIsDisplayed()

      // Terminating active commitment dismisses BlockingActivity cleanly
      val terminated =
        (CommitmentStateMachine.emergencyTerminate(
            commitment = active,
            confirmedWarning = true,
            pinVerification = com.example.domain.lifecycle.PinVerificationResult.NOT_PROVIDED,
            now = baseTime,
          ) as CommitmentResult.Success)
          .commitment
      kotlinx.coroutines.runBlocking { commitmentRepository.saveCommitment(terminated) }
      composeRule.waitUntil(timeoutMillis = 5_000L) {
        var finishing = false
        scenario.onActivity { activity -> finishing = activity.isFinishing }
        finishing
      }
      scenario.onActivity { activity -> assertTrue(activity.isFinishing) }
    }
  }

  @Test
  fun `return to home button launches home launcher and finishes blocking activity`() {
    val draft =
      CommitmentStateMachine.createDraft(
        id = CommitmentId("c-block-2"),
        rules =
          CommitmentRules(
            mode = RestrictionMode.STRICT,
            restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Sample Social")),
          ),
        now = baseTime,
      )
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseTime)
          as CommitmentResult.Success)
        .commitment
    kotlinx.coroutines.runBlocking { commitmentRepository.saveCommitment(active) }

    val app = ApplicationProvider.getApplicationContext<Application>()
    val intent =
      Intent(app, BlockingActivity::class.java).apply {
        putExtra(BlockingActivity.EXTRA_BLOCKED_PACKAGE, "com.example.social")
        putExtra(BlockingActivity.EXTRA_BLOCKED_APP_LABEL, "Sample Social")
      }

    ActivityScenario.launch<BlockingActivity>(intent).use { scenario ->
      composeRule.waitUntilTag("blocking_return_home_button")
      composeRule.onNodeWithTag("blocking_return_home_button").performClick()
      composeRule.waitForIdle()

      val startedIntent = shadowOf(app).nextStartedActivity
      assertNotNull(startedIntent)
      assertEquals(Intent.ACTION_MAIN, startedIntent.action)
      assertTrue(startedIntent.hasCategory(Intent.CATEGORY_HOME))
      scenario.onActivity { activity -> assertTrue(activity.isFinishing) }
    }
  }
}
