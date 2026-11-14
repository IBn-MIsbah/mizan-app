package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "security_events",
  foreignKeys = [
    ForeignKey(
      entity = CommitmentEntity::class,
      parentColumns = ["id"],
      childColumns = ["commitment_id"],
      onDelete = ForeignKey.SET_NULL,
    ),
  ],
  indices = [
    Index(value = ["commitment_id"]),
    Index(value = ["occurred_at_epochMillis"]),
  ],
)
data class SecurityEventEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "commitment_id") val commitmentId: String?,
  @ColumnInfo(name = "event_type") val eventType: String,
  @Embedded(prefix = "occurred_at_") val occurredAt: WallClockPointEmbedded,
  @ColumnInfo(name = "summary") val summary: String?,
)

/**
 * Non-secret singleton accountability metadata row. Never stores PINs, hashes, or salts.
 */
@Entity(tableName = "accountability_metadata")
data class AccountabilityMetadataEntity(
  @PrimaryKey val id: Int = SINGLETON_ID,
  @ColumnInfo(name = "is_pin_enabled") val isPinEnabled: Boolean,
  @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
) {
  companion object {
    const val SINGLETON_ID = 1
  }
}
