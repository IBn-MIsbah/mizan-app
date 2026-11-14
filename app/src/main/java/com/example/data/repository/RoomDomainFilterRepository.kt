package com.example.data.repository

import android.database.sqlite.SQLiteException
import com.example.core.common.DispatcherProvider
import com.example.data.local.dao.DomainFilterDao
import com.example.data.local.mapper.CorruptLocalDataException
import com.example.data.local.mapper.PersistenceMappers.toDomain
import com.example.data.local.mapper.PersistenceMappers.toEntity
import com.example.domain.filter.DomainNormalizationResult
import com.example.domain.filter.DomainNormalizer
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.repository.DatasetImportResult
import com.example.domain.repository.DomainDatasetSignatureVerifier
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.TimeProvider
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class RoomDomainFilterRepository
@Inject
constructor(
  private val domainFilterDao: DomainFilterDao,
  private val signatureVerifier: DomainDatasetSignatureVerifier,
  private val timeProvider: TimeProvider,
  private val dispatcherProvider: DispatcherProvider,
) : DomainFilterRepository {

  override fun observeDatasets(): Flow<PersistenceResult<List<FilterDatasetMetadata>>> =
    domainFilterDao
      .observeDatasets()
      .map<_, PersistenceResult<List<FilterDatasetMetadata>>> { rows ->
        PersistenceResult.Success(rows.map { it.toDomain() })
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)

  override suspend fun ensureDefaultDatasetsInitialized(): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        domainFilterDao.insertDatasetsIgnore(
          DatasetSeedDefaults.defaultMetadata().map { it.toEntity() }
        )
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun getDatasetMetadata(
    datasetId: String,
  ): PersistenceResult<FilterDatasetMetadata?> =
    withContext(dispatcherProvider.io) {
      try {
        PersistenceResult.Success(domainFilterDao.getDatasetMetadata(datasetId)?.toDomain())
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun getActiveDomainsForCategories(
    categories: Set<DomainCategory>,
  ): PersistenceResult<Map<DomainCategory, Set<String>>> =
    withContext(dispatcherProvider.io) {
      if (categories.isEmpty()) {
        return@withContext PersistenceResult.Success(emptyMap())
      }
      try {
        val datasets =
          domainFilterDao.getDatasetsByCategories(categories.map { it.name })
            .map { it.toDomain() }
        val result = LinkedHashMap<DomainCategory, Set<String>>()
        for (dataset in datasets) {
          if (dataset.trustStatus != DatasetTrustStatus.INSTALLED_VERIFIED) continue
          val domains =
            domainFilterDao
              .getDomainsByGeneration(dataset.datasetId, DomainFilterDao.GENERATION_ACTIVE)
              .toSet()
          if (domains.isNotEmpty()) {
            val existing = result[dataset.category].orEmpty()
            result[dataset.category] = existing + domains
          }
        }
        PersistenceResult.Success(result)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun stageVerifyAndActivateDataset(
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
    requireSignature: Boolean,
  ): PersistenceResult<DatasetImportResult> =
    withContext(dispatcherProvider.io) {
      try {
        val existing = domainFilterDao.getDatasetMetadata(datasetId)?.toDomain()

        if (versionCode <= 0L) {
          val reason = "Dataset versionCode must be positive."
          recordRejectedStatus(existing, DatasetTrustStatus.CORRUPT_OR_REJECTED, reason)
          return@withContext PersistenceResult.Success(DatasetImportResult.Rejected(reason))
        }

        if (rawPayloadBytes.isEmpty()) {
          val reason = "Dataset payload is empty."
          recordRejectedStatus(existing, DatasetTrustStatus.CORRUPT_OR_REJECTED, reason)
          return@withContext PersistenceResult.Success(DatasetImportResult.Rejected(reason))
        }
        if (rawPayloadBytes.size > MAX_DATASET_BYTES) {
          val reason =
            "Dataset payload exceeds maximum size of ${MAX_DATASET_BYTES / (1024 * 1024)} MB."
          recordRejectedStatus(existing, DatasetTrustStatus.CORRUPT_OR_REJECTED, reason)
          return@withContext PersistenceResult.Success(DatasetImportResult.Rejected(reason))
        }

        if (existing != null && versionCode < existing.versionCode) {
          val reason = "Version rollback rejected ($versionCode < ${existing.versionCode})."
          recordRejectedStatus(existing, DatasetTrustStatus.CORRUPT_OR_REJECTED, reason)
          return@withContext PersistenceResult.Success(DatasetImportResult.Rejected(reason))
        }

        val actualSha256 = sha256Hex(rawPayloadBytes)
        if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
          recordRejectedStatus(
            existing,
            DatasetTrustStatus.CORRUPT_OR_REJECTED,
            "SHA-256 checksum mismatch.",
          )
          return@withContext PersistenceResult.Success(
            DatasetImportResult.Rejected("SHA-256 content integrity check failed.")
          )
        }

        if (
          existing != null &&
            existing.isUsable &&
            versionCode == existing.versionCode &&
            existing.contentSha256 != null &&
            !actualSha256.equals(existing.contentSha256, ignoreCase = true)
        ) {
          val reason =
            "Conflicting dataset payload rejected for already-installed version $versionCode."
          recordRejectedStatus(existing, DatasetTrustStatus.CORRUPT_OR_REJECTED, reason)
          return@withContext PersistenceResult.Success(DatasetImportResult.Rejected(reason))
        }

        if (requireSignature) {
          if (
            !signatureVerifier.isTrustedKeyConfigured ||
              signatureAlgorithm.isNullOrBlank() ||
              signatureValue.isNullOrBlank()
          ) {
            recordRejectedStatus(
              existing,
              DatasetTrustStatus.INVALID_SIGNATURE,
              "Trusted signature verification key or dataset signature is missing.",
            )
            return@withContext PersistenceResult.Success(
              DatasetImportResult.Rejected(
                "Trusted public verification key or signature is missing."
              )
            )
          }
          val validSignature =
            signatureVerifier.verify(rawPayloadBytes, signatureAlgorithm, signatureValue)
          if (!validSignature) {
            recordRejectedStatus(
              existing,
              DatasetTrustStatus.INVALID_SIGNATURE,
              "Dataset cryptographic signature verification failed.",
            )
            return@withContext PersistenceResult.Success(
              DatasetImportResult.Rejected("Dataset signature verification failed.")
            )
          }
        }

        val parsedDomains = LinkedHashSet<String>()
        val text = rawPayloadBytes.decodeToString()
        for (rawLine in text.lineSequence()) {
          val candidate = rawLine.trim().substringBefore('#').trim()
          if (candidate.isEmpty()) continue
          when (val norm = DomainNormalizer.normalize(candidate)) {
            is DomainNormalizationResult.Valid -> {
              parsedDomains.add(norm.normalizedHost)
              if (parsedDomains.size > MAX_DATASET_RECORDS) {
                val reason = "Dataset exceeds record limit of $MAX_DATASET_RECORDS domains."
                recordRejectedStatus(existing, DatasetTrustStatus.CORRUPT_OR_REJECTED, reason)
                return@withContext PersistenceResult.Success(DatasetImportResult.Rejected(reason))
              }
            }
            is DomainNormalizationResult.Invalid -> {
              val reason = "Dataset contains invalid domain syntax."
              recordRejectedStatus(existing, DatasetTrustStatus.CORRUPT_OR_REJECTED, reason)
              return@withContext PersistenceResult.Success(DatasetImportResult.Rejected(reason))
            }
          }
        }

        if (parsedDomains.isEmpty()) {
          val reason = "Dataset parsed zero valid domain entries."
          recordRejectedStatus(existing, DatasetTrustStatus.CORRUPT_OR_REJECTED, reason)
          return@withContext PersistenceResult.Success(DatasetImportResult.Rejected(reason))
        }

        if (
          existing != null &&
            existing.isUsable &&
            versionCode == existing.versionCode &&
            actualSha256.equals(existing.contentSha256, ignoreCase = true)
        ) {
          val refreshed =
            existing.copy(
              updatedAtEpochMillis = timeProvider.currentWallClock().epochMillis,
              lastError = null,
              limitationsNote = limitationsNote,
            )
          domainFilterDao.upsertDatasetMetadata(refreshed.toEntity())
          return@withContext PersistenceResult.Success(DatasetImportResult.Success(refreshed))
        }

        val now = timeProvider.currentWallClock()
        val verifiedMeta =
          FilterDatasetMetadata(
            datasetId = datasetId,
            category = category,
            sourceName = sourceName,
            sourceUrl = existing?.sourceUrl,
            license = license,
            versionCode = versionCode,
            updatedAtEpochMillis = now.epochMillis,
            contentSha256 = actualSha256,
            signatureAlgorithm = signatureAlgorithm,
            signatureValue = signatureValue,
            recordCount = parsedDomains.size,
            trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
            lastError = null,
            limitationsNote = limitationsNote,
            previousVersionCode = existing?.takeIf { it.isUsable }?.versionCode,
            previousContentSha256 = existing?.takeIf { it.isUsable }?.contentSha256,
          )

        domainFilterDao.activateVerifiedDataset(
          metadata = verifiedMeta.toEntity(),
          domains = parsedDomains.toList(),
        )
        PersistenceResult.Success(DatasetImportResult.Success(verifiedMeta))
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun recordDatasetUpdateFailure(
    datasetId: String,
    reason: String,
    trustStatusOnEmpty: DatasetTrustStatus,
  ): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        val existing = domainFilterDao.getDatasetMetadata(datasetId)?.toDomain()
        recordRejectedStatus(existing, trustStatusOnEmpty, reason)
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun clearDatasetError(datasetId: String): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        val existing = domainFilterDao.getDatasetMetadata(datasetId)?.toDomain()
        if (existing != null && existing.isUsable && existing.lastError != null) {
          domainFilterDao.upsertDatasetMetadata(existing.copy(lastError = null).toEntity())
        }
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun rollbackDataset(datasetId: String): PersistenceResult<FilterDatasetMetadata> =
    withContext(dispatcherProvider.io) {
      try {
        val current =
          domainFilterDao.getDatasetMetadata(datasetId)?.toDomain()
            ?: return@withContext PersistenceResult.Failure(PersistenceError.NotFound("FilterDataset"))
        val rollbackDomains =
          domainFilterDao.getDomainsByGeneration(datasetId, DomainFilterDao.GENERATION_ROLLBACK)
        if (rollbackDomains.isEmpty() || current.previousVersionCode == null) {
          return@withContext PersistenceResult.Failure(
            PersistenceError.NotFound("Previous verified dataset generation")
          )
        }

        val restored =
          current.copy(
            versionCode = current.previousVersionCode,
            contentSha256 = current.previousContentSha256,
            recordCount = rollbackDomains.size,
            trustStatus = DatasetTrustStatus.INSTALLED_VERIFIED,
            lastError = null,
            previousVersionCode = null,
            previousContentSha256 = null,
          )
        domainFilterDao.rollbackDatasetToLastKnownGood(datasetId, restored.toEntity())
        PersistenceResult.Success(restored)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  private suspend fun recordRejectedStatus(
    existing: FilterDatasetMetadata?,
    status: DatasetTrustStatus,
    reason: String,
  ) {
    if (existing != null && existing.isUsable) {
      // Preserve last known good metadata status & active domains without downgrading usable data;
      // record lastError only.
      domainFilterDao.upsertDatasetMetadata(existing.copy(lastError = reason).toEntity())
    } else if (existing != null) {
      domainFilterDao.upsertDatasetMetadata(
        existing.copy(trustStatus = status, lastError = reason).toEntity()
      )
    }
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

  private fun mapThrowable(t: Throwable): PersistenceError =
    when (t) {
      is CorruptLocalDataException ->
        PersistenceError.CorruptData(t.message ?: "Corrupt dataset record")
      is SQLiteException -> PersistenceError.StorageFailure("Local dataset database failure")
      else -> throw t
    }

  companion object {
    const val MAX_DATASET_BYTES = 5 * 1024 * 1024
    const val MAX_DATASET_RECORDS = 100_000
  }
}
