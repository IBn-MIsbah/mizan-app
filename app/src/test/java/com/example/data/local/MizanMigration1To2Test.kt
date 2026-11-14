package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.di.DatabaseModule
import com.example.data.local.db.MizanDatabase
import com.example.data.repository.RoomDailyUsageRepository
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentId
import com.example.domain.model.PersistedActiveUsageSession
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageIntegrityCheckpoint
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import com.example.testing.FakeDispatcherProvider
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MizanMigration1To2Test {

  private fun resolveSchemaFile(version: Int): File {
    val candidates =
      listOf(
        File("schemas/com.example.data.local.db.MizanDatabase/$version.json"),
        File("app/schemas/com.example.data.local.db.MizanDatabase/$version.json"),
      )
    return candidates.firstOrNull { it.exists() }
      ?: error("Could not locate Room schema $version.json from ${File(".").absolutePath}")
  }

  private fun createVersion1DatabaseFromExportedSchema(
    context: Context,
    dbName: String,
  ) {
    val schemaJson = Json.parseToJsonElement(resolveSchemaFile(1).readText()).jsonObject
    val dbObj = schemaJson["database"]!!.jsonObject
    val entities = dbObj["entities"]!!.jsonArray
    val setupQueries = dbObj["setupQueries"]!!.jsonArray

    val callback =
      object : SupportSQLiteOpenHelper.Callback(1) {
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
          'c-mig-1', 'ACTIVE',
          '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC',
          '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC',
          '2026-11-20', '09:00:00', 1795165200000, 0, 'UTC'
        )
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO commitment_rules (commitment_id, restriction_mode, daily_allowance_millis, require_pin_for_emergency)
        VALUES ('c-mig-1', 'HYBRID', 1800000, 0)
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO daily_usages (id, commitment_id, local_date, consumed_millis)
        VALUES (1, 'c-mig-1', '2026-11-13', 600000)
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO usage_events (event_id, daily_usage_id, commitment_id, package_name, foreground_duration_millis, is_foreground, local_date)
        VALUES ('v1-evt-1', 1, 'c-mig-1', 'com.instagram.android', 600000, 1, '2026-11-13')
        """.trimIndent()
      )
    }
    helper.close()
  }

  @Test
  fun `migrate 1 to 2 preserves existing commitment and usage data and enables v2 tables`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dbName = "mizan_migration_test.db"
      context.deleteDatabase(dbName)
      assertTrue(resolveSchemaFile(2).exists())

      // 1. Seed authentic SQLite v1 DB directly from exported 1.json schema
      createVersion1DatabaseFromExportedSchema(context, dbName)

      // 2. Open with Room at version 2 + explicit MIGRATION_1_2 (triggers Room onValidateSchema)
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
        val repo =
          RoomDailyUsageRepository(
            database = roomDb,
            dailyUsageDao = roomDb.dailyUsageDao(),
            dispatcherProvider = FakeDispatcherProvider(),
          )

        val date = DomainDate(2026, 11, 13)
        val loadedUsage =
          (repo.getDailyUsage(CommitmentId("c-mig-1"), date) as PersistenceResult.Success).data
        assertEquals(10.minutes, loadedUsage.consumed)
        assertEquals(UsageCoverageStatus.MEASURED, loadedUsage.coverageStatus)

        // New v2 active session checkpoint + integrity checkpoint tables persist and clear cleanly
        val startClock = WallClockPoint(date, DomainTime(11, 0, 0), 1_794_567_600_000L)
        val session =
          PersistedActiveUsageSession(
            commitmentId = CommitmentId("c-mig-1"),
            appId = AppId("com.instagram.android"),
            startedAtWallClock = startClock,
            startedAtMonotonic = MonotonicTimestamp(42_000L),
            bootSessionToken = "boot:7",
          )
        assertTrue(repo.saveActiveUsageSession(session) is PersistenceResult.Success)
        val readSession = (repo.getActiveUsageSession() as PersistenceResult.Success).data
        assertNotNull(readSession)
        assertEquals("com.instagram.android", readSession!!.appId.packageName)
        assertEquals("boot:7", readSession.bootSessionToken)

        assertTrue(repo.clearActiveUsageSession() is PersistenceResult.Success)
        assertNull((repo.getActiveUsageSession() as PersistenceResult.Success).data)

        val checkpoint =
          UsageIntegrityCheckpoint(
            lastWallClock = startClock,
            lastMonotonic = MonotonicTimestamp(45_000L),
            bootSessionToken = "boot:7",
          )
        assertTrue(repo.saveIntegrityCheckpoint(checkpoint) is PersistenceResult.Success)
        assertEquals(
          checkpoint,
          (repo.getIntegrityCheckpoint() as PersistenceResult.Success).data,
        )

        val incomplete =
          (repo.markDayIncomplete(CommitmentId("c-mig-1"), date, "Test gap")
              as PersistenceResult.Success)
            .data
        assertEquals(UsageCoverageStatus.INCOMPLETE, incomplete.coverageStatus)
        assertEquals("Test gap", incomplete.incompleteReason)
        assertEquals(10.minutes, incomplete.consumed)

        val reconciled =
          (repo.markReconciliationAttempt(
              commitmentId = CommitmentId("c-mig-1"),
              date = date,
              reconciledAtEpochMillis = 1_794_570_000_000L,
              coverageStatus = UsageCoverageStatus.MEASURED_AND_RECONCILED,
              incompleteReason = null,
            ) as PersistenceResult.Success)
            .data
        // Existing INCOMPLETE status is preserved safely rather than silently hidden
        assertEquals(UsageCoverageStatus.INCOMPLETE, reconciled.coverageStatus)
        assertEquals(1_794_570_000_000L, reconciled.lastReconciledEpochMillis)
      } finally {
        roomDb.close()
        context.deleteDatabase(dbName)
      }
    }
}
