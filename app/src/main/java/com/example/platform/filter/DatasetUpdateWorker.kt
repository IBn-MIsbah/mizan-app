package com.example.platform.filter

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.domain.repository.RemoteDatasetUpdateResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Deferrable background worker that refreshes signed domain filter datasets when network
 * constraints (including optional Wi-Fi-only preference) are satisfied.
 *
 * Offline-first guarantee: failure or deferral never disables existing locally cached datasets or
 * custom user-defined domains.
 */
@HiltWorker
class DatasetUpdateWorker
@AssistedInject
constructor(
  @Assisted appContext: Context,
  @Assisted workerParams: WorkerParameters,
  private val syncController: DatasetSyncController,
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val targetDatasetId = inputData.getString(KEY_DATASET_ID)?.takeIf { it.isNotBlank() }
    val outcomes =
      try {
        syncController.runUpdateCheck(targetDatasetId)
      } catch (t: Exception) {
        return if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
          Result.retry()
        } else {
          Result.failure(
            workDataOf(
              KEY_OUTCOME to OUTCOME_FAILED,
              KEY_ERROR_REASON to (t.message ?: "Unexpected dataset update worker error."),
            )
          )
        }
      }

    val results = outcomes.values.toList()
    if (results.isEmpty() || results.all { it is RemoteDatasetUpdateResult.Disabled }) {
      return Result.success(workDataOf(KEY_OUTCOME to OUTCOME_DISABLED))
    }

    val transientFailure =
      results.firstOrNull { it is RemoteDatasetUpdateResult.Failed && it.isTransientNetworkError }
        as? RemoteDatasetUpdateResult.Failed
    if (transientFailure != null && runAttemptCount < MAX_RETRY_ATTEMPTS) {
      return Result.retry()
    }

    val permanentFailure =
      results.firstOrNull { it is RemoteDatasetUpdateResult.Failed }
        as? RemoteDatasetUpdateResult.Failed
    if (permanentFailure != null) {
      return Result.failure(
        workDataOf(
          KEY_OUTCOME to OUTCOME_FAILED,
          KEY_ERROR_REASON to permanentFailure.reason,
        )
      )
    }

    val updatedCount = results.count { it is RemoteDatasetUpdateResult.Updated }
    return Result.success(
      workDataOf(
        KEY_OUTCOME to OUTCOME_OK,
        KEY_UPDATED_COUNT to updatedCount,
      )
    )
  }

  companion object {
    const val KEY_DATASET_ID = "dataset_id"
    const val KEY_OUTCOME = "outcome"
    const val KEY_ERROR_REASON = "error_reason"
    const val KEY_UPDATED_COUNT = "updated_count"
    const val OUTCOME_OK = "ok"
    const val OUTCOME_DISABLED = "disabled"
    const val OUTCOME_FAILED = "failed"
    const val MAX_RETRY_ATTEMPTS = 3
  }
}
