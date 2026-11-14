package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.DatabaseModule
import com.example.data.local.db.MizanDatabase
import com.example.data.repository.RoomCommitmentRepository
import com.example.data.repository.RoomDailyUsageRepository
import com.example.data.repository.RoomDomainFilterRepository
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeTimeProvider
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MizanMigration2To3Test {

  private fun resolveSchemaFile(version: Int): File {
    val candidates =
      listOf(
        File("schemas/com.example.data.local.db.MizanDatabase/$version.json"),
        File("app/schemas/com.example.data.local.db.MizanDatabase/$version.json"),
      )
    return candidates.firstOrNull { it.exists() }
      ?: error("Could not locate Room schema $version.json from ${File(".").absolutePath}")
  }

  private fun createVersion2DatabaseFromExportedSchema(
    context: Context,
    dbName: String,
  ) {
    val schemaJson = Json.parseToJsonElement(resolveSchemaFile(2).readText()).jsonObject
    val dbObj = schemaJson["database"]!!.jsonObject
    val entities = dbObj["entities"]!!.jsonArray
    val setupQueries = dbObj["setupQueries"]!!.jsonArray

    val callback =
      object : SupportSQLiteOpenHelper.Callback(2) {
        override fun onCreate(db: SupportSQLiteDatabase) {
          entities.forEach { entityElement ->
            val entityObj = entityElement.jsonObject
            val tableName = entityObj["tableName"]!!.jsonPrimitive.content
            val createSql =
              entityObj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName)
            db.execSQL(createSql)
            entityObj["indices"]?.jsonArray?.forEach { indexElement ->
              val indexSql =
                indexElement.jsonObject["createSql"]!!.jsonPrimitive.content.replace(
                  "\${TABLE_NAME}",
                  tableName,
                )
              db.execSQL(indexSql)
            }
          }
          setupQueries.forEach { queryElement ->
            db.execSQL(queryElement.jsonPrimitive.content)
          }
        }

        override fun onUpgrade(
          db: SupportSQLiteDatabase,
          oldVersion: Int,
          newVersion: Int,
        ) = Unit
      }

    val helper =
      FrameworkSQLiteOpenHelperFactory()
        .create(
          SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        )

    helper.writableDatabase.use { db ->
      db.execSQL(
        """
        INSERT INTO commitments (
          id, lifecycle_state,
          created_at_dateIso, created_at_timeIso, created_at_epochMillis, created_at_utcOffsetMinutes, created_at_zoneId,
          activated_at_dateIso, activated_at_timeIso, activated_at_epochMillis, activated_at_utcOffsetMinutes, activated_at_zoneId,
          ends_at_dateIso, ends_at_timeIso, ends_at_epochMillis, ends_at_utcOffsetMinutes, ends_at_zoneId
        ) VALUES (
          'c-v2-1', 'ACTIVE',
          '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC',
          '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC',
          '2026-11-20', '09:00:00', 1795165200000, 0, 'UTC'
        )
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO commitment_rules (commitment_id, restriction_mode, daily_allowance_millis, require_pin_for_emergency)
        VALUES ('c-v2-1', 'HYBRID', 1800000, 0)
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO daily_usages (id, commitment_id, local_date, consumed_millis, coverage_status)
        VALUES (1, 'c-v2-1', '2026-11-13', 720000, 'MEASURED')
        """.trimIndent()
      )
    }
    helper.close()
  }

  @Test
  fun `migrate 2 to 3 preserves v2 commitment and usage rows and enables website filtering tables`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dbName = "mizan_migration_2_3_test.db"
      context.deleteDatabase(dbName)
      assertTrue(resolveSchemaFile(3).exists())

      createVersion2DatabaseFromExportedSchema(context, dbName)

      val roomDb =
        Room.databaseBuilder(context, MizanDatabase::class.java, dbName)
          .addMigrations(
            DatabaseModule.MIGRATION_1_2,
            DatabaseModule.MIGRATION_2_3,
            DatabaseModule.MIGRATION_3_4,
          )
          .allowMainThreadQueries()
          .build()

      try {
        val dispatchers = FakeDispatcherProvider()
        val timeProvider = FakeTimeProvider()
        val commitmentRepo =
          RoomCommitmentRepository(
            commitmentDao = roomDb.commitmentDao(),
            dispatcherProvider = dispatchers,
          )
        val usageRepo =
          RoomDailyUsageRepository(
            database = roomDb,
            dailyUsageDao = roomDb.dailyUsageDao(),
            dispatcherProvider = dispatchers,
          )

        val migratedCommitment =
          (commitmentRepo.getCommitment(CommitmentId("c-v2-1")) as PersistenceResult.Success).data
            as? Commitment.Active
        assertNotNull(migratedCommitment)
        assertFalse(migratedCommitment!!.rules.websiteFilteringEnabled)
        assertTrue(migratedCommitment.rules.blockedDomains.isEmpty())
        assertTrue(migratedCommitment.rules.blockedDomainCategories.isEmpty())

        val usage =
          (usageRepo.getDailyUsage(CommitmentId("c-v2-1"), DomainDate(2026, 11, 13))
              as PersistenceResult.Success)
            .data
        assertEquals(12.minutes, usage.consumed)

        val filterRepo =
          RoomDomainFilterRepository(
            domainFilterDao = roomDb.domainFilterDao(),
            signatureVerifier = com.example.domain.repository.NoTrustedKeySignatureVerifier(),
            timeProvider = timeProvider,
            dispatcherProvider = dispatchers,
          )
        assertTrue(filterRepo.ensureDefaultDatasetsInitialized() is PersistenceResult.Success)
        val datasets =
          (filterRepo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
              as PersistenceResult.Success)
            .data
        assertTrue(datasets.isEmpty())
        val meta = roomDb.domainFilterDao().getDatasetMetadata("builtin_placeholder_social_media")
        assertNotNull(meta)
        assertEquals(DatasetTrustStatus.MISSING_OR_EMPTY.name, meta!!.trustStatus)
      } finally {
        roomDb.close()
        context.deleteDatabase(dbName)
      }
    }
}
