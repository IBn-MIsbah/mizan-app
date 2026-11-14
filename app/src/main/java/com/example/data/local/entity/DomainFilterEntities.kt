package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "commitment_blocked_domains",
  foreignKeys = [
    ForeignKey(
      entity = CommitmentEntity::class,
      parentColumns = ["id"],
      childColumns = ["commitment_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["commitment_id", "normalized_domain", "rule_type"], unique = true),
  ],
)
data class CommitmentBlockedDomainEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  @ColumnInfo(name = "commitment_id") val commitmentId: String,
  @ColumnInfo(name = "normalized_domain") val normalizedDomain: String,
  @ColumnInfo(name = "display_domain") val displayDomain: String,
  @ColumnInfo(name = "category") val category: String,
  @ColumnInfo(name = "rule_type") val ruleType: String,
)

@Entity(
  tableName = "filter_datasets",
  indices = [
    Index(value = ["category"]),
  ],
)
data class FilterDatasetMetadataEntity(
  @PrimaryKey @ColumnInfo(name = "dataset_id") val datasetId: String,
  @ColumnInfo(name = "category") val category: String,
  @ColumnInfo(name = "source_name") val sourceName: String,
  @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
  @ColumnInfo(name = "license") val license: String,
  @ColumnInfo(name = "version_code") val versionCode: Long,
  @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long? = null,
  @ColumnInfo(name = "content_sha256") val contentSha256: String? = null,
  @ColumnInfo(name = "signature_algorithm") val signatureAlgorithm: String? = null,
  @ColumnInfo(name = "signature_value") val signatureValue: String? = null,
  @ColumnInfo(name = "record_count") val recordCount: Int = 0,
  @ColumnInfo(name = "trust_status") val trustStatus: String,
  @ColumnInfo(name = "last_error") val lastError: String? = null,
  @ColumnInfo(name = "limitations_note") val limitationsNote: String,
  @ColumnInfo(name = "previous_version_code") val previousVersionCode: Long? = null,
  @ColumnInfo(name = "previous_content_sha256") val previousContentSha256: String? = null,
)

@Entity(
  tableName = "filter_dataset_domains",
  foreignKeys = [
    ForeignKey(
      entity = FilterDatasetMetadataEntity::class,
      parentColumns = ["dataset_id"],
      childColumns = ["dataset_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["dataset_id", "generation"]),
    Index(value = ["dataset_id", "generation", "normalized_domain"], unique = true),
  ],
)
data class FilterDatasetDomainEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  @ColumnInfo(name = "dataset_id") val datasetId: String,
  @ColumnInfo(name = "normalized_domain") val normalizedDomain: String,
  @ColumnInfo(name = "generation") val generation: String,
)
