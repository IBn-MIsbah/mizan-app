package com.example.domain.model

enum class DomainCategory {
  CUSTOM,
  ADULT_CONTENT,
  SOCIAL_MEDIA,
}

enum class DomainRuleType {
  BLOCK,
  ALLOW,
}

@JvmInline
value class DomainName(val value: String) {
  init {
    require(value.isNotBlank()) { "DomainName must not be blank" }
  }
}

/**
 * Single normalized domain-filter entry bound to a commitment or local dataset.
 */
data class BlockedDomain(
  val domain: DomainName,
  val displayName: String = domain.value,
  val category: DomainCategory = DomainCategory.CUSTOM,
  val ruleType: DomainRuleType = DomainRuleType.BLOCK,
) {
  init {
    require(displayName.isNotBlank()) { "BlockedDomain displayName must not be blank" }
  }
}

enum class DatasetTrustStatus {
  MISSING_OR_EMPTY,
  INSTALLED_VERIFIED,
  INVALID_SIGNATURE,
  CORRUPT_OR_REJECTED,
  REMOTE_UPDATES_DISABLED,
}

data class FilterDatasetMetadata(
  val datasetId: String,
  val category: DomainCategory,
  val sourceName: String,
  val sourceUrl: String? = null,
  val license: String,
  val versionCode: Long,
  val updatedAtEpochMillis: Long? = null,
  val contentSha256: String? = null,
  val signatureAlgorithm: String? = null,
  val signatureValue: String? = null,
  val recordCount: Int = 0,
  val trustStatus: DatasetTrustStatus,
  val lastError: String? = null,
  val limitationsNote: String,
  val previousVersionCode: Long? = null,
  val previousContentSha256: String? = null,
) {
  val isUsable: Boolean
    get() = trustStatus == DatasetTrustStatus.INSTALLED_VERIFIED && recordCount > 0
}

enum class DatasetAvailabilityStatus {
  AVAILABLE,
  UPDATING,
  OUTDATED,
  UNAVAILABLE,
  FAILED,
}

object DatasetStatusEvaluator {
  const val DEFAULT_OUTDATED_AFTER_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L

  fun evaluate(
    metadata: FilterDatasetMetadata,
    isUpdating: Boolean,
    nowEpochMillis: Long,
    outdatedAfterMillis: Long = DEFAULT_OUTDATED_AFTER_MILLIS,
  ): DatasetAvailabilityStatus {
    if (isUpdating) return DatasetAvailabilityStatus.UPDATING

    if (
      metadata.trustStatus == DatasetTrustStatus.INVALID_SIGNATURE ||
        metadata.trustStatus == DatasetTrustStatus.CORRUPT_OR_REJECTED
    ) {
      return DatasetAvailabilityStatus.FAILED
    }

    if (metadata.isUsable && !metadata.lastError.isNullOrBlank()) {
      return DatasetAvailabilityStatus.FAILED
    }

    if (!metadata.isUsable) {
      return DatasetAvailabilityStatus.UNAVAILABLE
    }

    val updatedAt = metadata.updatedAtEpochMillis
    if (
      updatedAt != null &&
        nowEpochMillis >= updatedAt &&
        (nowEpochMillis - updatedAt) > outdatedAfterMillis
    ) {
      return DatasetAvailabilityStatus.OUTDATED
    }

    return DatasetAvailabilityStatus.AVAILABLE
  }
}

object DatasetSeedDefaults {
  const val ADULT_DATASET_ID = "builtin_placeholder_adult_content"
  const val SOCIAL_DATASET_ID = "builtin_placeholder_social_media"

  fun defaultMetadata(): List<FilterDatasetMetadata> =
    listOf(
      FilterDatasetMetadata(
        datasetId = ADULT_DATASET_ID,
        category = DomainCategory.ADULT_CONTENT,
        sourceName = "No curated signed feed configured",
        sourceUrl = null,
        license = "Unassigned (no external feed bundled)",
        versionCode = 0L,
        updatedAtEpochMillis = null,
        contentSha256 = null,
        signatureAlgorithm = null,
        signatureValue = null,
        recordCount = 0,
        trustStatus = DatasetTrustStatus.MISSING_OR_EMPTY,
        lastError =
          "Remote updates are disabled until a signed dataset source and pinned public verification key are configured.",
        limitationsNote =
          "Empty until a verified external dataset is imported. Do not rely on this category without an active verified dataset.",
      ),
      FilterDatasetMetadata(
        datasetId = SOCIAL_DATASET_ID,
        category = DomainCategory.SOCIAL_MEDIA,
        sourceName = "No curated signed feed configured",
        sourceUrl = null,
        license = "Unassigned (no external feed bundled)",
        versionCode = 0L,
        updatedAtEpochMillis = null,
        contentSha256 = null,
        signatureAlgorithm = null,
        signatureValue = null,
        recordCount = 0,
        trustStatus = DatasetTrustStatus.MISSING_OR_EMPTY,
        lastError =
          "Remote updates are disabled until a signed dataset source and pinned public verification key are configured.",
        limitationsNote =
          "Empty until a verified external dataset is imported. Custom domains remain available offline.",
      ),
    )
}
