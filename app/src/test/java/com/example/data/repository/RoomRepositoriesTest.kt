package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.DispatcherProvider
import com.example.data.local.db.MizanDatabase
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.PinVerificationResult
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.model.AllowedApp
import com.example.domain.model.AllowlistCategory
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.CompletionNextStep
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.ScheduleWindow
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageEventId
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomRepositoriesTest {

  private lateinit var database: MizanDatabase
  private lateinit var commitmentRepository: RoomCommitmentRepository
  private lateinit var dailyUsageRepository: RoomDailyUsageRepository
  private lateinit var securityEventRepository: RoomSecurityEventRepository
  private lateinit var accountabilityMetadataRepository: RoomAccountabilityMetadataRepository

  private val testDispatcher = UnconfinedTestDispatcher()
  private val dispatcherProvider =
    object : DispatcherProvider {
      override val main: CoroutineDispatcher = testDispatcher
      override val io: CoroutineDispatcher = testDispatcher
      override val default: CoroutineDispatcher = testDispatcher
    }

  private val createdAt =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(9, 0, 0),
      epochMillis = 1_794_560_400_000L,
      utcOffsetMinutes = -300,
      zoneId = "America/New_York",
    )

  private val instagram = RestrictedApp(AppId("com.instagram.android"), "Instagram")
  private val tiktok = RestrictedApp(AppId("com.zhiliaoapp.musically"), "TikTok")
  private val phone = AllowedApp(AppId("com.android.dialer"), "Phone", AllowlistCategory.EMERGENCY_PHONE)

  private val rules =
    CommitmentRules(
      mode = RestrictionMode.HYBRID,
      restrictedApps = setOf(instagram, tiktok),
      allowedApps = setOf(phone),
      scheduleWindows =
        listOf(
          ScheduleWindow(
            daysOfWeek = setOf(DomainDayOfWeek.MONDAY, DomainDayOfWeek.FRIDAY),
            start = DomainTime(22, 0),
            end = DomainTime(6, 0),
          )
        ),
      dailyAllowance = 30.minutes,
      requirePinForEmergencyTermination = true,
    )

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, MizanDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    commitmentRepository = RoomCommitmentRepository(database.commitmentDao(), dispatcherProvider)
    dailyUsageRepository =
      RoomDailyUsageRepository(database, database.dailyUsageDao(), dispatcherProvider)
    securityEventRepository =
      RoomSecurityEventRepository(database.securityEventDao(), dispatcherProvider)
    accountabilityMetadataRepository =
      RoomAccountabilityMetadataRepository(database.accountabilityMetadataDao(), dispatcherProvider)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `save and retrieve commitment with full rules and lifecycle transitions preserves usage`() =
    runTest {
      val draft = CommitmentStateMachine.createDraft(CommitmentId("c-life"), rules, createdAt)
      assertEquals(PersistenceResult.Success(Unit), commitmentRepository.saveCommitment(draft))

      val active =
        (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = createdAt)
            as CommitmentResult.Success)
          .commitment
      assertEquals(PersistenceResult.Success(Unit), commitmentRepository.saveCommitment(active))

      val observedActive =
        (commitmentRepository.observeActiveCommitment().first() as PersistenceResult.Success).data
      assertEquals(active, observedActive)

      // Record shared usage while active
      val usageResult =
        dailyUsageRepository.recordUsageEvent(
          commitment = active,
          event =
            UsageEvent(
              eventId = UsageEventId("e-1"),
              appId = instagram.appId,
              foregroundDuration = 12.minutes,
              isForeground = true,
              localDate = DomainDate(2026, 11, 13),
            ),
        )
      assertEquals(12.minutes, (usageResult as PersistenceResult.Success).data.consumed)

      // Transition Active -> Completed and save; verify rules & usage remain intact
      val completed =
        (CommitmentStateMachine.completeIfReachedEnd(active, active.endsAt)
            as CommitmentResult.Success)
          .commitment
      val chosen =
        (CommitmentStateMachine.chooseNextStep(completed, CompletionNextStep.FINISH)
            as CommitmentResult.Success)
          .commitment
      assertEquals(PersistenceResult.Success(Unit), commitmentRepository.saveCommitment(chosen))

      val loadedCompleted =
        (commitmentRepository.getCommitment(active.id) as PersistenceResult.Success).data
      assertEquals(chosen, loadedCompleted)
      assertNull(
        (commitmentRepository.observeActiveCommitment().first() as PersistenceResult.Success).data
      )

      val persistedUsage =
        (dailyUsageRepository.getDailyUsage(active.id, DomainDate(2026, 11, 13))
            as PersistenceResult.Success)
          .data
      assertEquals(12.minutes, persistedUsage.consumed)
      assertTrue(UsageEventId("e-1") in persistedUsage.recordedEventIds)
    }

  @Test
  fun `emergency terminated commitment round trips losslessly`() = runTest {
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-emg"), rules, createdAt)
    val active =
      (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = createdAt)
          as CommitmentResult.Success)
        .commitment
    val terminated =
      (CommitmentStateMachine.emergencyTerminate(
          commitment = active,
          confirmedWarning = true,
          pinVerification = PinVerificationResult.VERIFIED,
          now = createdAt.plusElapsedDays(2),
        ) as CommitmentResult.Success)
        .commitment

    assertEquals(PersistenceResult.Success(Unit), commitmentRepository.saveCommitment(terminated))
    val loaded =
      (commitmentRepository.getCommitment(terminated.id) as PersistenceResult.Success).data
    assertEquals(terminated, loaded)
  }

  @Test
  fun `missing commitment returns null on get and NotFound on delete`() = runTest {
    val missing = CommitmentId("does-not-exist")
    val getResult = commitmentRepository.getCommitment(missing)
    assertEquals(PersistenceResult.Success(null), getResult)

    val deleteResult = commitmentRepository.deleteCommitment(missing)
    assertEquals(PersistenceResult.Failure(PersistenceError.NotFound("Commitment")), deleteResult)
  }

  @Test
  fun `corrupt restriction mode surfaces PersistenceError CorruptData`() = runTest {
    val db = database.openHelper.writableDatabase
    db.execSQL(
      """
      INSERT INTO commitments (
        id, lifecycle_state,
        created_at_dateIso, created_at_timeIso, created_at_epochMillis, created_at_utcOffsetMinutes, created_at_zoneId
      ) VALUES ('corrupt-1', 'DRAFT', '2026-11-13', '09:00:00', 1794560400000, 0, 'UTC')
      """.trimIndent()
    )
    db.execSQL(
      """
      INSERT INTO commitment_rules (commitment_id, restriction_mode, daily_allowance_millis, require_pin_for_emergency)
      VALUES ('corrupt-1', 'INVALID_MODE', 1800000, 0)
      """.trimIndent()
    )

    val getResult = commitmentRepository.getCommitment(CommitmentId("corrupt-1"))
    assertTrue(getResult is PersistenceResult.Failure)
    assertTrue((getResult as PersistenceResult.Failure).error is PersistenceError.CorruptData)

    val flowResult = commitmentRepository.observeAllCommitments().first()
    assertTrue(flowResult is PersistenceResult.Failure)
    assertTrue((flowResult as PersistenceResult.Failure).error is PersistenceError.CorruptData)
  }

  @Test
  fun `daily usage repository shares pool across apps deduplicates events and enforces active state`() =
    runTest {
      val draft = CommitmentStateMachine.createDraft(CommitmentId("c-use"), rules, createdAt)
      commitmentRepository.saveCommitment(draft)

      // Draft commitment rejects usage accrual via domain rule
      val draftAttempt =
        dailyUsageRepository.recordUsageEvent(
          draft,
          UsageEvent(UsageEventId("u-0"), instagram.appId, 5.minutes, true, DomainDate(2026, 11, 13)),
        )
      assertTrue(draftAttempt is PersistenceResult.Failure)
      assertTrue((draftAttempt as PersistenceResult.Failure).error is PersistenceError.DomainRuleViolation)

      val active =
        (CommitmentStateMachine.activate(draft, true, createdAt) as CommitmentResult.Success)
          .commitment
      commitmentRepository.saveCommitment(active)

      val day1 = DomainDate(2026, 11, 13)
      val day2 = DomainDate(2026, 11, 14)

      dailyUsageRepository.recordUsageEvent(
        active,
        UsageEvent(UsageEventId("u-1"), instagram.appId, 10.minutes, true, day1),
      )
      // Duplicate event u-1 must not add 10 more minutes
      dailyUsageRepository.recordUsageEvent(
        active,
        UsageEvent(UsageEventId("u-1"), instagram.appId, 10.minutes, true, day1),
      )
      // Second restricted app shares same pool
      val afterTikTok =
        (dailyUsageRepository.recordUsageEvent(
            active,
            UsageEvent(UsageEventId("u-2"), tiktok.appId, 15.minutes, true, day1),
          ) as PersistenceResult.Success)
          .data

      assertEquals(25.minutes, afterTikTok.consumed)
      assertEquals(setOf(UsageEventId("u-1"), UsageEventId("u-2")), afterTikTok.recordedEventIds)

      val observedDay1 =
        (dailyUsageRepository.observeDailyUsage(active.id, day1).first()
            as PersistenceResult.Success)
          .data
      assertEquals(25.minutes, observedDay1.consumed)

      // New local day starts fresh at zero before first event
      val day2Empty =
        (dailyUsageRepository.getDailyUsage(active.id, day2) as PersistenceResult.Success).data
      assertEquals(0.minutes, day2Empty.consumed)
    }

  @Test
  fun `security events and accountability metadata repositories persist and observe reactively`() =
    runTest {
      val initialMeta =
        (accountabilityMetadataRepository.observeMetadata().first() as PersistenceResult.Success)
          .data
      assertEquals(AccountabilityMetadata(isPinEnabled = false, updatedAtEpochMillis = 0L), initialMeta)

      val updatedMeta = AccountabilityMetadata(isPinEnabled = true, updatedAtEpochMillis = 123456L)
      assertEquals(
        PersistenceResult.Success(Unit),
        accountabilityMetadataRepository.updateMetadata(updatedMeta),
      )
      assertEquals(
        updatedMeta,
        (accountabilityMetadataRepository.observeMetadata().first() as PersistenceResult.Success)
          .data,
      )

      val event =
        SecurityEvent(
          id = SecurityEventId("sec-100"),
          commitmentId = null,
          eventType = SecurityEventType.COMMITMENT_ACTIVATED,
          occurredAt = createdAt,
          summary = "User explicitly agreed to rules",
        )
      assertEquals(PersistenceResult.Success(Unit), securityEventRepository.recordEvent(event))
      val events =
        (securityEventRepository.observeSecurityEvents().first() as PersistenceResult.Success).data
      assertEquals(listOf(event), events)
    }

  @Test
  fun `daily usage records blocked attempts and queries recent usage while removal repo persists cooling-off requests`() =
    runTest {
      val draft = CommitmentStateMachine.createDraft(CommitmentId("c-m7-room"), rules, createdAt)
      val active =
        (CommitmentStateMachine.activate(draft, true, createdAt) as CommitmentResult.Success)
          .commitment
      commitmentRepository.saveCommitment(active)

      val day1 = DomainDate(2026, 11, 13)
      assertEquals(
        PersistenceResult.Success(Unit),
        dailyUsageRepository.recordBlockedAttempt(active.id, day1),
      )
      assertEquals(
        PersistenceResult.Success(Unit),
        dailyUsageRepository.recordBlockedAttempt(active.id, day1),
      )
      val reloaded =
        (dailyUsageRepository.getDailyUsage(active.id, day1) as PersistenceResult.Success).data
      assertEquals(2, reloaded.blockedAttempts)

      val recent =
        (dailyUsageRepository.observeRecentDailyUsage(10).first() as PersistenceResult.Success).data
      assertEquals(1, recent.size)
      assertEquals(2, recent.single().blockedAttempts)

      val removalRepo = RoomRemovalRequestRepository(database.removalRequestDao(), dispatcherProvider)
      val req =
        com.example.domain.lifecycle.RemovalRequestStateMachine.createRequest(
          id = com.example.domain.model.RemovalRequestId("rem-room-1"),
          commitmentId = active.id,
          nowWall = createdAt,
          nowMonotonic = com.example.domain.time.MonotonicTimestamp(1_000L),
          bootSessionToken = "boot:1",
        )
      assertEquals(PersistenceResult.Success(Unit), removalRepo.saveRequest(req))
      val latest = (removalRepo.observeLatestRequest().first() as PersistenceResult.Success).data
      assertEquals(req, latest)
      assertEquals(req, (removalRepo.getLatestOpenRequest() as PersistenceResult.Success).data)
    }
}
