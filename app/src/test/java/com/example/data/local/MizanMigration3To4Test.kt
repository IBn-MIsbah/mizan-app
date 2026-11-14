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
import com.example.data.repository.RoomRemovalRequestRepository
import com.example.domain.lifecycle.RemovalRequestStateMachine
import com.example.domain.model.CommitmentId
import com.example.domain.model.RemovalRequestId
import com.example.domain.model.RemovalRequestStatus
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MizanMigration3To4Test {

  private fun resolveSchemaFile(version: Int): File {
    val candidates =
      listOf(
        File("schemas/com.example.data.local.db.MizanDatabase/$version.json"),
        File("app/schemas/com.example.data.local.db.MizanDatabase/$version.json"),
      )
    return candidates.firstOrNull { it.exists() }
      ?: error("Could not locate Room schema $version.json from ${File(".").absolutePath}")
  }

  private fun createVersion3DatabaseFromExportedSchema(
    context: Context,
    dbName: String,
  ) {
    val schemaJson = Json.parseToJsonElement(resolveSchemaFile(3).readText()).jsonObject
    val dbObj = schemaJson["database"]!!.jsonObject
    val entities = dbObj["entities"]!!.jsonArray
    val setupQueries = dbObj["setupQueries"]!!.jsonArray

    val callback =
      object : SupportSQLiteOpenHelper.Callback(3) {
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
          'c-v3-1', 'ACTIVE',
          '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC',
          '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC',
          '2026-11-20', '09:00:00', 1795165200000, 0, 'UTC'
        )
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO commitment_rules (commitment_id, restriction_mode, daily_allowance_millis, require_pin_for_emergency)
        VALUES ('c-v3-1', 'HYBRID', 1800000, 0)
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO daily_usages (id, commitment_id, local_date, consumed_millis, coverage_status)
        VALUES (1, 'c-v3-1', '2026-11-13', 900000, 'MEASURED')
        """.trimIndent()
      )
    }
    helper.close()
  }

  @Test
  fun `migrate 3 to 4 preserves usage rows defaults blocked_attempts to 0 and enables removal_requests`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dbName = "mizan_migration_3_4_test.db"
      context.deleteDatabase(dbName)
      assertTrue(resolveSchemaFile(4).exists())

      createVersion3DatabaseFromExportedSchema(context, dbName)

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
        val usageRepo =
          RoomDailyUsageRepository(
            database = roomDb,
            dailyUsageDao = roomDb.dailyUsageDao(),
            dispatcherProvider = dispatchers,
          )
        val removalRepo =
          RoomRemovalRequestRepository(
            removalRequestDao = roomDb.removalRequestDao(),
            dispatcherProvider = dispatchers,
          )

        val date = DomainDate(2026, 11, 13)
        val loaded =
          (usageRepo.getDailyUsage(CommitmentId("c-v3-1"), date) as PersistenceResult.Success).data
        assertEquals(15.minutes, loaded.consumed)
        assertEquals(0, loaded.blockedAttempts)

        val attemptResult = usageRepo.recordBlockedAttempt(CommitmentId("c-v3-1"), date)
        assertEquals(PersistenceResult.Success(Unit), attemptResult)
        val afterAttempt =
          (usageRepo.getDailyUsage(CommitmentId("c-v3-1"), date) as PersistenceResult.Success).data
        assertEquals(1, afterAttempt.blockedAttempts)

        val req =
          RemovalRequestStateMachine.createRequest(
            id = RemovalRequestId("rem-mig-1"),
            commitmentId = CommitmentId("c-v3-1"),
            nowWall =
              WallClockPoint(date, DomainTime(12, 0, 0), 1_794_571_200_000L),
            nowMonotonic = MonotonicTimestamp(12_000L),
            bootSessionToken = "boot:1",
          )
        assertEquals(PersistenceResult.Success(Unit), removalRepo.saveRequest(req))
        val open = (removalRepo.getLatestOpenRequest() as PersistenceResult.Success).data
        assertNotNull(open)
        assertEquals(RemovalRequestStatus.PENDING, open!!.status)
      } finally {
        roomDb.close()
        context.deleteDatabase(dbName)
      }
    }
}
