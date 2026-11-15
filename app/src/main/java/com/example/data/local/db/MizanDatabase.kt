package com.example.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.data.local.dao.AccountabilityMetadataDao
import com.example.data.local.dao.CommitmentDao
import com.example.data.local.dao.DailyUsageDao
import com.example.data.local.dao.DomainFilterDao
import com.example.data.local.dao.RemovalRequestDao
import com.example.data.local.dao.SecurityEventDao
import com.example.data.local.entity.AccountabilityMetadataEntity
import com.example.data.local.entity.ActiveUsageSessionEntity
import com.example.data.local.entity.AllowedAppEntity
import com.example.data.local.entity.CommitmentBlockedDomainEntity
import com.example.data.local.entity.CommitmentEntity
import com.example.data.local.entity.CommitmentRulesEntity
import com.example.data.local.entity.DailyUsageEntity
import com.example.data.local.entity.FilterDatasetDomainEntity
import com.example.data.local.entity.FilterDatasetMetadataEntity
import com.example.data.local.entity.RemovalRequestEntity
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.ScheduleWindowEntity
import com.example.data.local.entity.SecurityEventEntity
import com.example.data.local.entity.UsageEventEntity
import com.example.data.local.entity.UsageIntegrityStateEntity

/**
 * MIZAN local SQLite database (version 5).
 *
 * Schema management policy:
 * - `exportSchema = true` exports schema JSON files to `app/schemas/` under version control.
 * - Destructive migrations are never enabled in production builds.
 * - Version 2 extends `daily_usages` and `usage_events` and adds `active_usage_sessions` and
 *   `usage_integrity_state` via `MIGRATION_1_2`.
 * - Version 3 extends `commitment_rules` and adds `commitment_blocked_domains`, `filter_datasets`,
 *   and `filter_dataset_domains` via `MIGRATION_2_3`.
 * - Version 4 extends `daily_usages` (`blocked_attempts`) and adds `removal_requests` via
 *   `MIGRATION_3_4`.
 * - Version 5 adds `block_youtube_shorts` to `commitment_rules` via `MIGRATION_4_5`.
 */
@Database(
  entities = [
    CommitmentEntity::class,
    CommitmentRulesEntity::class,
    RestrictedAppEntity::class,
    AllowedAppEntity::class,
    ScheduleWindowEntity::class,
    CommitmentBlockedDomainEntity::class,
    FilterDatasetMetadataEntity::class,
    FilterDatasetDomainEntity::class,
    DailyUsageEntity::class,
    UsageEventEntity::class,
    ActiveUsageSessionEntity::class,
    UsageIntegrityStateEntity::class,
    SecurityEventEntity::class,
    AccountabilityMetadataEntity::class,
    RemovalRequestEntity::class,
  ],
  version = 5,
  exportSchema = true,
)
abstract class MizanDatabase : RoomDatabase() {
  abstract fun commitmentDao(): CommitmentDao

  abstract fun domainFilterDao(): DomainFilterDao

  abstract fun dailyUsageDao(): DailyUsageDao

  abstract fun securityEventDao(): SecurityEventDao

  abstract fun accountabilityMetadataDao(): AccountabilityMetadataDao

  abstract fun removalRequestDao(): RemovalRequestDao

  companion object {
    const val DATABASE_NAME = "mizan.db"
  }
}
