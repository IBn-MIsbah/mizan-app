package com.example.data.filter

import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DomainCategory
import com.example.domain.repository.DatasetImportResult
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.PersistenceResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface SignedPackageImportOutcome {
  data class Success(val result: DatasetImportResult.Success) : SignedPackageImportOutcome

  data class Rejected(val reason: String) : SignedPackageImportOutcome
}

/**
 * Validates and imports user-selected local signed dataset packages (.json).
 * Enforces SHA-256 integrity, version checks, and pinned-key ECDSA cryptographic signature
 * verification so unsigned or forged files are never accepted.
 */
@Singleton
class SignedLocalDatasetImporter
@Inject
constructor(
  private val domainFilterRepository: DomainFilterRepository,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun importPackageBytes(rawJsonBytes: ByteArray): SignedPackageImportOutcome {
    if (rawJsonBytes.isEmpty()) {
      return SignedPackageImportOutcome.Rejected("Selected dataset file is empty.")
    }
    if (rawJsonBytes.size > MAX_PACKAGE_BYTES) {
      return SignedPackageImportOutcome.Rejected(
        "Selected dataset file exceeds maximum size of ${MAX_PACKAGE_BYTES / (1024 * 1024)} MB."
      )
    }

    val pkg =
      try {
        json.decodeFromString<SignedDatasetPackage>(rawJsonBytes.decodeToString())
      } catch (_: SerializationException) {
        return SignedPackageImportOutcome.Rejected("Selected file is not a valid MIZAN signed dataset package.")
      } catch (_: IllegalArgumentException) {
        return SignedPackageImportOutcome.Rejected("Selected file contains invalid UTF-8 text.")
      }

    val expectedDatasetId =
      when (pkg.category) {
        DomainCategory.ADULT_CONTENT -> DatasetSeedDefaults.ADULT_DATASET_ID
        DomainCategory.SOCIAL_MEDIA -> DatasetSeedDefaults.SOCIAL_DATASET_ID
        DomainCategory.CUSTOM ->
          return SignedPackageImportOutcome.Rejected(
            "Custom domains are configured in Commitment Setup, not as category datasets."
          )
      }

    if (pkg.schemaVersion != SignedDatasetPackage.CURRENT_SCHEMA_VERSION) {
      return SignedPackageImportOutcome.Rejected(
        "Unsupported signed package schema version ${pkg.schemaVersion}."
      )
    }
    if (pkg.datasetId != expectedDatasetId) {
      return SignedPackageImportOutcome.Rejected(
        "Dataset package ID (${pkg.datasetId}) does not match expected category dataset ($expectedDatasetId)."
      )
    }
    if (pkg.sourceName.isBlank() || pkg.license.isBlank() || pkg.limitationsNote.isBlank()) {
      return SignedPackageImportOutcome.Rejected("Signed package is missing source, license, or limitations metadata.")
    }

    domainFilterRepository.ensureDefaultDatasetsInitialized()

    return when (
      val res =
        domainFilterRepository.stageVerifyAndActivateDataset(
          datasetId = pkg.datasetId,
          category = pkg.category,
          sourceName = pkg.sourceName.trim(),
          license = pkg.license.trim(),
          versionCode = pkg.versionCode,
          expectedSha256 = pkg.contentSha256,
          signatureAlgorithm = pkg.signatureAlgorithm,
          signatureValue = pkg.signatureValue,
          rawPayloadBytes = pkg.domainsPayload.encodeToByteArray(),
          limitationsNote = pkg.limitationsNote.trim(),
          requireSignature = true,
        )
    ) {
      is PersistenceResult.Failure ->
        SignedPackageImportOutcome.Rejected("Failed to persist dataset in local Room storage.")
      is PersistenceResult.Success ->
        when (val importResult = res.data) {
          is DatasetImportResult.Success -> SignedPackageImportOutcome.Success(importResult)
          is DatasetImportResult.Rejected -> SignedPackageImportOutcome.Rejected(importResult.reason)
        }
    }
  }

  companion object {
    const val MAX_PACKAGE_BYTES = 6 * 1024 * 1024
  }
}
