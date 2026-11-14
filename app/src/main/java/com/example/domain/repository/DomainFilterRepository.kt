package com.example.domain.repository

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

class NoTrustedKeySignatureVerifier : DomainDatasetSignatureVerifier {
  override val isTrustedKeyConfigured: Boolean = false

  override fun verify(
    payloadBytes: ByteArray,
    signatureAlgorithm: String,
    signatureValue: String,
  ): Boolean = false
}

sealed interface DatasetImportResult {
  data class Success(val metadata: FilterDatasetMetadata) : DatasetImportResult

  data class Rejected(val reason: String, val previousPreserved: Boolean = true) : DatasetImportResult
}

sealed interface RemoteDatasetUpdateResult {
  data class Disabled(val reason: String) : RemoteDatasetUpdateResult

  data class Updated(val metadata: FilterDatasetMetadata) : RemoteDatasetUpdateResult

  data class Failed(val reason: String) : RemoteDatasetUpdateResult
}

interface RemoteDatasetUpdater {
  suspend fun checkAndUpdate(datasetId: String): RemoteDatasetUpdateResult
}

interface DomainFilterRepository {
  fun observeDatasets(): Flow<PersistenceResult<List<FilterDatasetMetadata>>>

  suspend fun ensureDefaultDatasetsInitialized(): PersistenceResult<Unit>

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

  suspend fun rollbackDataset(datasetId: String): PersistenceResult<FilterDatasetMetadata>
}
