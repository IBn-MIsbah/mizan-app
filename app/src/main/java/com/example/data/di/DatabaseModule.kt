package com.example.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.AccountabilityMetadataDao
import com.example.data.local.dao.CommitmentDao
import com.example.data.local.dao.DailyUsageDao
import com.example.data.local.dao.DomainFilterDao
import com.example.data.local.dao.SecurityEventDao
import com.example.data.local.db.MizanDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

  private const val PREFERENCES_FILE_NAME = "mizan_preferences"

  val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `daily_usages` ADD COLUMN `coverage_status` TEXT NOT NULL DEFAULT 'MEASURED'"
        )
        db.execSQL("ALTER TABLE `daily_usages` ADD COLUMN `incomplete_reason` TEXT")
        db.execSQL(
          "ALTER TABLE `daily_usages` ADD COLUMN `last_reconciled_epoch_millis` INTEGER"
        )

        db.execSQL(
          "ALTER TABLE `usage_events` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'ACCESSIBILITY_INTERVAL'"
        )
        db.execSQL("ALTER TABLE `usage_events` ADD COLUMN `started_at_epoch_millis` INTEGER")
        db.execSQL("ALTER TABLE `usage_events` ADD COLUMN `ended_at_epoch_millis` INTEGER")
        db.execSQL("ALTER TABLE `usage_events` ADD COLUMN `zone_id` TEXT NOT NULL DEFAULT 'UTC'")
        db.execSQL("ALTER TABLE `usage_events` ADD COLUMN `boot_session_token` TEXT")

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `active_usage_sessions` (
            `id` INTEGER NOT NULL,
            `commitment_id` TEXT NOT NULL,
            `package_name` TEXT NOT NULL,
            `started_monotonic_millis` INTEGER NOT NULL,
            `boot_session_token` TEXT NOT NULL,
            `started_at_dateIso` TEXT NOT NULL,
            `started_at_timeIso` TEXT NOT NULL,
            `started_at_epochMillis` INTEGER NOT NULL,
            `started_at_utcOffsetMinutes` INTEGER NOT NULL,
            `started_at_zoneId` TEXT NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`commitment_id`) REFERENCES `commitments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
          )
          """.trimIndent()
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_active_usage_sessions_commitment_id` ON `active_usage_sessions` (`commitment_id`)"
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `usage_integrity_state` (
            `id` INTEGER NOT NULL,
            `last_wall_epoch_millis` INTEGER NOT NULL,
            `last_monotonic_millis` INTEGER NOT NULL,
            `last_boot_session_token` TEXT NOT NULL,
            `last_wall_date_iso` TEXT NOT NULL,
            `last_wall_time_iso` TEXT NOT NULL,
            `last_zone_id` TEXT NOT NULL,
            `last_utc_offset_minutes` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
          )
          """.trimIndent()
        )
      }
    }

  val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `commitment_rules` ADD COLUMN `website_filtering_enabled` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
          "ALTER TABLE `commitment_rules` ADD COLUMN `blocked_domain_categories_csv` TEXT NOT NULL DEFAULT ''"
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `commitment_blocked_domains` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `commitment_id` TEXT NOT NULL,
            `normalized_domain` TEXT NOT NULL,
            `display_domain` TEXT NOT NULL,
            `category` TEXT NOT NULL,
            `rule_type` TEXT NOT NULL,
            FOREIGN KEY(`commitment_id`) REFERENCES `commitments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
          )
          """.trimIndent()
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_commitment_blocked_domains_commitment_id_normalized_domain_rule_type` ON `commitment_blocked_domains` (`commitment_id`, `normalized_domain`, `rule_type`)"
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `filter_datasets` (
            `dataset_id` TEXT NOT NULL,
            `category` TEXT NOT NULL,
            `source_name` TEXT NOT NULL,
            `source_url` TEXT,
            `license` TEXT NOT NULL,
            `version_code` INTEGER NOT NULL,
            `updated_at_epoch_millis` INTEGER,
            `content_sha256` TEXT,
            `signature_algorithm` TEXT,
            `signature_value` TEXT,
            `record_count` INTEGER NOT NULL,
            `trust_status` TEXT NOT NULL,
            `last_error` TEXT,
            `limitations_note` TEXT NOT NULL,
            `previous_version_code` INTEGER,
            `previous_content_sha256` TEXT,
            PRIMARY KEY(`dataset_id`)
          )
          """.trimIndent()
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_filter_datasets_category` ON `filter_datasets` (`category`)"
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `filter_dataset_domains` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `dataset_id` TEXT NOT NULL,
            `normalized_domain` TEXT NOT NULL,
            `generation` TEXT NOT NULL,
            FOREIGN KEY(`dataset_id`) REFERENCES `filter_datasets`(`dataset_id`) ON UPDATE NO ACTION ON DELETE CASCADE
          )
          """.trimIndent()
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_filter_dataset_domains_dataset_id_generation` ON `filter_dataset_domains` (`dataset_id`, `generation`)"
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_filter_dataset_domains_dataset_id_generation_normalized_domain` ON `filter_dataset_domains` (`dataset_id`, `generation`, `normalized_domain`)"
        )
      }
    }

  /**
   * Provides the local Room database without destructive migrations so user data is never
   * silently dropped on upgrades. Explicit [androidx.room.migration.Migration] instances must
   * be registered here whenever [MizanDatabase] advances.
   */
  @Provides
  @Singleton
  fun provideMizanDatabase(@ApplicationContext context: Context): MizanDatabase =
    Room.databaseBuilder(
        context,
        MizanDatabase::class.java,
        MizanDatabase.DATABASE_NAME,
      )
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
      .build()

  @Provides
  fun provideCommitmentDao(database: MizanDatabase): CommitmentDao = database.commitmentDao()

  @Provides
  fun provideDomainFilterDao(database: MizanDatabase): DomainFilterDao = database.domainFilterDao()

  @Provides
  fun provideDailyUsageDao(database: MizanDatabase): DailyUsageDao = database.dailyUsageDao()

  @Provides
  fun provideSecurityEventDao(database: MizanDatabase): SecurityEventDao =
    database.securityEventDao()

  @Provides
  fun provideAccountabilityMetadataDao(database: MizanDatabase): AccountabilityMetadataDao =
    database.accountabilityMetadataDao()

  @Provides
  @Singleton
  fun providePreferencesDataStore(
    @ApplicationContext context: Context,
  ): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
      corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = { emptyPreferences() }),
      produceFile = { context.preferencesDataStoreFile(PREFERENCES_FILE_NAME) },
    )
}
