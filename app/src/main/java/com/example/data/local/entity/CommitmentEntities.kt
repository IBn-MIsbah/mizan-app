package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
  tableName = "commitments",
  indices = [
    Index(value = ["lifecycle_state"]),
  ],
)
data class CommitmentEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "lifecycle_state") val lifecycleState: String,
  @Embedded(prefix = "created_at_") val createdAt: WallClockPointEmbedded,
  @Embedded(prefix = "activated_at_") val activatedAt: WallClockPointEmbedded?,
  @Embedded(prefix = "ends_at_") val endsAt: WallClockPointEmbedded?,
  @Embedded(prefix = "completed_at_") val completedAt: WallClockPointEmbedded?,
  @ColumnInfo(name = "next_step_choice") val nextStepChoice: String?,
  @Embedded(prefix = "terminated_at_") val terminatedAt: WallClockPointEmbedded?,
  @ColumnInfo(name = "pin_verified") val pinVerified: Boolean?,
)

@Entity(
  tableName = "commitment_rules",
  foreignKeys = [
    ForeignKey(
      entity = CommitmentEntity::class,
      parentColumns = ["id"],
      childColumns = ["commitment_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
)
data class CommitmentRulesEntity(
  @PrimaryKey @ColumnInfo(name = "commitment_id") val commitmentId: String,
  @ColumnInfo(name = "restriction_mode") val restrictionMode: String,
  @ColumnInfo(name = "daily_allowance_millis") val dailyAllowanceMillis: Long,
  @ColumnInfo(name = "require_pin_for_emergency") val requirePinForEmergencyTermination: Boolean,
  @ColumnInfo(name = "website_filtering_enabled", defaultValue = "0")
  val websiteFilteringEnabled: Boolean = false,
  @ColumnInfo(name = "blocked_domain_categories_csv", defaultValue = "")
  val blockedDomainCategoriesCsv: String = "",
  @ColumnInfo(name = "block_youtube_shorts", defaultValue = "0")
  val blockYouTubeShorts: Boolean = false,
)

@Entity(
  tableName = "restricted_apps",
  foreignKeys = [
    ForeignKey(
      entity = CommitmentEntity::class,
      parentColumns = ["id"],
      childColumns = ["commitment_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["commitment_id", "package_name"], unique = true),
  ],
)
data class RestrictedAppEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  @ColumnInfo(name = "commitment_id") val commitmentId: String,
  @ColumnInfo(name = "package_name") val packageName: String,
  @ColumnInfo(name = "display_label") val displayLabel: String,
)

@Entity(
  tableName = "allowed_apps",
  foreignKeys = [
    ForeignKey(
      entity = CommitmentEntity::class,
      parentColumns = ["id"],
      childColumns = ["commitment_id"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["commitment_id", "package_name"], unique = true),
  ],
)
data class AllowedAppEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  @ColumnInfo(name = "commitment_id") val commitmentId: String,
  @ColumnInfo(name = "package_name") val packageName: String,
  @ColumnInfo(name = "display_label") val displayLabel: String,
  @ColumnInfo(name = "category") val category: String,
)

@Entity(
  tableName = "schedule_windows",
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
data class ScheduleWindowEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  @ColumnInfo(name = "commitment_id") val commitmentId: String,
  @ColumnInfo(name = "window_order") val windowOrder: Int,
  @ColumnInfo(name = "days_of_week_csv") val daysOfWeekCsv: String,
  @ColumnInfo(name = "start_hour") val startHour: Int,
  @ColumnInfo(name = "start_minute") val startMinute: Int,
  @ColumnInfo(name = "start_second") val startSecond: Int,
  @ColumnInfo(name = "end_hour") val endHour: Int,
  @ColumnInfo(name = "end_minute") val endMinute: Int,
  @ColumnInfo(name = "end_second") val endSecond: Int,
)

data class CommitmentWithRelations(
  @Embedded val commitment: CommitmentEntity,
  @Relation(parentColumn = "id", entityColumn = "commitment_id")
  val rules: CommitmentRulesEntity?,
  @Relation(parentColumn = "id", entityColumn = "commitment_id")
  val restrictedApps: List<RestrictedAppEntity>,
  @Relation(parentColumn = "id", entityColumn = "commitment_id")
  val allowedApps: List<AllowedAppEntity>,
  @Relation(parentColumn = "id", entityColumn = "commitment_id")
  val scheduleWindows: List<ScheduleWindowEntity>,
  @Relation(parentColumn = "id", entityColumn = "commitment_id")
  val blockedDomains: List<CommitmentBlockedDomainEntity> = emptyList(),
)
