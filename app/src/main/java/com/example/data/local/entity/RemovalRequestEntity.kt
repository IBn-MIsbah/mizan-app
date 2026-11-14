package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "removal_requests",
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
    Index(value = ["requested_at_epochMillis"]),
    Index(value = ["status"]),
  ],
)
data class RemovalRequestEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "commitment_id") val commitmentId: String?,
  @ColumnInfo(name = "status") val status: String,
  @Embedded(prefix = "requested_at_") val requestedAt: WallClockPointEmbedded,
  @Embedded(prefix = "unlocks_at_") val unlocksAt: WallClockPointEmbedded,
  @ColumnInfo(name = "requested_monotonic_millis") val requestedMonotonicMillis: Long,
  @ColumnInfo(name = "boot_session_token") val bootSessionToken: String,
  @Embedded(prefix = "resolved_at_") val resolvedAt: WallClockPointEmbedded?,
  @ColumnInfo(name = "clock_anomaly_observed", defaultValue = "0")
  val clockAnomalyObserved: Boolean = false,
)
