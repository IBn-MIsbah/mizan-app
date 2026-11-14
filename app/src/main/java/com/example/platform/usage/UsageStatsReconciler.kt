package com.example.platform.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.example.core.common.DispatcherProvider
import com.example.domain.allowance.IntervalOverlapFilter
import com.example.domain.allowance.MidnightIntervalSplitter
import com.example.domain.model.AppId
import com.example.domain.model.Commitment
import com.example.domain.model.RestrictionMode
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageEventId
import com.example.domain.model.UsageMeasurementSource
import com.example.domain.repository.DailyUsageRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import com.example.platform.enforcement.AppPackageName
import com.example.platform.health.UsageAccessMonitor
import com.example.platform.health.UsageAccessPermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withContext

enum class OsUsageEventType {
  FOREGROUND,
  BACKGROUND,
  SCREEN_OFF,
  KEYGUARD_SHOWN,
  OTHER,
}

data class OsUsageEvent(
  val packageName: String,
  val eventType: OsUsageEventType,
  val timestampEpochMillis: Long,
)

interface UsageStatsHistorySource {
  fun queryEvents(startEpochMillis: Long, endEpochMillis: Long): List<OsUsageEvent>
}

@Singleton
class AndroidUsageStatsHistorySource
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
) : UsageStatsHistorySource {

  override fun queryEvents(startEpochMillis: Long, endEpochMillis: Long): List<OsUsageEvent> {
    if (endEpochMillis <= startEpochMillis) return emptyList()
    val manager =
      context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return emptyList()
    val usageEvents =
      runCatching { manager.queryEvents(startEpochMillis, endEpochMillis) }.getOrNull()
        ?: return emptyList()

    val collected = mutableListOf<OsUsageEvent>()
    val reusableEvent = UsageEvents.Event()
    while (usageEvents.hasNextEvent()) {
      if (!usageEvents.getNextEvent(reusableEvent)) break
      val pkg = reusableEvent.packageName?.trim().orEmpty()
      if (pkg.isBlank()) continue
      val mappedType = mapEventType(reusableEvent.eventType)
      if (mappedType != OsUsageEventType.OTHER) {
        collected.add(
          OsUsageEvent(
            packageName = pkg,
            eventType = mappedType,
            timestampEpochMillis = reusableEvent.timeStamp,
          )
        )
      }
    }
    return collected
  }

  private fun mapEventType(eventType: Int): OsUsageEventType =
    when {
      eventType == EVENT_ACTIVITY_RESUMED -> OsUsageEventType.FOREGROUND
      eventType == EVENT_ACTIVITY_PAUSED -> OsUsageEventType.BACKGROUND
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        eventType == UsageEvents.Event.ACTIVITY_STOPPED -> OsUsageEventType.BACKGROUND
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE -> OsUsageEventType.SCREEN_OFF
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        eventType == UsageEvents.Event.KEYGUARD_SHOWN -> OsUsageEventType.KEYGUARD_SHOWN
      else -> OsUsageEventType.OTHER
    }

  private companion object {
    @Suppress("DEPRECATION")
    const val EVENT_ACTIVITY_RESUMED = UsageEvents.Event.MOVE_TO_FOREGROUND
    @Suppress("DEPRECATION")
    const val EVENT_ACTIVITY_PAUSED = UsageEvents.Event.MOVE_TO_BACKGROUND
  }
}

sealed interface ReconcileResult {
  data class Reconciled(
    val addedIntervals: Int,
    val addedDuration: Duration,
    val partialCoverage: Boolean,
  ) : ReconcileResult

  data object PermissionDisabled : ReconcileResult

  data object PermissionUnavailable : ReconcileResult

  data object NoActiveCommitment : ReconcileResult

  data object StorageFailure : ReconcileResult
}

/**
 * Supplementary, permission-gated UsageStats reconciliation service.
 *
 * Rules enforced:
 * - Queries only `[max(activatedAt, now - lookbackWindow), min(now, endsAt)]`.
 * - Reconstructs only closed foreground intervals backed by matching end/interrupt events.
 * - Ignores MIZAN and allowlisted apps while allowing them to terminate preceding intervals.
 * - Subtracts existing persisted intervals (from live accessibility tracking or prior runs) so
 *   overlapping intervals are never double-counted and measured records are never overwritten.
 */
@Singleton
class UsageStatsReconciler
@Inject
constructor(
  private val usageAccessMonitor: UsageAccessMonitor,
  private val historySource: UsageStatsHistorySource,
  private val dailyUsageRepository: DailyUsageRepository,
  private val timeProvider: TimeProvider,
  private val dispatcherProvider: DispatcherProvider,
  @param:AppPackageName private val ownPackageName: String,
) {

  suspend fun reconcileActiveCommitment(
    commitment: Commitment?,
    lookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
  ): ReconcileResult {
    val active = commitment as? Commitment.Active ?: return ReconcileResult.NoActiveCommitment
    if (active.rules.mode == RestrictionMode.STRICT) {
      return ReconcileResult.Reconciled(addedIntervals = 0, addedDuration = Duration.ZERO, partialCoverage = false)
    }

    usageAccessMonitor.refreshSystemStatus()
    when (usageAccessMonitor.usageAccessState.value.permissionStatus) {
      UsageAccessPermissionStatus.DISABLED -> return ReconcileResult.PermissionDisabled
      UsageAccessPermissionStatus.UNAVAILABLE -> return ReconcileResult.PermissionUnavailable
      UsageAccessPermissionStatus.ENABLED -> Unit
    }

    val nowWall = timeProvider.currentWallClock()
    val effectiveEndMs = minOf(nowWall.epochMillis, active.endsAt.epochMillis)
    if (effectiveEndMs <= active.activatedAt.epochMillis) {
      return ReconcileResult.Reconciled(addedIntervals = 0, addedDuration = Duration.ZERO, partialCoverage = false)
    }

    val historyFloorMs = nowWall.epochMillis - lookbackDays.toLong() * WallClockPoint.MILLIS_PER_DAY
    val clippedByHistoryWindow = active.activatedAt.epochMillis < historyFloorMs
    val queryStartMs = maxOf(active.activatedAt.epochMillis, historyFloorMs)

    val events =
      withContext(dispatcherProvider.io) {
        runCatching { historySource.queryEvents(queryStartMs, effectiveEndMs) }.getOrNull()
      } ?: return ReconcileResult.PermissionUnavailable

    val restrictedPackages =
      active.rules.restrictedApps
        .map { it.appId.packageName }
        .filter { pkg ->
          pkg != ownPackageName && !active.rules.isAllowlisted(AppId(pkg))
        }
        .toSet()

    if (restrictedPackages.isEmpty()) {
      return ReconcileResult.Reconciled(addedIntervals = 0, addedDuration = Duration.ZERO, partialCoverage = false)
    }

    val parseResult = extractClosedIntervals(events, queryStartMs, effectiveEndMs, restrictedPackages)

    var addedIntervals = 0
    var addedMillis = 0L
    val touchedDates = mutableSetOf<DomainDate>()
    var sawImplausibleInterval = false

    for (candidate in parseResult.intervals) {
      val durationMs = candidate.endEpochMillis - candidate.startEpochMillis
      if (durationMs <= 0L) continue
      if (durationMs.milliseconds > MAX_RECONCILED_INTERVAL) {
        sawImplausibleInterval = true
        continue
      }

      val startWall = timeProvider.wallClockAt(candidate.startEpochMillis)
      val slices =
        MidnightIntervalSplitter.splitByLocalMidnight(
          startWallClock = startWall,
          totalDuration = durationMs.milliseconds,
          timeProvider = timeProvider,
        )

      for (slice in slices) {
        val clampedStart = maxOf(slice.startedAtEpochMillis, active.activatedAt.epochMillis)
        val clampedEnd = minOf(slice.endedAtEpochMillis, active.endsAt.epochMillis)
        if (clampedEnd <= clampedStart) continue

        touchedDates.add(slice.localDate)
        val existingUsage =
          when (val existingRes = dailyUsageRepository.getDailyUsage(active.id, slice.localDate)) {
            is PersistenceResult.Success -> existingRes.data
            is PersistenceResult.Failure -> return ReconcileResult.StorageFailure
          }

        val uncoveredGaps =
          IntervalOverlapFilter.subtractExisting(
            candidateStartMs = clampedStart,
            candidateEndMs = clampedEnd,
            packageName = AppId(candidate.packageName),
            existingIntervals = existingUsage.intervals,
          )

        for (gap in uncoveredGaps) {
          val gapDurationMs = gap.endEpochMillis - gap.startEpochMillis
          if (gapDurationMs <= 0L) continue

          val dateIso = formatDateIso(slice.localDate)
          val deterministicEventId =
            UsageEventId(
              "reconcile:${active.id.value}:$dateIso:${candidate.packageName}:${gap.startEpochMillis}:${gap.endEpochMillis}"
            )
          val event =
            UsageEvent(
              eventId = deterministicEventId,
              appId = AppId(candidate.packageName),
              foregroundDuration = gapDurationMs.milliseconds,
              isForeground = true,
              localDate = slice.localDate,
              source = UsageMeasurementSource.USAGE_STATS_RECONCILED,
              startedAtEpochMillis = gap.startEpochMillis,
              endedAtEpochMillis = gap.endEpochMillis,
              zoneId = slice.zoneId,
            )
          when (dailyUsageRepository.recordUsageEvent(active, event)) {
            is PersistenceResult.Success -> {
              addedIntervals += 1
              addedMillis += gapDurationMs
            }
            is PersistenceResult.Failure -> return ReconcileResult.StorageFailure
          }
        }
      }
    }

    val partialCoverage =
      clippedByHistoryWindow || parseResult.hasIncompleteEvents || sawImplausibleInterval
    val datesToMark = (touchedDates + nowWall.date).distinct()
    for (date in datesToMark) {
      val markIncomplete =
        if (partialCoverage) UsageCoverageStatus.INCOMPLETE else null
      val reason =
        when {
          clippedByHistoryWindow -> "UsageStats OS history window no longer retains earlier commitment days"
          sawImplausibleInterval -> "Omitted an implausibly long UsageStats interval conservatively"
          parseResult.hasIncompleteEvents -> "UsageStats history had missing close or open event pairs"
          else -> null
        }
      dailyUsageRepository.markReconciliationAttempt(
        commitmentId = active.id,
        date = date,
        reconciledAtEpochMillis = nowWall.epochMillis,
        coverageStatus = markIncomplete,
        incompleteReason = reason,
      )
    }

    return ReconcileResult.Reconciled(
      addedIntervals = addedIntervals,
      addedDuration = addedMillis.milliseconds,
      partialCoverage = partialCoverage,
    )
  }

  private data class CandidateClosedInterval(
    val packageName: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
  )

  private data class ExtractedIntervals(
    val intervals: List<CandidateClosedInterval>,
    val hasIncompleteEvents: Boolean,
  )

  private fun extractClosedIntervals(
    rawEvents: List<OsUsageEvent>,
    queryStartMs: Long,
    queryEndMs: Long,
    restrictedPackages: Set<String>,
  ): ExtractedIntervals {
    val sorted =
      rawEvents
        .filter { it.timestampEpochMillis in queryStartMs..queryEndMs }
        .sortedBy { it.timestampEpochMillis }

    if (sorted.isEmpty()) {
      return ExtractedIntervals(intervals = emptyList(), hasIncompleteEvents = false)
    }

    val closed = mutableListOf<CandidateClosedInterval>()
    var openPackage: String? = null
    var openStartMs: Long = Long.MIN_VALUE
    var incomplete = false

    for (event in sorted) {
      when (event.eventType) {
        OsUsageEventType.FOREGROUND -> {
          if (openPackage == null) {
            openPackage = event.packageName
            openStartMs = event.timestampEpochMillis
          } else if (openPackage == event.packageName) {
            // Duplicate resume for already-open package: retain earliest start timestamp.
          } else {
            if (openPackage in restrictedPackages && event.timestampEpochMillis > openStartMs) {
              closed.add(
                CandidateClosedInterval(
                  packageName = openPackage,
                  startEpochMillis = openStartMs,
                  endEpochMillis = event.timestampEpochMillis,
                )
              )
            }
            openPackage = event.packageName
            openStartMs = event.timestampEpochMillis
          }
        }

        OsUsageEventType.BACKGROUND -> {
          if (openPackage == event.packageName && event.timestampEpochMillis > openStartMs) {
            if (openPackage in restrictedPackages) {
              closed.add(
                CandidateClosedInterval(
                  packageName = openPackage,
                  startEpochMillis = openStartMs,
                  endEpochMillis = event.timestampEpochMillis,
                )
              )
            }
            openPackage = null
            openStartMs = Long.MIN_VALUE
          } else if (openPackage == null && event.packageName in restrictedPackages) {
            // Orphan pause/background event with no open resume in queried history.
            incomplete = true
          }
        }

        OsUsageEventType.SCREEN_OFF,
        OsUsageEventType.KEYGUARD_SHOWN -> {
          val activePkg = openPackage
          if (activePkg != null && activePkg in restrictedPackages && event.timestampEpochMillis > openStartMs) {
            closed.add(
              CandidateClosedInterval(
                packageName = activePkg,
                startEpochMillis = openStartMs,
                endEpochMillis = event.timestampEpochMillis,
              )
            )
          }
          openPackage = null
          openStartMs = Long.MIN_VALUE
        }

        OsUsageEventType.OTHER -> Unit
      }
    }

    // Drop any dangling open resume event without a matching close/interrupt event rather than
    // fabricating elapsed time up to `now`.
    if (openPackage != null && openPackage in restrictedPackages) {
      incomplete = true
    }

    return ExtractedIntervals(intervals = closed, hasIncompleteEvents = incomplete)
  }

  private fun formatDateIso(date: DomainDate): String =
    String.format(Locale.US, "%04d-%02d-%02d", date.year, date.month, date.dayOfMonth)

  companion object {
    const val DEFAULT_LOOKBACK_DAYS = 7
    val MAX_RECONCILED_INTERVAL: Duration = 12.hours
  }
}
