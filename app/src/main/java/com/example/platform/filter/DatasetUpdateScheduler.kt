package com.example.platform.filter

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.filter.DatasetFeedPolicy
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.repository.DomainDatasetSignatureVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface DatasetUpdateScheduler {
  fun syncPeriodicSchedule(wifiOnly: Boolean)

  fun enqueueOneTimeUpdate(wifiOnly: Boolean, datasetId: String? = null)
}

@Singleton
class WorkManagerDatasetUpdateScheduler
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val feedPolicy: DatasetFeedPolicy,
  private val signatureVerifier: DomainDatasetSignatureVerifier,
) : DatasetUpdateScheduler {

  override fun syncPeriodicSchedule(wifiOnly: Boolean) {
    val workManager = WorkManager.getInstance(context)
    // Remove any legacy shared periodic job from earlier versions to prevent duplicate runs.
    workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)

    val managedIds =
      (DatasetFeedPolicy.defaultUnconfiguredSources().keys + feedPolicy.sources.keys).distinct()

    for (datasetId in managedIds) {
      val workName = periodicWorkNameFor(datasetId)
      val spec = feedPolicy.sourceFor(datasetId)
      if (
        !feedPolicy.remoteUpdatesEnabled ||
          !signatureVerifier.isTrustedKeyConfigured ||
          spec == null ||
          !feedPolicy.isFeedConfiguredFor(datasetId)
      ) {
        workManager.cancelUniqueWork(workName)
      } else {
        val effectiveWifiOnly = spec.requiresWifiOnly(wifiOnlyPreference = wifiOnly)
        workManager.enqueueUniquePeriodicWork(
          workName,
          ExistingPeriodicWorkPolicy.UPDATE,
          buildPeriodicRequest(wifiOnly = effectiveWifiOnly, datasetId = datasetId),
        )
      }
    }
  }

  override fun enqueueOneTimeUpdate(wifiOnly: Boolean, datasetId: String?) {
    val workManager = WorkManager.getInstance(context)
    val managedIds =
      if (datasetId != null) {
        listOf(datasetId)
      } else {
        (DatasetFeedPolicy.defaultUnconfiguredSources().keys + feedPolicy.sources.keys).distinct()
      }

    for (targetId in managedIds) {
      val workName = oneTimeWorkNameFor(targetId)
      val spec = feedPolicy.sourceFor(targetId)
      if (
        !feedPolicy.remoteUpdatesEnabled ||
          !signatureVerifier.isTrustedKeyConfigured ||
          spec == null ||
          !feedPolicy.isFeedConfiguredFor(targetId)
      ) {
        workManager.cancelUniqueWork(workName)
      } else {
        val effectiveWifiOnly = spec.requiresWifiOnly(wifiOnlyPreference = wifiOnly)
        workManager.enqueueUniqueWork(
          workName,
          ExistingWorkPolicy.REPLACE,
          buildOneTimeRequest(wifiOnly = effectiveWifiOnly, datasetId = targetId),
        )
      }
    }
  }

  companion object {
    const val UNIQUE_PERIODIC_WORK_NAME = "mizan_periodic_dataset_update"
    const val UNIQUE_ONE_TIME_WORK_NAME = "mizan_manual_dataset_update"
    const val PERIODIC_INTERVAL_DAYS = 7L

    fun periodicWorkNameFor(datasetId: String): String =
      when (datasetId) {
        DatasetSeedDefaults.ADULT_DATASET_ID -> "${UNIQUE_PERIODIC_WORK_NAME}_adult_content"
        DatasetSeedDefaults.SOCIAL_DATASET_ID -> "${UNIQUE_PERIODIC_WORK_NAME}_social_media"
        else -> "${UNIQUE_PERIODIC_WORK_NAME}_$datasetId"
      }

    fun oneTimeWorkNameFor(datasetId: String): String =
      when (datasetId) {
        DatasetSeedDefaults.ADULT_DATASET_ID -> "${UNIQUE_ONE_TIME_WORK_NAME}_adult_content"
        DatasetSeedDefaults.SOCIAL_DATASET_ID -> "${UNIQUE_ONE_TIME_WORK_NAME}_social_media"
        else -> "${UNIQUE_ONE_TIME_WORK_NAME}_$datasetId"
      }

    fun buildConstraints(wifiOnly: Boolean, requireBatteryNotLow: Boolean): Constraints =
      Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(requireBatteryNotLow)
        .build()

    fun buildPeriodicRequest(
      wifiOnly: Boolean,
      datasetId: String? = null,
    ): PeriodicWorkRequest =
      PeriodicWorkRequestBuilder<DatasetUpdateWorker>(PERIODIC_INTERVAL_DAYS, TimeUnit.DAYS)
        .setConstraints(buildConstraints(wifiOnly = wifiOnly, requireBatteryNotLow = true))
        .setInputData(workDataOf(DatasetUpdateWorker.KEY_DATASET_ID to datasetId))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
        .build()

    fun buildOneTimeRequest(wifiOnly: Boolean, datasetId: String? = null): OneTimeWorkRequest =
      OneTimeWorkRequestBuilder<DatasetUpdateWorker>()
        .setConstraints(buildConstraints(wifiOnly = wifiOnly, requireBatteryNotLow = false))
        .setInputData(workDataOf(DatasetUpdateWorker.KEY_DATASET_ID to datasetId))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
        .build()
  }
}
