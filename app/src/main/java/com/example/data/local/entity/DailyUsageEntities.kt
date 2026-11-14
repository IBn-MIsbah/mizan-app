package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One shared daily usage pool per `(commitment_id, local_date)`.
 */
@Entity(
  tableName = "daily_usages",
  foreignKeys = [
    ForeignKey(
      entity = CommitmentEntity::class,
      parentColumns = ["id"],
      childColumns = ["commitment_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["commitment_id", "local_date"], unique = true),
  ],
)
data class DailyUsageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  @ColumnInfo(name = "commitment_id") val commitmentId: String,
  @ColumnInfo(name = "local_date") val localDate: String,
  @ColumnInfo(name = "consumed_millis") val consumedMillis: Long,
  @ColumnInfo(name = "coverage_status", defaultValue = "MEASURED")
  val coverageStatus: String = "MEASURED",
  @ColumnInfo(name = "incomplete_reason") val incompleteReason: String? = null,
  @ColumnInfo(name = "last_reconciled_epoch_millis") val lastReconciledEpochMillis: Long? = null,
)

/**
 * Recorded usage event keyed by stable [eventId] to guarantee idempotent deduplication.
 */
@Entity(
  tableName = "usage_events",
  foreignKeys = [
    ForeignKey(
      entity = DailyUsageEntity::class,
      parentColumns = ["id"],
      childColumns = ["daily_usage_id"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = CommitmentEntity::class,
      parentColumns = ["id"],
      childColumns = ["commitment_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["daily_usage_id"]),
    Index(value = ["commitment_id", "local_date"]),
  ],
)
data class UsageEventEntity(
  @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
  @ColumnInfo(name = "daily_usage_id") val dailyUsageId: Long,
  @ColumnInfo(name = "commitment_id") val commitmentId: String,
  @ColumnInfo(name = "package_name") val packageName: String,
  @ColumnInfo(name = "foreground_duration_millis") val foregroundDurationMillis: Long,
  @ColumnInfo(name = "is_foreground") val isForeground: Boolean,
  @ColumnInfo(name = "local_date") val localDate: String,
  @ColumnInfo(name = "source", defaultValue = "ACCESSIBILITY_INTERVAL")
  val source: String = "ACCESSIBILITY_INTERVAL",
  @ColumnInfo(name = "started_at_epoch_millis") val startedAtEpochMillis: Long? = null,
  @ColumnInfo(name = "ended_at_epoch_millis") val endedAtEpochMillis: Long? = null,
  @ColumnInfo(name = "zone_id", defaultValue = "UTC") val zoneId: String = "UTC",
  @ColumnInfo(name = "boot_session_token") val bootSessionToken: String? = null,
)

data class DailyUsageWithEvents(
  @Embedded val dailyUsage: DailyUsageEntity,
  @Relation(parentColumn = "id", entityColumn = "daily_usage_id")
  val events: List<UsageEventEntity>,
)

/**
 * Singleton checkpoint persisting any currently open foreground usage session so process death,
 * interruption, or device reboot can be detected and recovered conservatively without fabricating time.
 */
@Entity(
  tableName = "active_usage_sessions",
  foreignKeys = [
    ForeignKey(
      entity = CommitmentEntity::class,
      parentColumns = ["id"],
      childColumns = ["commitment_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["commitment_id"]),
  ],
)
data class ActiveUsageSessionEntity(
  @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLETON_ID,
  @ColumnInfo(name = "commitment_id") val commitmentId: String,
  @ColumnInfo(name = "package_name") val packageName: String,
  @Embedded(prefix = "started_at_") val startedAt: WallClockPointEmbedded,
  @ColumnInfo(name = "started_monotonic_millis") val startedMonotonicMillis: Long,
  @ColumnInfo(name = "boot_session_token") val bootSessionToken: String,
) {
  companion object {
    const val SINGLETON_ID = 1
  }
}

/**
 * Singleton checkpoint storing the most recent observed wall-clock, monotonic, and boot identity
 * to detect suspicious wall-clock jumps and device reboots conservatively.
 */
@Entity(tableName = "usage_integrity_state")
data class UsageIntegrityStateEntity(
  @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLETON_ID,
  @ColumnInfo(name = "last_wall_epoch_millis") val lastWallEpochMillis: Long,
  @ColumnInfo(name = "last_monotonic_millis") val lastMonotonicMillis: Long,
  @ColumnInfo(name = "last_boot_session_token") val lastBootSessionToken: String,
  @ColumnInfo(name = "last_wall_date_iso") val lastWallDateIso: String,
  @ColumnInfo(name = "last_wall_time_iso") val lastWallTimeIso: String,
  @ColumnInfo(name = "last_zone_id") val lastZoneId: String,
  @ColumnInfo(name = "last_utc_offset_minutes") val lastUtcOffsetMinutes: Int,
) {
  companion object {
    const val SINGLETON_ID = 1
  }
}
