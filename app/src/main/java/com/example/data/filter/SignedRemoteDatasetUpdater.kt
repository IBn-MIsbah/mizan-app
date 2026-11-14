package com.example.data.filter

import com.example.data.repository.RoomDomainFilterRepository
import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.repository.DatasetImportResult
import com.example.domain.repository.DomainDatasetSignatureVerifier
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.RemoteDatasetUpdateResult
import com.example.domain.repository.RemoteDatasetUpdater
import com.example.domain.repository.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Signed, version-checked, integrity-validated remote dataset updater.
 *
 * Fail-closed behavior:
 * - Keeps remote updates disabled when no trusted ECDSA public key or vetted HTTPS feed source is
 *   configured.
 * - Never replaces a valid cached dataset with an empty, malformed, unsigned, downgraded, or
 *   signature-failing update.
 */
@Singleton
class SignedRemoteDatasetUpdater
@Inject
constructor(
  private val feedPolicy: DatasetFeedPolicy,
  private val signatureVerifier: DomainDatasetSignatureVerifier,
  private val domainFilterRepository: DomainFilterRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val feedHttpFetcher: FeedHttpFetcher,
  private val networkPolicyChecker: UpdateNetworkPolicyChecker,
) : RemoteDatasetUpdater {

  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun checkAndUpdate(datasetId: String): RemoteDatasetUpdateResult {
    domainFilterRepository.ensureDefaultDatasetsInitialized()

    val sourceSpec = feedPolicy.sourceFor(datasetId)
    if (
      !feedPolicy.remoteUpdatesEnabled ||
        !signatureVerifier.isTrustedKeyConfigured ||
        sourceSpec == null ||
        sourceSpec.manifestUrl.isNullOrBlank() ||
        !sourceSpec.manifestUrl.startsWith("https://", ignoreCase = true)
    ) {
      return RemoteDatasetUpdateResult.Disabled(
        "Remote dataset updates are disabled until a vetted HTTPS feed source and pinned ECDSA public verification key are configured."
      )
    }

    val prefs = runCatching { userPreferencesRepository.preferences.first() }.getOrNull()
    val wifiOnlyPref = prefs?.wifiOnlyFilterUpdates ?: true
    val effectiveWifiOnly = sourceSpec.requiresWifiOnly(wifiOnlyPreference = wifiOnlyPref)
    when (val eligibility = networkPolicyChecker.checkEligibility(effectiveWifiOnly)) {
      is UpdateNetworkEligibility.Ineligible -> {
        return RemoteDatasetUpdateResult.Failed(
          reason = eligibility.reason,
          isTransientNetworkError = eligibility.isTransientOffline,
        )
      }
      UpdateNetworkEligibility.Eligible -> Unit
    }

    val manifestBytes =
      when (val res = feedHttpFetcher.fetchBytes(sourceSpec.manifestUrl, MAX_MANIFEST_BYTES)) {
        is FeedFetchResult.Success -> res.bytes
        is FeedFetchResult.NetworkError -> {
          return RemoteDatasetUpdateResult.Failed(
            reason = res.reason,
            isTransientNetworkError = true,
          )
        }
        is FeedFetchResult.HttpError -> {
          val msg = "Dataset manifest fetch failed (HTTP ${res.statusCode})."
          recordFailureSafely(datasetId, msg, DatasetTrustStatus.CORRUPT_OR_REJECTED)
          return RemoteDatasetUpdateResult.Failed(reason = msg, isTransientNetworkError = false)
        }
        is FeedFetchResult.InvalidRequestOrLimit -> {
          recordFailureSafely(datasetId, res.reason, DatasetTrustStatus.CORRUPT_OR_REJECTED)
          return RemoteDatasetUpdateResult.Failed(reason = res.reason, isTransientNetworkError = false)
        }
      }

    val manifest =
      try {
        json.decodeFromString<SignedDatasetManifest>(manifestBytes.decodeToString())
      } catch (_: SerializationException) {
        val msg = "Dataset manifest JSON is malformed."
        recordFailureSafely(datasetId, msg, DatasetTrustStatus.CORRUPT_OR_REJECTED)
        return RemoteDatasetUpdateResult.Failed(reason = msg, isTransientNetworkError = false)
      } catch (_: IllegalArgumentException) {
        val msg = "Dataset manifest encoding is invalid."
        recordFailureSafely(datasetId, msg, DatasetTrustStatus.CORRUPT_OR_REJECTED)
        return RemoteDatasetUpdateResult.Failed(reason = msg, isTransientNetworkError = false)
      }

    val validationError = validateManifest(manifest, sourceSpec)
    if (validationError != null) {
      recordFailureSafely(datasetId, validationError, DatasetTrustStatus.CORRUPT_OR_REJECTED)
      return RemoteDatasetUpdateResult.Failed(reason = validationError, isTransientNetworkError = false)
    }

    val existing =
      when (val metaRes = domainFilterRepository.getDatasetMetadata(datasetId)) {
        is PersistenceResult.Success -> metaRes.data
        is PersistenceResult.Failure -> {
          return RemoteDatasetUpdateResult.Failed(
            reason = "Unable to inspect local cached dataset state.",
            isTransientNetworkError = false,
          )
        }
      }

    if (existing != null && manifest.versionCode < existing.versionCode) {
      val msg =
        "Version rollback rejected (${manifest.versionCode} < ${existing.versionCode}). Cached dataset preserved."
      recordFailureSafely(datasetId, msg, DatasetTrustStatus.CORRUPT_OR_REJECTED)
      return RemoteDatasetUpdateResult.Failed(reason = msg, isTransientNetworkError = false)
    }

    if (existing != null && existing.isUsable && manifest.versionCode == existing.versionCode) {
      if (manifest.contentSha256.trim().equals(existing.contentSha256, ignoreCase = true)) {
        domainFilterRepository.clearDatasetError(datasetId)
        return RemoteDatasetUpdateResult.UpToDate(existing.copy(lastError = null))
      } else {
        val msg =
          "Remote manifest hash differs for already-installed version ${manifest.versionCode}; existing dataset preserved."
        recordFailureSafely(datasetId, msg, DatasetTrustStatus.CORRUPT_OR_REJECTED)
        return RemoteDatasetUpdateResult.Failed(reason = msg, isTransientNetworkError = false)
      }
    }

    val payloadBytes =
      when (
        val payloadRes =
          feedHttpFetcher.fetchBytes(manifest.payloadUrl, RoomDomainFilterRepository.MAX_DATASET_BYTES)
      ) {
        is FeedFetchResult.Success -> payloadRes.bytes
        is FeedFetchResult.NetworkError -> {
          return RemoteDatasetUpdateResult.Failed(
            reason = payloadRes.reason,
            isTransientNetworkError = true,
          )
        }
        is FeedFetchResult.HttpError -> {
          val msg = "Dataset payload fetch failed (HTTP ${payloadRes.statusCode})."
          recordFailureSafely(datasetId, msg, DatasetTrustStatus.CORRUPT_OR_REJECTED)
          return RemoteDatasetUpdateResult.Failed(reason = msg, isTransientNetworkError = false)
        }
        is FeedFetchResult.InvalidRequestOrLimit -> {
          recordFailureSafely(datasetId, payloadRes.reason, DatasetTrustStatus.CORRUPT_OR_REJECTED)
          return RemoteDatasetUpdateResult.Failed(
            reason = payloadRes.reason,
            isTransientNetworkError = false,
          )
        }
      }

    return when (
      val importRes =
        domainFilterRepository.stageVerifyAndActivateDataset(
          datasetId = manifest.datasetId,
          category = manifest.category,
          sourceName = manifest.sourceName.trim(),
          license = manifest.license.trim(),
          versionCode = manifest.versionCode,
          expectedSha256 = manifest.contentSha256,
          signatureAlgorithm = manifest.signatureAlgorithm,
          signatureValue = manifest.signatureValue,
          rawPayloadBytes = payloadBytes,
          limitationsNote = manifest.limitationsNote.trim(),
          requireSignature = true,
        )
    ) {
      is PersistenceResult.Failure ->
        RemoteDatasetUpdateResult.Failed(
          reason = "Local persistence failure during dataset verification.",
          isTransientNetworkError = false,
        )
      is PersistenceResult.Success ->
        when (val outcome = importRes.data) {
          is DatasetImportResult.Success -> RemoteDatasetUpdateResult.Updated(outcome.metadata)
          is DatasetImportResult.Rejected ->
            RemoteDatasetUpdateResult.Failed(
              reason = outcome.reason,
              isTransientNetworkError = false,
            )
        }
    }
  }

  override suspend fun checkAndUpdateAll(): Map<String, RemoteDatasetUpdateResult> {
    val targetIds = feedPolicy.sources.keys.toList()
    val outcomes = LinkedHashMap<String, RemoteDatasetUpdateResult>(targetIds.size)
    for (id in targetIds) {
      outcomes[id] = checkAndUpdate(id)
    }
    return outcomes
  }

  private fun validateManifest(
    manifest: SignedDatasetManifest,
    spec: DatasetFeedSourceSpec,
  ): String? =
    when {
      manifest.schemaVersion != SignedDatasetManifest.CURRENT_SCHEMA_VERSION ->
        "Unsupported dataset manifest schema version ${manifest.schemaVersion}."
      manifest.datasetId != spec.datasetId ->
        "Manifest datasetId mismatch (${manifest.datasetId} != ${spec.datasetId})."
      manifest.category != spec.category || manifest.category == DomainCategory.CUSTOM ->
        "Manifest category mismatch (${manifest.category})."
      manifest.versionCode <= 0L -> "Manifest versionCode must be positive."
      !manifest.payloadUrl.trim().startsWith("https://", ignoreCase = true) ->
        "Manifest payloadUrl must use HTTPS."
      manifest.sourceName.isBlank() || manifest.license.isBlank() ->
        "Manifest missing sourceName or license metadata."
      manifest.contentSha256.isBlank() ||
        manifest.signatureAlgorithm.isBlank() ||
        manifest.signatureValue.isBlank() ->
        "Manifest missing SHA-256 digest or cryptographic signature."
      manifest.limitationsNote.isBlank() -> "Manifest missing limitationsNote."
      else -> null
    }

  private suspend fun recordFailureSafely(
    datasetId: String,
    reason: String,
    trustStatus: DatasetTrustStatus,
  ) {
    runCatching {
      domainFilterRepository.recordDatasetUpdateFailure(
        datasetId = datasetId,
        reason = reason,
        trustStatusOnEmpty = trustStatus,
      )
    }
  }

  companion object {
    const val MAX_MANIFEST_BYTES = 64 * 1024
  }
}
