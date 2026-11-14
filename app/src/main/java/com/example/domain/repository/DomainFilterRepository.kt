package com.example.domain.repository

import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.model.FilterDatasetMetadata
import kotlinx.coroutines.flow.Flow

interface DomainDatasetSignatureVerifier {
  val isTrustedKeyConfigured: Boolean

  fun verify(
    payloadBytes: ByteArray,
    signatureAlgorithm: String,
    signatureValue: String,
  ): Boolean
}

sealed interface DatasetImportResult {
  data class Success(val metadata: FilterDatasetMetadata) : DatasetImportResult

  data class Rejected(val reason: String, val previousPreserved: Boolean = true) : DatasetImportResult
}

sealed interface RemoteDatasetUpdateResult {
  data class Disabled(val reason: String) : RemoteDatasetUpdateResult

  data class UpToDate(val metadata: FilterDatasetMetadata) : RemoteDatasetUpdateResult

  data class Updated(val metadata: FilterDatasetMetadata) : RemoteDatasetUpdateResult

  data class Failed(val reason: String, val isTransientNetworkError: Boolean = false) :
    RemoteDatasetUpdateResult
}

interface RemoteDatasetUpdater {
  suspend fun checkAndUpdate(datasetId: String): RemoteDatasetUpdateResult

  suspend fun checkAndUpdateAll(): Map<String, RemoteDatasetUpdateResult>
}

interface DomainFilterRepository {
  fun observeDatasets(): Flow<PersistenceResult<List<FilterDatasetMetadata>>>

  suspend fun ensureDefaultDatasetsInitialized(): PersistenceResult<Unit>

  suspend fun getDatasetMetadata(datasetId: String): PersistenceResult<FilterDatasetMetadata?>

  suspend fun getActiveDomainsForCategories(
    categories: Set<DomainCategory>,
  ): PersistenceResult<Map<DomainCategory, Set<String>>>

  suspend fun stageVerifyAndActivateDataset(
    datasetId: String,
    category: DomainCategory,
    sourceName: String,
    license: String,
    versionCode: Long,
    expectedSha256: String,
    signatureAlgorithm: String?,
    signatureValue: String?,
    rawPayloadBytes: ByteArray,
    limitationsNote: String,
    requireSignature: Boolean = true,
  ): PersistenceResult<DatasetImportResult>

  suspend fun recordDatasetUpdateFailure(
    datasetId: String,
    reason: String,
    trustStatusOnEmpty: DatasetTrustStatus = DatasetTrustStatus.CORRUPT_OR_REJECTED,
  ): PersistenceResult<Unit>

  suspend fun clearDatasetError(datasetId: String): PersistenceResult<Unit>

  suspend fun rollbackDataset(datasetId: String): PersistenceResult<FilterDatasetMetadata>
}
