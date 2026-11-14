package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.MizanDatabase
import com.example.data.local.entity.AccountabilityMetadataEntity
import com.example.data.local.entity.AllowedAppEntity
import com.example.data.local.entity.CommitmentEntity
import com.example.data.local.entity.CommitmentRulesEntity
import com.example.data.local.entity.DailyUsageEntity
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.ScheduleWindowEntity
import com.example.data.local.entity.SecurityEventEntity
import com.example.data.local.entity.UsageEventEntity
import com.example.data.local.entity.WallClockPointEmbedded
import com.example.data.local.mapper.CorruptLocalDataException
import com.example.data.local.mapper.PersistenceMappers.toDomain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MizanDatabaseAndDaoTest {

  private lateinit var database: MizanDatabase

  private val baseClock =
    WallClockPointEmbedded(
      dateIso = "2026-11-13",
      timeIso = "09:00:00",
      epochMillis = 1_794_560_400_000L,
      utcOffsetMinutes = 0,
      zoneId = "UTC",
    )

  private val endClock =
    WallClockPointEmbedded(
      dateIso = "2026-11-20",
      timeIso = "09:00:00",
      epochMillis = 1_795_165_200_000L,
      utcOffsetMinutes = 0,
      zoneId = "UTC",
    )

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, MizanDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `initial version 1 database opens cleanly and supports full commitment graph`() = runTest {
    val commitmentDao = database.commitmentDao()
    val commitment =
      CommitmentEntity(
        id = "c-1",
        lifecycleState = "ACTIVE",
        createdAt = baseClock,
        activatedAt = baseClock,
        endsAt = endClock,
        completedAt = null,
        nextStepChoice = null,
        terminatedAt = null,
        pinVerified = null,
      )
    val rules =
      CommitmentRulesEntity(
        commitmentId = "c-1",
        restrictionMode = "HYBRID",
        dailyAllowanceMillis = 1_800_000L,
        requirePinForEmergencyTermination = true,
      )
    val restricted =
      listOf(RestrictedAppEntity(commitmentId = "c-1", packageName = "com.instagram.android", displayLabel = "Instagram"))
    val allowed =
      listOf(AllowedAppEntity(commitmentId = "c-1", packageName = "com.android.dialer", displayLabel = "Phone", category = "EMERGENCY_PHONE"))
    val schedules =
      listOf(
        ScheduleWindowEntity(
          commitmentId = "c-1",
          windowOrder = 0,
          daysOfWeekCsv = "MONDAY,FRIDAY",
          startHour = 22,
          startMinute = 0,
          startSecond = 0,
          endHour = 6,
          endMinute = 0,
          endSecond = 0,
        )
      )

    commitmentDao.saveFullCommitment(commitment, rules, restricted, allowed, schedules)

    val loaded = commitmentDao.getCommitmentWithRelations("c-1")
    assertNotNull(loaded)
    assertEquals("ACTIVE", loaded!!.commitment.lifecycleState)
    assertEquals(1, loaded.restrictedApps.size)
    assertEquals(1, loaded.allowedApps.size)
    assertEquals(1, loaded.scheduleWindows.size)
    assertEquals(1_800_000L, loaded.rules?.dailyAllowanceMillis)
  }

  @Test(expected = SQLiteConstraintException::class)
  fun `duplicate restricted app for same commitment violates unique constraint`() = runTest {
    val dao = database.commitmentDao()
    dao.saveFullCommitment(
      commitment =
        CommitmentEntity("c-1", "DRAFT", baseClock, null, null, null, null, null, null),
      rules = CommitmentRulesEntity("c-1", "STRICT", 1_800_000L, false),
      restrictedApps =
        listOf(
          RestrictedAppEntity(commitmentId = "c-1", packageName = "com.example.app", displayLabel = "App"),
          RestrictedAppEntity(commitmentId = "c-1", packageName = "com.example.app", displayLabel = "App"),
        ),
      allowedApps = emptyList(),
      scheduleWindows = emptyList(),
    )
  }

  @Test(expected = SQLiteConstraintException::class)
  fun `foreign key prevents inserting rules without parent commitment`() = runTest {
    database.commitmentDao().insertRules(
      CommitmentRulesEntity("missing-id", "STRICT", 1_800_000L, false)
    )
  }

  @Test(expected = SQLiteConstraintException::class)
  fun `unique constraint prevents multiple daily usage rows for same commitment and date`() = runTest {
    database.commitmentDao().insertCommitment(
      CommitmentEntity("c-1", "ACTIVE", baseClock, baseClock, endClock, null, null, null, null)
    )
    val usageDao = database.dailyUsageDao()
    usageDao.insertDailyUsage(DailyUsageEntity(commitmentId = "c-1", localDate = "2026-11-13", consumedMillis = 1000L))
    usageDao.insertDailyUsage(DailyUsageEntity(commitmentId = "c-1", localDate = "2026-11-13", consumedMillis = 2000L))
  }

  @Test
  fun `duplicate usage event id is ignored by insertUsageEventIgnore`() = runTest {
    database.commitmentDao().insertCommitment(
      CommitmentEntity("c-1", "ACTIVE", baseClock, baseClock, endClock, null, null, null, null)
    )
    val usageDao = database.dailyUsageDao()
    val usageId = usageDao.insertDailyUsage(DailyUsageEntity(commitmentId = "c-1", localDate = "2026-11-13", consumedMillis = 0L))

    val event =
      UsageEventEntity(
        eventId = "evt-1",
        dailyUsageId = usageId,
        commitmentId = "c-1",
        packageName = "com.instagram.android",
        foregroundDurationMillis = 60_000L,
        isForeground = true,
        localDate = "2026-11-13",
      )

    val firstRowId = usageDao.insertUsageEventIgnore(event)
    val duplicateRowId = usageDao.insertUsageEventIgnore(event)

    assertTrue(firstRowId > 0L)
    assertEquals(-1L, duplicateRowId)
  }

  @Test
  fun `transaction rolls back completely when multi step write fails`() = runTest {
    val dao = database.commitmentDao()
    try {
      database.withTransaction {
        dao.insertCommitment(
          CommitmentEntity("c-tx", "DRAFT", baseClock, null, null, null, null, null, null)
        )
        // Duplicate restricted app triggers constraint exception inside transaction
        dao.insertRestrictedApps(
          listOf(
            RestrictedAppEntity(commitmentId = "c-tx", packageName = "com.dup", displayLabel = "Dup"),
            RestrictedAppEntity(commitmentId = "c-tx", packageName = "com.dup", displayLabel = "Dup"),
          )
        )
      }
    } catch (_: SQLiteConstraintException) {
      // Expected rollback trigger
    }

    assertNull(dao.getCommitmentWithRelations("c-tx"))
  }

  @Test
  fun `deleting commitment cascades usage and sets security event commitmentId to null`() = runTest {
    val commitmentDao = database.commitmentDao()
    commitmentDao.saveFullCommitment(
      commitment =
        CommitmentEntity("c-del", "ACTIVE", baseClock, baseClock, endClock, null, null, null, null),
      rules = CommitmentRulesEntity("c-del", "HYBRID", 1_800_000L, false),
      restrictedApps = emptyList(),
      allowedApps = emptyList(),
      scheduleWindows = emptyList(),
    )
    val usageId =
      database.dailyUsageDao().insertDailyUsage(
        DailyUsageEntity(commitmentId = "c-del", localDate = "2026-11-13", consumedMillis = 5000L)
      )
    database.dailyUsageDao().insertUsageEventIgnore(
      UsageEventEntity("evt-del", usageId, "c-del", "com.pkg", 5000L, true, "2026-11-13")
    )
    database.securityEventDao().insert(
      SecurityEventEntity("sec-1", "c-del", "COMMITMENT_ACTIVATED", baseClock, "Activated")
    )

    val deletedCount = commitmentDao.deleteCommitmentById("c-del")

    assertEquals(1, deletedCount)
    assertNull(database.dailyUsageDao().getDailyUsageWithEvents("c-del", "2026-11-13"))
    val events = database.securityEventDao().observeAll().first()
    assertEquals(1, events.size)
    assertNull(events.first().commitmentId)
  }

  @Test
  fun `accountability metadata and security events round trip cleanly`() = runTest {
    val metadataDao = database.accountabilityMetadataDao()
    metadataDao.upsert(AccountabilityMetadataEntity(isPinEnabled = true, updatedAtEpochMillis = 999L))
    val loadedMeta = metadataDao.observe().first()
    assertEquals(true, loadedMeta?.isPinEnabled)
    assertEquals(999L, loadedMeta?.updatedAtEpochMillis)

    val securityDao = database.securityEventDao()
    securityDao.insert(
      SecurityEventEntity("sec-a", null, "WALL_CLOCK_ANOMALY_OBSERVED", baseClock, null)
    )
    val events = securityDao.observeAll().first()
    assertEquals(1, events.size)
    assertEquals("WALL_CLOCK_ANOMALY_OBSERVED", events.first().eventType)
  }

  @Test(expected = CorruptLocalDataException::class)
  fun `unknown stored lifecycle state throws CorruptLocalDataException instead of silent fallback`() =
    runTest {
      val db = database.openHelper.writableDatabase
      db.execSQL(
        """
        INSERT INTO commitments (
          id, lifecycle_state,
          created_at_dateIso, created_at_timeIso, created_at_epochMillis, created_at_utcOffsetMinutes, created_at_zoneId
        ) VALUES ('bad-1', 'UNKNOWN_STATE', '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC')
        """.trimIndent()
      )
      db.execSQL(
        """
        INSERT INTO commitment_rules (commitment_id, restriction_mode, daily_allowance_millis, require_pin_for_emergency)
        VALUES ('bad-1', 'STRICT', 1800000, 0)
        """.trimIndent()
      )

      val loaded = database.commitmentDao().getCommitmentWithRelations("bad-1")!!
      loaded.toDomain()
    }
}
