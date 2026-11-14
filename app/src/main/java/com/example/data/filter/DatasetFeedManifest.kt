package com.example.data.filter

import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DatasetStatusEvaluator
import com.example.domain.model.DomainCategory
import kotlinx.serialization.Serializable

/**
 * Signed remote dataset feed manifest metadata fetched over HTTPS.
 * Every signature is evaluated over the raw UTF-8 domain payload downloaded from [payloadUrl].
 */
@Serializable
data class SignedDatasetManifest(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val datasetId: String,
  val category: DomainCategory,
  val versionCode: Long,
  val sourceName: String,
  val license: String,
  val publishedAtEpochMillis: Long,
  val payloadUrl: String,
  val contentSha256: String,
  val signatureAlgorithm: String,
  val signatureValue: String,
  val limitationsNote: String,
) {
  companion object {
    const val CURRENT_SCHEMA_VERSION = 1
  }
}

/**
 * Self-contained signed local dataset package for user-initiated offline SAF import.
 * Enforces the exact same SHA-256 and pinned-key ECDSA signature checks as remote feeds.
 */
@Serializable
data class SignedDatasetPackage(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val datasetId: String,
  val category: DomainCategory,
  val versionCode: Long,
  val sourceName: String,
  val license: String,
  val publishedAtEpochMillis: Long? = null,
  val contentSha256: String,
  val signatureAlgorithm: String,
  val signatureValue: String,
  val domainsPayload: String,
  val limitationsNote: String,
) {
  companion object {
    const val CURRENT_SCHEMA_VERSION = 1
  }
}

enum class CategoryNetworkPolicy {
  ALLOW_CELLULAR_AND_WIFI,
  RESPECT_WIFI_ONLY_PREFERENCE,
}

object DatasetNetworkPolicyResolver {
  fun defaultPolicyFor(category: DomainCategory): CategoryNetworkPolicy =
    when (category) {
      DomainCategory.ADULT_CONTENT -> CategoryNetworkPolicy.ALLOW_CELLULAR_AND_WIFI
      DomainCategory.SOCIAL_MEDIA,
      DomainCategory.CUSTOM -> CategoryNetworkPolicy.RESPECT_WIFI_ONLY_PREFERENCE
    }

  fun requiresWifiOnly(
    category: DomainCategory,
    wifiOnlyPreference: Boolean,
    policy: CategoryNetworkPolicy = defaultPolicyFor(category),
  ): Boolean =
    when (policy) {
      CategoryNetworkPolicy.ALLOW_CELLULAR_AND_WIFI -> false
      CategoryNetworkPolicy.RESPECT_WIFI_ONLY_PREFERENCE -> wifiOnlyPreference
    }
}

data class DatasetFeedSourceSpec(
  val datasetId: String,
  val category: DomainCategory,
  val manifestUrl: String?,
  val sourceName: String,
  val license: String,
  val outdatedAfterMillis: Long = DatasetStatusEvaluator.DEFAULT_OUTDATED_AFTER_MILLIS,
  val networkPolicy: CategoryNetworkPolicy = DatasetNetworkPolicyResolver.defaultPolicyFor(category),
) {
  fun requiresWifiOnly(wifiOnlyPreference: Boolean): Boolean =
    DatasetNetworkPolicyResolver.requiresWifiOnly(
      category = category,
      wifiOnlyPreference = wifiOnlyPreference,
      policy = networkPolicy,
    )
}

/**
 * Feed policy and replaceable source registry.
 * Keeps remote updates disabled until vetted HTTPS manifest endpoints and a pinned public signing
 * key are explicitly provisioned.
 */
data class DatasetFeedPolicy(
  val remoteUpdatesEnabled: Boolean = false,
  val sources: Map<String, DatasetFeedSourceSpec> = defaultUnconfiguredSources(),
) {
  fun sourceFor(datasetId: String): DatasetFeedSourceSpec? = sources[datasetId]

  fun isFeedConfiguredFor(datasetId: String): Boolean {
    if (!remoteUpdatesEnabled) return false
    val url = sources[datasetId]?.manifestUrl ?: return false
    return url.startsWith("https://", ignoreCase = true)
  }

  fun hasAnyConfiguredFeed(): Boolean =
    remoteUpdatesEnabled && sources.keys.any { isFeedConfiguredFor(it) }

  companion object {
    fun defaultUnconfiguredSources(): Map<String, DatasetFeedSourceSpec> =
      linkedMapOf(
        DatasetSeedDefaults.ADULT_DATASET_ID to
          DatasetFeedSourceSpec(
            datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
            category = DomainCategory.ADULT_CONTENT,
            manifestUrl = null,
            sourceName = "No curated signed feed configured",
            license = "Unassigned (no external feed bundled)",
            networkPolicy = CategoryNetworkPolicy.ALLOW_CELLULAR_AND_WIFI,
          ),
        DatasetSeedDefaults.SOCIAL_DATASET_ID to
          DatasetFeedSourceSpec(
            datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
            category = DomainCategory.SOCIAL_MEDIA,
            manifestUrl = null,
            sourceName = "No curated signed feed configured",
            license = "Unassigned (no external feed bundled)",
            networkPolicy = CategoryNetworkPolicy.RESPECT_WIFI_ONLY_PREFERENCE,
          ),
      )
  }
}
