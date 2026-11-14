package com.example.domain.allowance

import com.example.domain.model.AppId
import com.example.domain.model.RecordedUsageInterval
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class LocalDayIntervalSlice(
  val localDate: DomainDate,
  val startedAtEpochMillis: Long,
  val endedAtEpochMillis: Long,
  val duration: Duration,
  val zoneId: String,
)

data class EpochInterval(
  val startEpochMillis: Long,
  val endEpochMillis: Long,
) {
  init {
    require(endEpochMillis > startEpochMillis) {
      "EpochInterval must have positive span: $startEpochMillis .. $endEpochMillis"
    }
  }
}

/**
 * Pure local-midnight interval splitter using [TimeProvider.nextLocalMidnightEpochMillis].
 *
 * Duration measurement relies exclusively on monotonic elapsed duration passed in via
 * [totalDuration]; wall-clock [startWallClock] anchors the local calendar day & midnight boundary.
 */
object MidnightIntervalSplitter {

  fun splitByLocalMidnight(
    startWallClock: WallClockPoint,
    totalDuration: Duration,
    timeProvider: TimeProvider,
    maxSlices: Int = 14,
  ): List<LocalDayIntervalSlice> {
    if (!totalDuration.isFinite() || totalDuration <= Duration.ZERO) {
      return emptyList()
    }

    val totalMillis = totalDuration.inWholeMilliseconds
    if (totalMillis <= 0L) return emptyList()

    val slices = mutableListOf<LocalDayIntervalSlice>()
    var cursorWall = startWallClock
    var cursorEpochMs = startWallClock.epochMillis
    var remainingMs = totalMillis

    var sliceCount = 0
    while (remainingMs > 0L && sliceCount < maxSlices) {
      val nextMidnightMs =
        timeProvider.nextLocalMidnightEpochMillis(cursorWall).coerceAtLeast(cursorEpochMs + 1L)
      val untilMidnightMs = (nextMidnightMs - cursorEpochMs).coerceAtLeast(1L)
      val segmentMs = minOf(remainingMs, untilMidnightMs)
      val segmentEndMs = cursorEpochMs + segmentMs

      slices.add(
        LocalDayIntervalSlice(
          localDate = cursorWall.date,
          startedAtEpochMillis = cursorEpochMs,
          endedAtEpochMillis = segmentEndMs,
          duration = segmentMs.milliseconds,
          zoneId = cursorWall.zoneId,
        )
      )

      remainingMs -= segmentMs
      cursorEpochMs = segmentEndMs
      if (remainingMs > 0L) {
        cursorWall =
          WallClockPoint(
            date = cursorWall.date.plusDays(1),
            time = DomainTime.MIDNIGHT,
            epochMillis = segmentEndMs,
            utcOffsetMinutes = cursorWall.utcOffsetMinutes,
            zoneId = cursorWall.zoneId,
          )
      }
      sliceCount += 1
    }
    return slices
  }
}

/**
 * Subtracts previously persisted intervals from candidate reconciled intervals so overlapping
 * accessibility and UsageStats records are never double-counted.
 */
object IntervalOverlapFilter {

  fun subtractExisting(
    candidateStartMs: Long,
    candidateEndMs: Long,
    packageName: AppId,
    existingIntervals: List<RecordedUsageInterval>,
  ): List<EpochInterval> {
    if (candidateEndMs <= candidateStartMs) return emptyList()

    val relevantExisting =
      existingIntervals
        .mapNotNull { existing ->
          if (existing.packageName != packageName) return@mapNotNull null
          val s = existing.startedAtEpochMillis ?: return@mapNotNull null
          val e = existing.endedAtEpochMillis ?: return@mapNotNull null
          if (e <= candidateStartMs || s >= candidateEndMs) null
          else EpochInterval(startEpochMillis = maxOf(s, candidateStartMs), endEpochMillis = minOf(e, candidateEndMs))
        }
        .sortedBy { it.startEpochMillis }

    if (relevantExisting.isEmpty()) {
      return listOf(EpochInterval(candidateStartMs, candidateEndMs))
    }

    val uncovered = mutableListOf<EpochInterval>()
    var cursor = candidateStartMs
    for (block in relevantExisting) {
      if (block.startEpochMillis > cursor) {
        uncovered.add(EpochInterval(cursor, block.startEpochMillis))
      }
      if (block.endEpochMillis > cursor) {
        cursor = block.endEpochMillis
      }
      if (cursor >= candidateEndMs) break
    }
    if (cursor < candidateEndMs) {
      uncovered.add(EpochInterval(cursor, candidateEndMs))
    }
    return uncovered
  }
}
