package com.example.platform.usage

import androidx.annotation.VisibleForTesting
import com.example.core.common.DispatcherProvider
import com.example.core.common.IdGenerator
import com.example.domain.allowance.MidnightIntervalSplitter
import com.example.domain.allowance.SharedAllowanceAccountant
import com.example.domain.evaluator.RestrictionDecision
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.PersistedActiveUsageSession
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageEventId
import com.example.domain.model.UsageIntegrityCheckpoint
import com.example.domain.model.UsageMeasurementSource
import com.example.domain.repository.DailyUsageRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import com.example.platform.enforcement.AppPackageName
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionEndReason {
  APP_SWITCHED,
  BLOCKED,
  ALLOWLISTED_OR_MIZAN,
  SCREEN_OFF,
  DEVICE_LOCKED,
  SERVICE_INTERRUPTED,
  SERVICE_DISCONNECTED,
  COMMITMENT_ENDED,
  ALLOWANCE_EXHAUSTED,
}

sealed interface UsageTrackingOutcome {
  data object Ok : UsageTrackingOutcome

  data object StorageError : UsageTrackingOutcome
}

/**
 * Coordinates foreground usage session lifecycle using monotonic duration measurement within the
 * current boot session and local-calendar attribution via [TimeProvider] and
 * [MidnightIntervalSplitter].
 */
@Singleton
class ForegroundUsageTracker
@Inject
constructor(
  private val dailyUsageRepository: DailyUsageRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val timeProvider: TimeProvider,
  private val bootSessionProvider: BootSessionProvider,
  private val deviceInteractiveStateProvider: DeviceInteractiveStateProvider,
  private val idGenerator: IdGenerator,
  private val dispatcherProvider: DispatcherProvider,
  @param:AppPackageName private val ownPackageName: String,
) {

  private data class ActiveSession(
    val commitment: Commitment.Active,
    val appId: AppId,
    val startedAtWallClock: WallClockPoint,
    val startedAtMonotonic: MonotonicTimestamp,
    val bootSessionToken: String,
  )

  private val mutex = Mutex()
  private var activeSession: ActiveSession? = null
  private var exhaustionJob: Job? = null
  private var cachedCheckpoint: UsageIntegrityCheckpoint? = null
  private var timerScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
  private var exhaustionCallback: (suspend (String) -> Unit)? = null

  fun setOnAllowanceExhaustedListener(listener: (suspend (String) -> Unit)?) {
    exhaustionCallback = listener
  }

  @VisibleForTesting
  internal fun setTimerScopeForTesting(scope: CoroutineScope) {
    timerScope = scope
  }

  suspend fun recoverUnfinishedSessionIfNeeded(): UsageTrackingOutcome =
    mutex.withLock {
      val nowWall = timeProvider.currentWallClock()
      val nowMonotonic = timeProvider.currentMonotonic()
      val integrityOk = verifyAndUpdateIntegrityLocked(commitmentId = null, nowWall = nowWall, nowMonotonic = nowMonotonic)

      val persisted =
        when (val res = dailyUsageRepository.getActiveUsageSession()) {
          is PersistenceResult.Success -> res.data
          is PersistenceResult.Failure -> return@withLock UsageTrackingOutcome.StorageError
        }

      if (persisted != null) {
        if (dailyUsageRepository.clearActiveUsageSession() is PersistenceResult.Failure) {
          return@withLock UsageTrackingOutcome.StorageError
        }
        // Only mark incomplete if this isn't already actively held by this live process.
        if (activeSession == null) {
          val currentBoot = bootSessionProvider.currentBootSessionToken()
          val rebooted =
            currentBoot != persisted.bootSessionToken ||
              nowMonotonic < persisted.startedAtMonotonic
          val reason =
            if (rebooted) {
              "Device rebooted while usage tracking session was open"
            } else {
              "Process or accessibility service restarted while app session was open"
            }
          if (
            dailyUsageRepository.markDayIncomplete(
              commitmentId = persisted.commitmentId,
              date = persisted.startedAtWallClock.date,
              reason = reason,
            ) is PersistenceResult.Failure
          ) {
            return@withLock UsageTrackingOutcome.StorageError
          }
          if (nowWall.date > persisted.startedAtWallClock.date) {
            dailyUsageRepository.markDayIncomplete(
              commitmentId = persisted.commitmentId,
              date = nowWall.date,
              reason = reason,
            )
          }
        }
      }

      if (integrityOk) UsageTrackingOutcome.Ok else UsageTrackingOutcome.StorageError
    }

  suspend fun onForegroundTransition(
    commitment: Commitment?,
    packageName: String?,
    decision: RestrictionDecision?,
    nowWall: WallClockPoint = timeProvider.currentWallClock(),
    nowMonotonic: MonotonicTimestamp = timeProvider.currentMonotonic(),
  ): UsageTrackingOutcome =
    mutex.withLock {
      val activeCommitment = commitment as? Commitment.Active
      val integrityOk =
        verifyAndUpdateIntegrityLocked(
          commitmentId = activeCommitment?.id,
          nowWall = nowWall,
          nowMonotonic = nowMonotonic,
        )

      val interactive = deviceInteractiveStateProvider.isInteractiveAndUnlocked()
      val isTrackableTarget =
        interactive &&
          packageName != null &&
          packageName != ownPackageName &&
          activeCommitment != null &&
          !activeCommitment.hasReachedEnd(nowWall) &&
          decision != null &&
          !decision.isBlocked &&
          activeCommitment.rules.isRestricted(AppId(packageName)) &&
          !activeCommitment.rules.isAllowlisted(AppId(packageName)) &&
          (activeCommitment.rules.mode == RestrictionMode.HYBRID ||
            activeCommitment.rules.mode == RestrictionMode.SCHEDULED)

      val current = activeSession
      if (
        isTrackableTarget &&
          current != null &&
          current.commitment.id == activeCommitment!!.id &&
          current.appId.packageName == packageName
      ) {
        // Duplicate foreground event for the already-active tracked app: keep session continuous
        // without double-starting, flushing pre-midnight slices cleanly if the calendar day changed.
        if (nowWall.date != current.startedAtWallClock.date) {
          val flushOutcome = closeActiveSessionLocked(nowWall, nowMonotonic)
          if (flushOutcome != UsageTrackingOutcome.Ok) {
            return@withLock flushOutcome
          }
          return@withLock startSessionLocked(activeCommitment, AppId(packageName), nowWall, nowMonotonic)
        }
        return@withLock if (integrityOk) UsageTrackingOutcome.Ok else UsageTrackingOutcome.StorageError
      }

      val closeOutcome = closeActiveSessionLocked(nowWall, nowMonotonic)
      if (closeOutcome != UsageTrackingOutcome.Ok) {
        return@withLock closeOutcome
      }

      if (!isTrackableTarget) {
        return@withLock if (integrityOk) UsageTrackingOutcome.Ok else UsageTrackingOutcome.StorageError
      }

      val startOutcome =
        startSessionLocked(activeCommitment!!, AppId(packageName!!), nowWall, nowMonotonic)
      if (startOutcome != UsageTrackingOutcome.Ok || !integrityOk) {
        UsageTrackingOutcome.StorageError
      } else {
        UsageTrackingOutcome.Ok
      }
    }

  suspend fun closeActiveSession(
    @Suppress("UNUSED_PARAMETER") reason: SessionEndReason,
    nowWall: WallClockPoint = timeProvider.currentWallClock(),
    nowMonotonic: MonotonicTimestamp = timeProvider.currentMonotonic(),
  ): UsageTrackingOutcome =
    mutex.withLock {
      closeActiveSessionLocked(nowWall = nowWall, nowMonotonic = nowMonotonic)
    }

  private suspend fun startSessionLocked(
    commitment: Commitment.Active,
    appId: AppId,
    nowWall: WallClockPoint,
    nowMonotonic: MonotonicTimestamp,
  ): UsageTrackingOutcome {
    var remainingHybridAllowance: Duration? = null
    if (commitment.rules.mode == RestrictionMode.HYBRID) {
      val usage =
        when (val result = dailyUsageRepository.getDailyUsage(commitment.id, nowWall.date)) {
          is PersistenceResult.Success -> result.data
          is PersistenceResult.Failure -> return UsageTrackingOutcome.StorageError
        }
      val remaining = SharedAllowanceAccountant.remainingAllowance(commitment, usage, nowWall.date)
      if (remaining <= Duration.ZERO) {
        // Allowance already exhausted: do not start accruing extra usage.
        return UsageTrackingOutcome.Ok
      }
      remainingHybridAllowance = remaining
    }

    val bootToken = bootSessionProvider.currentBootSessionToken()
    val persisted =
      PersistedActiveUsageSession(
        commitmentId = commitment.id,
        appId = appId,
        startedAtWallClock = nowWall,
        startedAtMonotonic = nowMonotonic,
        bootSessionToken = bootToken,
      )
    if (dailyUsageRepository.saveActiveUsageSession(persisted) is PersistenceResult.Failure) {
      return UsageTrackingOutcome.StorageError
    }

    activeSession =
      ActiveSession(
        commitment = commitment,
        appId = appId,
        startedAtWallClock = nowWall,
        startedAtMonotonic = nowMonotonic,
        bootSessionToken = bootToken,
      )

    if (remainingHybridAllowance != null) {
      scheduleHybridExhaustionTimerLocked(
        commitmentId = commitment.id,
        appId = appId,
        remainingAllowance = remainingHybridAllowance,
        commitmentEndsAtEpochMillis = commitment.endsAt.epochMillis,
        nowWall = nowWall,
      )
    }
    return UsageTrackingOutcome.Ok
  }

  private fun scheduleHybridExhaustionTimerLocked(
    commitmentId: CommitmentId,
    appId: AppId,
    remainingAllowance: Duration,
    commitmentEndsAtEpochMillis: Long,
    nowWall: WallClockPoint,
  ) {
    exhaustionJob?.cancel()
    val msUntilCommitmentEnd = (commitmentEndsAtEpochMillis - nowWall.epochMillis).coerceAtLeast(0L)
    val delayMs = minOf(remainingAllowance.inWholeMilliseconds, msUntilCommitmentEnd).coerceAtLeast(1L)
    exhaustionJob =
      timerScope.launch {
        delay(delayMs)
        handleAllowanceTimerFired(commitmentId, appId)
      }
  }

  private suspend fun handleAllowanceTimerFired(
    expectedCommitmentId: CommitmentId,
    expectedAppId: AppId,
  ) {
    var shouldNotifyExhausted = false
    mutex.withLock {
      val current = activeSession ?: return@withLock
      if (current.commitment.id != expectedCommitmentId || current.appId != expectedAppId) {
        return@withLock
      }
      if (!deviceInteractiveStateProvider.isInteractiveAndUnlocked()) {
        closeActiveSessionLocked(timeProvider.currentWallClock(), timeProvider.currentMonotonic())
        return@withLock
      }
      val closeOutcome =
        closeActiveSessionLocked(
          nowWall = timeProvider.currentWallClock(),
          nowMonotonic = timeProvider.currentMonotonic(),
          cancelTimer = false,
        )
      shouldNotifyExhausted = closeOutcome == UsageTrackingOutcome.Ok
    }
    if (shouldNotifyExhausted) {
      exhaustionCallback?.invoke(expectedAppId.packageName)
    }
  }

  private suspend fun closeActiveSessionLocked(
    nowWall: WallClockPoint,
    nowMonotonic: MonotonicTimestamp,
    cancelTimer: Boolean = true,
  ): UsageTrackingOutcome {
    if (cancelTimer) {
      exhaustionJob?.cancel()
    }
    exhaustionJob = null
    val session = activeSession ?: return UsageTrackingOutcome.Ok
    activeSession = null

    val clearResult = dailyUsageRepository.clearActiveUsageSession()

    val currentBoot = bootSessionProvider.currentBootSessionToken()
    if (currentBoot != session.bootSessionToken || nowMonotonic < session.startedAtMonotonic) {
      dailyUsageRepository.markDayIncomplete(
        commitmentId = session.commitment.id,
        date = session.startedAtWallClock.date,
        reason = "Device reboot or monotonic reset interrupted foreground session",
      )
      return if (clearResult is PersistenceResult.Failure) {
        UsageTrackingOutcome.StorageError
      } else {
        UsageTrackingOutcome.Ok
      }
    }

    var elapsed = nowMonotonic.elapsedSince(session.startedAtMonotonic)
    val maxUntilCommitmentEnd =
      (session.commitment.endsAt.epochMillis - session.startedAtWallClock.epochMillis)
        .coerceAtLeast(0L)
        .milliseconds
    if (elapsed > maxUntilCommitmentEnd) {
      elapsed = maxUntilCommitmentEnd
    }

    if (elapsed > MAX_CONTINUOUS_INTERVAL) {
      dailyUsageRepository.markDayIncomplete(
        commitmentId = session.commitment.id,
        date = session.startedAtWallClock.date,
        reason = "Continuous session duration exceeded plausibility cap and was omitted conservatively",
      )
      return if (clearResult is PersistenceResult.Failure) {
        UsageTrackingOutcome.StorageError
      } else {
        UsageTrackingOutcome.Ok
      }
    }

    if (elapsed <= Duration.ZERO) {
      return if (clearResult is PersistenceResult.Failure) {
        UsageTrackingOutcome.StorageError
      } else {
        UsageTrackingOutcome.Ok
      }
    }

    val slices =
      MidnightIntervalSplitter.splitByLocalMidnight(
        startWallClock = session.startedAtWallClock,
        totalDuration = elapsed,
        timeProvider = timeProvider,
      )

    for (slice in slices) {
      val event =
        UsageEvent(
          eventId = UsageEventId("a11y:${idGenerator.nextId()}"),
          appId = session.appId,
          foregroundDuration = slice.duration,
          isForeground = true,
          localDate = slice.localDate,
          source = UsageMeasurementSource.ACCESSIBILITY_INTERVAL,
          startedAtEpochMillis = slice.startedAtEpochMillis,
          endedAtEpochMillis = slice.endedAtEpochMillis,
          zoneId = slice.zoneId,
          bootSessionToken = session.bootSessionToken,
        )
      if (dailyUsageRepository.recordUsageEvent(session.commitment, event) is PersistenceResult.Failure) {
        return UsageTrackingOutcome.StorageError
      }
    }

    return if (clearResult is PersistenceResult.Failure) {
      UsageTrackingOutcome.StorageError
    } else {
      UsageTrackingOutcome.Ok
    }
  }

  private suspend fun verifyAndUpdateIntegrityLocked(
    commitmentId: CommitmentId?,
    nowWall: WallClockPoint,
    nowMonotonic: MonotonicTimestamp,
  ): Boolean {
    val currentBoot = bootSessionProvider.currentBootSessionToken()
    val previous =
      cachedCheckpoint
        ?: when (val loaded = dailyUsageRepository.getIntegrityCheckpoint()) {
          is PersistenceResult.Success -> loaded.data
          is PersistenceResult.Failure -> return false
        }

    if (
      previous != null &&
        previous.bootSessionToken == currentBoot &&
        nowMonotonic >= previous.lastMonotonic
    ) {
      val monoDelta = nowMonotonic.elapsedSince(previous.lastMonotonic).inWholeMilliseconds
      val wallDelta = nowWall.epochMillis - previous.lastWallClock.epochMillis
      val driftMs = abs(wallDelta - monoDelta)
      if (driftMs > CLOCK_ANOMALY_TOLERANCE_MS) {
        runCatching {
          securityEventRepository.recordEvent(
            SecurityEvent(
              id = SecurityEventId(idGenerator.nextId()),
              commitmentId = commitmentId,
              eventType = SecurityEventType.WALL_CLOCK_ANOMALY_OBSERVED,
              occurredAt = nowWall,
              summary = "Local wall-clock shift detected relative to monotonic clock",
            )
          )
        }
      }
    }

    val nextCheckpoint =
      UsageIntegrityCheckpoint(
        lastWallClock = nowWall,
        lastMonotonic = nowMonotonic,
        bootSessionToken = currentBoot,
      )
    cachedCheckpoint = nextCheckpoint
    return dailyUsageRepository.saveIntegrityCheckpoint(nextCheckpoint) is PersistenceResult.Success
  }

  companion object {
    val MAX_CONTINUOUS_INTERVAL: Duration = 12.hours
    const val CLOCK_ANOMALY_TOLERANCE_MS: Long = 120_000L
  }
}
