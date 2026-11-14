package com.example.platform.filter

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.data.filter.DatasetFeedPolicy
import com.example.data.filter.DatasetFeedSourceSpec
import com.example.data.filter.EcdsaDomainDatasetSignatureVerifier
import com.example.data.filter.TrustedDatasetKeyConfig
import com.example.domain.model.AppPreferences
import com.example.domain.model.DatasetAvailabilityStatus
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DatasetStatusEvaluator
import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.repository.RemoteDatasetUpdateResult
import com.example.feature.settings.DatasetManagementItem
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsSummary
import com.example.feature.settings.SettingsUiState
import com.example.testing.FakeDatasetSyncController
import com.example.ui.theme.MizanTheme
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatasetStatusAndWorkerRobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `dataset status evaluator distinguishes available updating outdated unavailable and failed`() {
    val now = 1_800_000_000_000L
    val validMeta =
      FilterDatasetMetadata(
        datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
        category = DomainCategory.SOCIAL_MEDIA,
        sourceName = "Signed Mizan Feed",
        license = "CC-BY-4.0",
        versionCode = 3L,
        updatedAtEpochMillis = now - 5_000L,
        recordCount = 250,
        trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
        limitationsNote = "Domain-only.",
      )

    assertEquals(
      DatasetAvailabilityStatus.AVAILABLE,
      DatasetStatusEvaluator.evaluate(validMeta, isUpdating = false, nowEpochMillis = now),
    )
    assertEquals(
      DatasetAvailabilityStatus.UPDATING,
      DatasetStatusEvaluator.evaluate(validMeta, isUpdating = true, nowEpochMillis = now),
    )
    assertEquals(
      DatasetAvailabilityStatus.OUTDATED,
      DatasetStatusEvaluator.evaluate(
        validMeta.copy(updatedAtEpochMillis = now - DatasetStatusEvaluator.DEFAULT_OUTDATED_AFTER_MILLIS - 1L),
        isUpdating = false,
        nowEpochMillis = now,
      ),
    )
    assertEquals(
      DatasetAvailabilityStatus.FAILED,
      DatasetStatusEvaluator.evaluate(
        validMeta.copy(lastError = "Signature verification failed on newer manifest."),
        isUpdating = false,
        nowEpochMillis = now,
      ),
    )
    assertEquals(
      DatasetAvailabilityStatus.UNAVAILABLE,
      DatasetStatusEvaluator.evaluate(
        DatasetSeedDefaults.defaultMetadata().first(),
        isUpdating = false,
        nowEpochMillis = now,
      ),
    )
  }

  @Test
  fun `dataset update worker handles disabled transient retry permanent failure and success`() =
    runTest {
      val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
      val fakeController = FakeDatasetSyncController()

      val factory =
        object : WorkerFactory() {
          override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
          ): ListenableWorker = DatasetUpdateWorker(appContext, workerParameters, fakeController)
        }

      // 1. Disabled feed returns success cleanly
      val disabledWorker =
        TestListenableWorkerBuilder<DatasetUpdateWorker>(context)
          .setWorkerFactory(factory)
          .build()
      val disabledResult = disabledWorker.doWork()
      assertTrue(disabledResult is ListenableWorker.Result.Success)
      assertEquals(
        DatasetUpdateWorker.OUTCOME_DISABLED,
        (disabledResult as ListenableWorker.Result.Success).outputData.getString(
          DatasetUpdateWorker.KEY_OUTCOME
        ),
      )

      // 2. Transient network error -> Retry when below max attempts
      fakeController.nextCheckResults =
        mapOf(
          DatasetSeedDefaults.SOCIAL_DATASET_ID to
            RemoteDatasetUpdateResult.Failed("Offline", isTransientNetworkError = true)
        )
      val retryWorker =
        TestListenableWorkerBuilder<DatasetUpdateWorker>(context)
          .setWorkerFactory(factory)
          .setRunAttemptCount(0)
          .build()
      assertTrue(retryWorker.doWork() is ListenableWorker.Result.Retry)

      // 3. Permanent failure (invalid signature / corrupt manifest) -> Failure
      fakeController.nextCheckResults =
        mapOf(
          DatasetSeedDefaults.SOCIAL_DATASET_ID to
            RemoteDatasetUpdateResult.Failed("Bad ECDSA signature", isTransientNetworkError = false)
        )
      val failWorker =
        TestListenableWorkerBuilder<DatasetUpdateWorker>(context)
          .setWorkerFactory(factory)
          .setInputData(workDataOf(DatasetUpdateWorker.KEY_DATASET_ID to DatasetSeedDefaults.SOCIAL_DATASET_ID))
          .build()
      val failResult = failWorker.doWork()
      assertTrue(failResult is ListenableWorker.Result.Failure)

      // 4. Updated dataset -> Success with updated count
      val updatedMeta =
        FilterDatasetMetadata(
          datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
          category = DomainCategory.SOCIAL_MEDIA,
          sourceName = "Signed Feed",
          license = "CC-BY-4.0",
          versionCode = 2L,
          recordCount = 12,
          trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
          limitationsNote = "Domain-only.",
        )
      fakeController.nextCheckResults =
        mapOf(DatasetSeedDefaults.SOCIAL_DATASET_ID to RemoteDatasetUpdateResult.Updated(updatedMeta))
      val okWorker =
        TestListenableWorkerBuilder<DatasetUpdateWorker>(context)
          .setWorkerFactory(factory)
          .build()
      val okResult = okWorker.doWork()
      assertTrue(okResult is ListenableWorker.Result.Success)
      assertEquals(
        1,
        (okResult as ListenableWorker.Result.Success).outputData.getInt(
          DatasetUpdateWorker.KEY_UPDATED_COUNT,
          0,
        ),
      )
    }

  @Test
  fun `work manager scheduler cancels legacy and per-category jobs when unconfigured`() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
    val config = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    val workManager = WorkManager.getInstance(context)

    val noKeyVerifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig(null))
    val unconfiguredScheduler =
      WorkManagerDatasetUpdateScheduler(
        context = context,
        feedPolicy = DatasetFeedPolicy(remoteUpdatesEnabled = false),
        signatureVerifier = noKeyVerifier,
      )
    unconfiguredScheduler.syncPeriodicSchedule(wifiOnly = true)
    unconfiguredScheduler.enqueueOneTimeUpdate(wifiOnly = true)

    assertTrue(
      workManager
        .getWorkInfosForUniqueWork(WorkManagerDatasetUpdateScheduler.UNIQUE_PERIODIC_WORK_NAME)
        .get()
        .isEmpty()
    )
    assertTrue(
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.periodicWorkNameFor(DatasetSeedDefaults.ADULT_DATASET_ID)
        )
        .get()
        .isEmpty()
    )
    assertTrue(
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.periodicWorkNameFor(DatasetSeedDefaults.SOCIAL_DATASET_ID)
        )
        .get()
        .isEmpty()
    )
    assertTrue(
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.oneTimeWorkNameFor(DatasetSeedDefaults.ADULT_DATASET_ID)
        )
        .get()
        .isEmpty()
    )
    assertTrue(
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.oneTimeWorkNameFor(DatasetSeedDefaults.SOCIAL_DATASET_ID)
        )
        .get()
        .isEmpty()
    )

    // Even when feeds are configured, missing pinned key keeps all periodic/manual jobs disabled
    val configuredPolicyMissingKey =
      DatasetFeedPolicy(
        remoteUpdatesEnabled = true,
        sources =
          mapOf(
            DatasetSeedDefaults.ADULT_DATASET_ID to
              DatasetFeedSourceSpec(
                datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
                category = DomainCategory.ADULT_CONTENT,
                manifestUrl = "https://feeds.example.invalid/adult/manifest.json",
                sourceName = "Signed Adult Feed",
                license = "CC-BY-4.0",
              ),
            DatasetSeedDefaults.SOCIAL_DATASET_ID to
              DatasetFeedSourceSpec(
                datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
                category = DomainCategory.SOCIAL_MEDIA,
                manifestUrl = "https://feeds.example.invalid/social/manifest.json",
                sourceName = "Signed Social Feed",
                license = "CC-BY-4.0",
              ),
          ),
      )
    val missingKeyScheduler =
      WorkManagerDatasetUpdateScheduler(
        context = context,
        feedPolicy = configuredPolicyMissingKey,
        signatureVerifier = noKeyVerifier,
      )
    missingKeyScheduler.syncPeriodicSchedule(wifiOnly = false)
    missingKeyScheduler.enqueueOneTimeUpdate(wifiOnly = false)
    assertTrue(
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.periodicWorkNameFor(DatasetSeedDefaults.ADULT_DATASET_ID)
        )
        .get()
        .isEmpty()
    )
    assertTrue(
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.periodicWorkNameFor(DatasetSeedDefaults.SOCIAL_DATASET_ID)
        )
        .get()
        .isEmpty()
    )
  }

  @Test
  fun `work manager scheduler enforces category-specific constraints for adult cellular and social wifi-only`() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
    val config = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    val workManager = WorkManager.getInstance(context)

    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val keyPair = generator.generateKeyPair()
    val configuredVerifier =
      EcdsaDomainDatasetSignatureVerifier(
        TrustedDatasetKeyConfig(
          EcdsaDomainDatasetSignatureVerifier.encodePublicKeySpkiBase64(keyPair.public)
        )
      )
    val configuredPolicy =
      DatasetFeedPolicy(
        remoteUpdatesEnabled = true,
        sources =
          mapOf(
            DatasetSeedDefaults.ADULT_DATASET_ID to
              DatasetFeedSourceSpec(
                datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
                category = DomainCategory.ADULT_CONTENT,
                manifestUrl = "https://feeds.example.invalid/adult/manifest.json",
                sourceName = "Signed Adult Feed",
                license = "CC-BY-4.0",
              ),
            DatasetSeedDefaults.SOCIAL_DATASET_ID to
              DatasetFeedSourceSpec(
                datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
                category = DomainCategory.SOCIAL_MEDIA,
                manifestUrl = "https://feeds.example.invalid/social/manifest.json",
                sourceName = "Signed Social Feed",
                license = "CC-BY-4.0",
              ),
          ),
      )
    val configuredScheduler =
      WorkManagerDatasetUpdateScheduler(
        context = context,
        feedPolicy = configuredPolicy,
        signatureVerifier = configuredVerifier,
      )

    // With wifiOnly preference enabled: ADULT_CONTENT uses CONNECTED (allows cellular + Wi-Fi),
    // while SOCIAL_MEDIA enforces UNMETERED (Wi-Fi only).
    configuredScheduler.syncPeriodicSchedule(wifiOnly = true)

    assertTrue(
      workManager
        .getWorkInfosForUniqueWork(WorkManagerDatasetUpdateScheduler.UNIQUE_PERIODIC_WORK_NAME)
        .get()
        .isEmpty()
    )

    val adultPeriodicWifiOn =
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.periodicWorkNameFor(DatasetSeedDefaults.ADULT_DATASET_ID)
        )
        .get()
    assertEquals(1, adultPeriodicWifiOn.size)
    assertEquals(WorkInfo.State.ENQUEUED, adultPeriodicWifiOn.first().state)
    assertEquals(
      NetworkType.CONNECTED,
      adultPeriodicWifiOn.first().constraints.requiredNetworkType,
    )
    assertTrue(adultPeriodicWifiOn.first().constraints.requiresBatteryNotLow())

    val socialPeriodicWifiOn =
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.periodicWorkNameFor(DatasetSeedDefaults.SOCIAL_DATASET_ID)
        )
        .get()
    assertEquals(1, socialPeriodicWifiOn.size)
    assertEquals(WorkInfo.State.ENQUEUED, socialPeriodicWifiOn.first().state)
    assertEquals(
      NetworkType.UNMETERED,
      socialPeriodicWifiOn.first().constraints.requiredNetworkType,
    )
    assertTrue(socialPeriodicWifiOn.first().constraints.requiresBatteryNotLow())

    // One-time jobs also preserve category-specific constraints and target datasetId input data
    configuredScheduler.enqueueOneTimeUpdate(wifiOnly = true)
    val adultOneTime =
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.oneTimeWorkNameFor(DatasetSeedDefaults.ADULT_DATASET_ID)
        )
        .get()
    val socialOneTime =
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.oneTimeWorkNameFor(DatasetSeedDefaults.SOCIAL_DATASET_ID)
        )
        .get()
    assertEquals(1, adultOneTime.size)
    assertEquals(NetworkType.CONNECTED, adultOneTime.first().constraints.requiredNetworkType)
    assertEquals(1, socialOneTime.size)
    assertEquals(NetworkType.UNMETERED, socialOneTime.first().constraints.requiredNetworkType)

    // With wifiOnly preference disabled: SOCIAL_MEDIA transitions cleanly to CONNECTED without duplicates
    configuredScheduler.syncPeriodicSchedule(wifiOnly = false)
    val adultPeriodicWifiOff =
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.periodicWorkNameFor(DatasetSeedDefaults.ADULT_DATASET_ID)
        )
        .get()
    val socialPeriodicWifiOff =
      workManager
        .getWorkInfosForUniqueWork(
          WorkManagerDatasetUpdateScheduler.periodicWorkNameFor(DatasetSeedDefaults.SOCIAL_DATASET_ID)
        )
        .get()
    assertEquals(1, adultPeriodicWifiOff.size)
    assertEquals(
      NetworkType.CONNECTED,
      adultPeriodicWifiOff.first().constraints.requiredNetworkType,
    )
    assertEquals(1, socialPeriodicWifiOff.size)
    assertEquals(
      NetworkType.CONNECTED,
      socialPeriodicWifiOff.first().constraints.requiredNetworkType,
    )
  }

  @Test
  fun `sync controller treats adult cellular update plus social wifi-only deferral as non-fatal status`() =
    runTest {
      val adultMeta =
        FilterDatasetMetadata(
          datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
          category = DomainCategory.ADULT_CONTENT,
          sourceName = "Signed Adult Feed",
          license = "CC-BY-4.0",
          versionCode = 3L,
          recordCount = 100,
          trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
          limitationsNote = "Domain-only.",
        )
      val fakeUpdater =
        object : com.example.domain.repository.RemoteDatasetUpdater {
          override suspend fun checkAndUpdate(datasetId: String): RemoteDatasetUpdateResult =
            when (datasetId) {
              DatasetSeedDefaults.ADULT_DATASET_ID -> RemoteDatasetUpdateResult.Updated(adultMeta)
              else ->
                RemoteDatasetUpdateResult.Failed(
                  reason = "Wi-Fi-only update policy active and current network is metered; update deferred.",
                  isTransientNetworkError = true,
                )
            }

          override suspend fun checkAndUpdateAll(): Map<String, RemoteDatasetUpdateResult> =
            linkedMapOf(
              DatasetSeedDefaults.ADULT_DATASET_ID to checkAndUpdate(DatasetSeedDefaults.ADULT_DATASET_ID),
              DatasetSeedDefaults.SOCIAL_DATASET_ID to checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID),
            )
        }
      val fakeRepo = com.example.testing.FakeDomainFilterRepository()
      val controller =
        DefaultDatasetSyncController(
          remoteDatasetUpdater = fakeUpdater,
          signedLocalDatasetImporter = com.example.data.filter.SignedLocalDatasetImporter(fakeRepo),
          domainFilterRepository = fakeRepo,
          userPreferencesRepository = com.example.testing.FakeUserPreferencesRepository(),
          datasetUpdateScheduler =
            object : DatasetUpdateScheduler {
              override fun syncPeriodicSchedule(wifiOnly: Boolean) = Unit
              override fun enqueueOneTimeUpdate(wifiOnly: Boolean, datasetId: String?) = Unit
            },
          feedPolicy = DatasetFeedPolicy(remoteUpdatesEnabled = true),
        )

      controller.runUpdateCheck(targetDatasetId = null)
      val snapshot = controller.syncState.value
      assertTrue(snapshot.statusMessage?.contains("Updated 1 verified filter dataset") == true)
      assertTrue(snapshot.statusMessage?.contains("deferred") == true)
      assertEquals(null, snapshot.errorMessage)
    }

  @Test
  fun `settings screen renders dataset statuses rollback button limitations and action callbacks`() {
    var checkUpdatesClicks = 0
    var importClicks = 0
    var rolledBackId: String? = null

    val adultMeta =
      FilterDatasetMetadata(
        datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
        category = DomainCategory.ADULT_CONTENT,
        sourceName = "Signed Adult Feed",
        license = "CC-BY-4.0",
        versionCode = 4L,
        updatedAtEpochMillis = 1_800_000_000_000L,
        recordCount = 500,
        trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
        lastError = "New v5 signature rejected.",
        limitationsNote = "Domain-only.",
        previousVersionCode = 3L,
      )
    val socialMeta =
      FilterDatasetMetadata(
        datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
        category = DomainCategory.SOCIAL_MEDIA,
        sourceName = "Signed Social Feed",
        license = "CC-BY-4.0",
        versionCode = 2L,
        updatedAtEpochMillis = 1_795_000_000_000L,
        recordCount = 80,
        trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
        limitationsNote = "Domain-only.",
      )

    composeRule.setContent {
      MizanTheme {
        SettingsScreen(
          uiState =
            SettingsUiState.Ready(
              SettingsSummary(
                preferences = AppPreferences(),
                applicationId = "com.ibnmisbah.mizan",
                versionName = "1.0",
                datasets = listOf(adultMeta, socialMeta),
                datasetItems =
                  listOf(
                    DatasetManagementItem(adultMeta, DatasetAvailabilityStatus.FAILED),
                    DatasetManagementItem(socialMeta, DatasetAvailabilityStatus.OUTDATED),
                  ),
                datasetSyncSnapshot =
                  DatasetSyncUiSnapshot(statusMessage = "Using cached v4 adult dataset offline."),
              )
            ),
          onSelectTheme = {},
          onToggleWifiOnly = {},
          onRevisitOnboarding = {},
          onCheckDatasetUpdates = { checkUpdatesClicks += 1 },
          onImportSignedDataset = { importClicks += 1 },
          onRollbackDataset = { rolledBackId = it },
        )
      }
    }

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_filter_pref_card"))
    composeRule
      .onNodeWithTag("settings_dataset_status_${adultMeta.datasetId}")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("Failed")
    composeRule
      .onNodeWithTag("settings_dataset_rollback_${adultMeta.datasetId}")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()
    assertEquals(adultMeta.datasetId, rolledBackId)

    composeRule
      .onNodeWithTag("settings_dataset_status_${socialMeta.datasetId}")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("Outdated")

    composeRule
      .onNodeWithTag("settings_dataset_check_updates_button")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()
    composeRule
      .onNodeWithTag("settings_dataset_import_button")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()
    composeRule
      .onNodeWithTag("settings_dataset_sync_feedback")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("Using cached v4 adult dataset offline.")
    composeRule
      .onNodeWithTag("settings_dataset_limitations_note")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("Private DNS", substring = true)
      .assertTextContains("DNS-over-HTTPS", substring = true)
      .assertTextContains("IPv6", substring = true)
      .assertTextContains("another active VPN", substring = true)
    assertEquals(1, checkUpdatesClicks)
    assertEquals(1, importClicks)
  }

  @Test
  fun `settings screen renders available updating and unavailable dataset badges`() {
    val availableMeta =
      FilterDatasetMetadata(
        datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
        category = DomainCategory.ADULT_CONTENT,
        sourceName = "Signed Adult Feed",
        license = "CC-BY-4.0",
        versionCode = 10L,
        updatedAtEpochMillis = 1_800_000_000_000L,
        recordCount = 1200,
        trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
        limitationsNote = "Domain-only.",
      )
    val unavailableMeta = DatasetSeedDefaults.defaultMetadata().last()

    composeRule.setContent {
      MizanTheme {
        SettingsScreen(
          uiState =
            SettingsUiState.Ready(
              SettingsSummary(
                preferences = AppPreferences(),
                applicationId = "com.ibnmisbah.mizan",
                versionName = "1.0",
                datasets = listOf(availableMeta, unavailableMeta),
                datasetItems =
                  listOf(
                    DatasetManagementItem(availableMeta, DatasetAvailabilityStatus.UPDATING),
                    DatasetManagementItem(unavailableMeta, DatasetAvailabilityStatus.UNAVAILABLE),
                  ),
                datasetSyncSnapshot =
                  DatasetSyncUiSnapshot(updatingDatasetIds = setOf(availableMeta.datasetId)),
              )
            ),
          onSelectTheme = {},
          onToggleWifiOnly = {},
          onRevisitOnboarding = {},
        )
      }
    }

    composeRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_filter_pref_card"))
    composeRule
      .onNodeWithTag("settings_dataset_status_${availableMeta.datasetId}")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("Updating…")
    composeRule
      .onNodeWithTag("settings_dataset_status_${unavailableMeta.datasetId}")
      .performScrollTo()
      .assertIsDisplayed()
      .assertTextContains("Unavailable")
  }
}
