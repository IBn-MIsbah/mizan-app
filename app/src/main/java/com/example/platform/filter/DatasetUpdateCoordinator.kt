package com.example.platform.filter

import com.example.data.filter.DatasetFeedPolicy
import com.example.data.filter.SignedDatasetPackage
import com.example.data.filter.SignedLocalDatasetImporter
import com.example.data.filter.SignedPackageImportOutcome
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DomainCategory
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.RemoteDatasetUpdateResult
import com.example.domain.repository.RemoteDatasetUpdater
import com.example.domain.repository.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DatasetSyncUiSnapshot(
  val updatingDatasetIds: Set<String> = emptySet(),
  val isImportingPackage: Boolean = false,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
  val isRemoteFeedConfigured: Boolean = false,
) {
  val isAnyUpdating: Boolean
    get() = updatingDatasetIds.isNotEmpty() || isImportingPackage
}

interface DatasetSyncController {
  val syncState: StateFlow<DatasetSyncUiSnapshot>

  suspend fun runUpdateCheck(targetDatasetId: String? = null): Map<String, RemoteDatasetUpdateResult>

  suspend fun importSignedLocalPackage(rawJsonBytes: ByteArray): SignedPackageImportOutcome

  suspend fun rollbackDataset(datasetId: String): PersistenceResult<FilterDatasetMetadata>

  suspend fun syncPeriodicSchedule()
}

@Singleton
class DefaultDatasetSyncController
@Inject
constructor(
  private val remoteDatasetUpdater: RemoteDatasetUpdater,
  private val signedLocalDatasetImporter: SignedLocalDatasetImporter,
  private val domainFilterRepository: DomainFilterRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val datasetUpdateScheduler: DatasetUpdateScheduler,
  private val feedPolicy: DatasetFeedPolicy,
) : DatasetSyncController {

  private val mutex = Mutex()
  private val _syncState =
    MutableStateFlow(
      DatasetSyncUiSnapshot(isRemoteFeedConfigured = feedPolicy.hasAnyConfiguredFeed())
    )
  override val syncState: StateFlow<DatasetSyncUiSnapshot> = _syncState.asStateFlow()

  override suspend fun runUpdateCheck(
    targetDatasetId: String?,
  ): Map<String, RemoteDatasetUpdateResult> =
    mutex.withLock {
      val targetIds =
        if (targetDatasetId != null) {
          setOf(targetDatasetId)
        } else {
          setOf(DatasetSeedDefaults.ADULT_DATASET_ID, DatasetSeedDefaults.SOCIAL_DATASET_ID)
        }
      _syncState.update {
        it.copy(
          updatingDatasetIds = it.updatingDatasetIds + targetIds,
          statusMessage = null,
          errorMessage = null,
        )
      }
      return@withLock try {
        val outcomes =
          if (targetDatasetId != null) {
            mapOf(targetDatasetId to remoteDatasetUpdater.checkAndUpdate(targetDatasetId))
          } else {
            remoteDatasetUpdater.checkAndUpdateAll()
          }
        applySummaryFeedback(outcomes)
        outcomes
      } finally {
        _syncState.update {
          it.copy(updatingDatasetIds = it.updatingDatasetIds - targetIds)
        }
      }
    }

  override suspend fun importSignedLocalPackage(
    rawJsonBytes: ByteArray,
  ): SignedPackageImportOutcome =
    mutex.withLock {
      _syncState.update {
        it.copy(isImportingPackage = true, statusMessage = null, errorMessage = null)
      }
      return@withLock try {
        when (val outcome = signedLocalDatasetImporter.importPackageBytes(rawJsonBytes)) {
          is SignedPackageImportOutcome.Success -> {
            val meta = outcome.result.metadata
            _syncState.update {
              it.copy(
                statusMessage =
                  "Verified ${formatCategoryName(meta.category)} dataset imported (v${meta.versionCode} · ${meta.recordCount} entries).",
                errorMessage = null,
              )
            }
            outcome
          }
          is SignedPackageImportOutcome.Rejected -> {
            _syncState.update {
              it.copy(statusMessage = null, errorMessage = outcome.reason)
            }
            outcome
          }
        }
      } finally {
        _syncState.update { it.copy(isImportingPackage = false) }
      }
    }

  override suspend fun rollbackDataset(
    datasetId: String,
  ): PersistenceResult<FilterDatasetMetadata> =
    mutex.withLock {
      _syncState.update {
        it.copy(
          updatingDatasetIds = it.updatingDatasetIds + datasetId,
          statusMessage = null,
          errorMessage = null,
        )
      }
      return@withLock try {
        val result = domainFilterRepository.rollbackDataset(datasetId)
        when (result) {
          is PersistenceResult.Success -> {
            val meta = result.data
            _syncState.update {
              it.copy(
                statusMessage =
                  "Rolled back ${formatCategoryName(meta.category)} dataset to v${meta.versionCode} (${meta.recordCount} entries).",
                errorMessage = null,
              )
            }
          }
          is PersistenceResult.Failure -> {
            _syncState.update {
              it.copy(
                statusMessage = null,
                errorMessage = "No verified previous dataset generation is available to roll back.",
              )
            }
          }
        }
        result
      } finally {
        _syncState.update { it.copy(updatingDatasetIds = it.updatingDatasetIds - datasetId) }
      }
    }

  override suspend fun syncPeriodicSchedule() {
    val wifiOnly = runCatching { userPreferencesRepository.preferences.first().wifiOnlyFilterUpdates }
      .getOrDefault(true)
    datasetUpdateScheduler.syncPeriodicSchedule(wifiOnly)
  }

  private fun applySummaryFeedback(outcomes: Map<String, RemoteDatasetUpdateResult>) {
    val values = outcomes.values.toList()
    if (values.isEmpty()) return

    val updatedCount = values.count { it is RemoteDatasetUpdateResult.Updated }
    val upToDateCount = values.count { it is RemoteDatasetUpdateResult.UpToDate }
    val disabledResult =
      values.firstOrNull { it is RemoteDatasetUpdateResult.Disabled }
        as? RemoteDatasetUpdateResult.Disabled
    val permanentFailure =
      values.firstOrNull { it is RemoteDatasetUpdateResult.Failed && !it.isTransientNetworkError }
        as? RemoteDatasetUpdateResult.Failed
    val transientFailure =
      values.firstOrNull { it is RemoteDatasetUpdateResult.Failed && it.isTransientNetworkError }
        as? RemoteDatasetUpdateResult.Failed

    when {
      permanentFailure != null -> {
        _syncState.update {
          it.copy(statusMessage = null, errorMessage = permanentFailure.reason)
        }
      }
      updatedCount > 0 && transientFailure != null -> {
        _syncState.update {
          it.copy(
            statusMessage =
              "Updated $updatedCount verified filter dataset(s). Remaining feed deferred: ${transientFailure.reason}",
            errorMessage = null,
          )
        }
      }
      updatedCount > 0 -> {
        _syncState.update {
          it.copy(
            statusMessage = "Updated $updatedCount verified filter dataset(s).",
            errorMessage = null,
          )
        }
      }
      upToDateCount > 0 && transientFailure != null -> {
        _syncState.update {
          it.copy(
            statusMessage =
              "Eligible datasets are up to date. Remaining feed deferred: ${transientFailure.reason}",
            errorMessage = null,
          )
        }
      }
      upToDateCount > 0 && disabledResult == null -> {
        _syncState.update {
          it.copy(
            statusMessage = "All configured datasets are already up to date.",
            errorMessage = null,
          )
        }
      }
      transientFailure != null -> {
        _syncState.update {
          it.copy(statusMessage = null, errorMessage = transientFailure.reason)
        }
      }
      disabledResult != null -> {
        _syncState.update {
          it.copy(statusMessage = disabledResult.reason, errorMessage = null)
        }
      }
    }
  }

  private fun formatCategoryName(category: DomainCategory): String =
    when (category) {
      DomainCategory.ADULT_CONTENT -> "Adult Content"
      DomainCategory.SOCIAL_MEDIA -> "Social Media"
      DomainCategory.CUSTOM -> "Custom"
    }
}
