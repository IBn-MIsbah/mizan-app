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
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.RestrictionMode
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import com.example.testing.FakeDispatcherProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MizanMigration4To5Test {

  private fun resolveSchemaFile(version: Int): File {
    val candidates =
      listOf(
        File("schemas/com.example.data.local.db.MizanDatabase/$version.json"),
        File("app/schemas/com.example.data.local.db.MizanDatabase/$version.json"),
      )
    return candidates.firstOrNull { it.exists() }
      ?: error("Could not locate Room schema $version.json from ${File(".").absolutePath}")
  }

  private fun createVersion4DatabaseFromExportedSchema(
    context: Context,
    dbName: String,
  ) {
    val schemaJson = Json.parseToJsonElement(resolveSchemaFile(4).readText()).jsonObject
    val dbObj = schemaJson["database"]!!.jsonObject
    val entities = dbObj["entities"]!!.jsonArray
    val setupQueries = dbObj["setupQueries"]!!.jsonArray

    val callback =
      object : SupportSQLiteOpenHelper.Callback(4) {
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
          'c-v4-1', 'ACTIVE',
          '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC',
          '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC',
          '2026-11-20', '09:00:00', 1795165200000, 0, 'UTC'
        )
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO commitment_rules (
          commitment_id, restriction_mode, daily_allowance_millis, require_pin_for_emergency,
          website_filtering_enabled, blocked_domain_categories_csv
        ) VALUES ('c-v4-1', 'HYBRID', 1800000, 0, 0, '')
        """.trimIndent()
      )
    }
    helper.close()
  }

  @Test
  fun `migrate 4 to 5 preserves commitments defaults block_youtube_shorts to false and saves new flag`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dbName = "mizan_migration_4_5_test.db"
      context.deleteDatabase(dbName)

      createVersion4DatabaseFromExportedSchema(context, dbName)

      val roomDb =
        Room.databaseBuilder(context, MizanDatabase::class.java, dbName)
          .addMigrations(
            DatabaseModule.MIGRATION_1_2,
            DatabaseModule.MIGRATION_2_3,
            DatabaseModule.MIGRATION_3_4,
            DatabaseModule.MIGRATION_4_5,
          )
          .allowMainThreadQueries()
          .build()

      try {
        val repo =
          RoomCommitmentRepository(
            commitmentDao = roomDb.commitmentDao(),
            dispatcherProvider = FakeDispatcherProvider(),
          )

        val existing =
          (repo.getCommitment(CommitmentId("c-v4-1")) as PersistenceResult.Success).data
            as Commitment.Active
        assertFalse(existing.rules.blockYouTubeShorts)

        val now =
          WallClockPoint(DomainDate(2026, 11, 14), DomainTime(10, 0, 0), 1_794_650_400_000L)
        val draftWithShorts =
          Commitment.Draft(
            id = CommitmentId("c-v5-draft"),
            createdAt = now,
            rules =
              CommitmentRules(
                mode = RestrictionMode.STRICT,
                restrictedApps = emptySet(),
                blockYouTubeShorts = true,
              ),
          )
        assertEquals(PersistenceResult.Success(Unit), repo.saveCommitment(draftWithShorts))

        val loadedDraft =
          (repo.getCommitment(CommitmentId("c-v5-draft")) as PersistenceResult.Success).data
            as Commitment.Draft
        assertTrue(loadedDraft.rules.blockYouTubeShorts)
      } finally {
        roomDb.close()
        context.deleteDatabase(dbName)
      }
    }
}
