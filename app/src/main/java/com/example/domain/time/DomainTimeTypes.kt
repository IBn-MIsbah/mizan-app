package com.example.domain.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ISO-8601 day of week (Monday = 1 .. Sunday = 7), independent of java.time/Android API levels.
 */
enum class DomainDayOfWeek(val isoValue: Int) {
  MONDAY(1),
  TUESDAY(2),
  WEDNESDAY(3),
  THURSDAY(4),
  FRIDAY(5),
  SATURDAY(6),
  SUNDAY(7);

  fun previous(): DomainDayOfWeek =
    entries[(ordinal - 1 + entries.size) % entries.size]

  fun next(): DomainDayOfWeek =
    entries[(ordinal + 1) % entries.size]

  companion object {
    fun fromIsoValue(isoValue: Int): DomainDayOfWeek =
      entries.firstOrNull { it.isoValue == isoValue }
        ?: throw IllegalArgumentException("Invalid ISO day of week: $isoValue")
  }
}

/**
 * Platform-independent local calendar date (proleptic Gregorian calendar).
 *
 * Used for local-midnight allowance resets, commitment dates, and schedule day matching.
 */
data class DomainDate(
  val year: Int,
  val month: Int,
  val dayOfMonth: Int,
) : Comparable<DomainDate> {

  init {
    require(year in 1970..9999) { "Year must be between 1970 and 9999, got $year" }
    require(month in 1..12) { "Month must be between 1 and 12, got $month" }
    val maxDay = daysInMonth(year, month)
    require(dayOfMonth in 1..maxDay) {
      "Day must be between 1 and $maxDay for $year-$month, got $dayOfMonth"
    }
  }

  /**
   * Computes day of week using Tomohiko Sakamoto's algorithm (1 = Monday .. 7 = Sunday).
   */
  fun dayOfWeek(): DomainDayOfWeek {
    val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    val adjustedYear = if (month < 3) year - 1 else year
    // 0 = Sunday, 1 = Monday, ..., 6 = Saturday
    val sundayBased =
      (adjustedYear + adjustedYear / 4 - adjustedYear / 100 + adjustedYear / 400 +
        t[month - 1] + dayOfMonth) % 7
    val iso = if (sundayBased == 0) 7 else sundayBased
    return DomainDayOfWeek.fromIsoValue(iso)
  }

  fun plusDays(days: Int): DomainDate {
    require(days >= 0) { "Only non-negative day offsets are supported, got $days" }
    var currentYear = year
    var currentMonth = month
    var currentDay = dayOfMonth
    var remaining = days
    while (remaining > 0) {
      val maxDay = daysInMonth(currentYear, currentMonth)
      val step = minOf(remaining, maxDay - currentDay)
      if (step > 0) {
        currentDay += step
        remaining -= step
      } else {
        remaining -= 1
        currentDay = 1
        if (currentMonth == 12) {
          currentMonth = 1
          currentYear += 1
        } else {
          currentMonth += 1
        }
      }
    }
    return DomainDate(currentYear, currentMonth, currentDay)
  }

  override fun compareTo(other: DomainDate): Int =
    compareValuesBy(this, other, DomainDate::year, DomainDate::month, DomainDate::dayOfMonth)

  companion object {
    fun isLeapYear(year: Int): Boolean =
      (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

    fun daysInMonth(year: Int, month: Int): Int =
      when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> throw IllegalArgumentException("Invalid month: $month")
      }
  }
}

/**
 * Platform-independent local wall-clock time of day (00:00:00 .. 23:59:59).
 */
data class DomainTime(
  val hour: Int,
  val minute: Int,
  val second: Int = 0,
) : Comparable<DomainTime> {

  init {
    require(hour in 0..23) { "Hour must be in 0..23, got $hour" }
    require(minute in 0..59) { "Minute must be in 0..59, got $minute" }
    require(second in 0..59) { "Second must be in 0..59, got $second" }
  }

  val secondsOfDay: Int
    get() = hour * 3600 + minute * 60 + second

  override fun compareTo(other: DomainTime): Int =
    secondsOfDay.compareTo(other.secondsOfDay)

  companion object {
    val MIDNIGHT = DomainTime(0, 0, 0)
  }
}

/**
 * Snapshot of wall-clock / local calendar time supplied explicitly to pure domain logic.
 *
 * Limitations:
 * - [date] and [time] reflect the local calendar representation resolved by the host clock/timezone
 *   at observation time. Pure domain code does not bundle an IANA tzdb to resolve DST gaps or folds
 *   independently.
 * - Offline software cannot guarantee trusted wall-clock time if a user alters the device clock.
 */
data class WallClockPoint(
  val date: DomainDate,
  val time: DomainTime,
  val epochMillis: Long,
  val utcOffsetMinutes: Int = 0,
  val zoneId: String = "UTC",
) {
  init {
    require(utcOffsetMinutes in -18 * 60..18 * 60) {
      "UTC offset minutes must be within [-1080, 1080], got $utcOffsetMinutes"
    }
    require(zoneId.isNotBlank()) { "zoneId must not be blank" }
  }

  fun dayOfWeek(): DomainDayOfWeek = date.dayOfWeek()

  /**
   * Advances this wall-clock point by [days] calendar days while preserving wall-clock [time]
   * and advancing [epochMillis] by the exact 24-hour multiple.
   *
   * Note: across a Daylight Saving Time transition, 168 real elapsed hours differs from
   * 7 wall-clock days at the same local hour by +/- 1 hour; commitment end boundaries use
   * elapsed UTC epoch time (`epochMillis + duration.inWholeMilliseconds`).
   */
  fun plusElapsedDays(days: Int): WallClockPoint {
    require(days >= 0) { "days must be non-negative, got $days" }
    return copy(
      date = date.plusDays(days),
      epochMillis = epochMillis + days.toLong() * MILLIS_PER_DAY,
    )
  }

  companion object {
    const val MILLIS_PER_DAY: Long = 86_400_000L
  }
}

/**
 * Monotonic elapsed milliseconds within a single device boot session.
 *
 * Important limitations:
 * - Values from different boot sessions are NOT directly comparable because monotonic clocks
 *   reset on reboot.
 * - Cross-reboot duration recovery requires persisted boot markers and conservative reconciliation
 *   in later persistence/platform milestones.
 */
@JvmInline
value class MonotonicTimestamp(val elapsedMillis: Long) : Comparable<MonotonicTimestamp> {
  init {
    require(elapsedMillis >= 0L) {
      "Monotonic elapsedMillis must be non-negative, got $elapsedMillis"
    }
  }

  fun elapsedSince(earlier: MonotonicTimestamp): Duration {
    require(elapsedMillis >= earlier.elapsedMillis) {
      "Monotonic timestamp $elapsedMillis precedes earlier timestamp ${earlier.elapsedMillis}; " +
        "timestamps from separate boot sessions cannot be subtracted directly."
    }
    return (elapsedMillis - earlier.elapsedMillis).milliseconds
  }

  override fun compareTo(other: MonotonicTimestamp): Int =
    elapsedMillis.compareTo(other.elapsedMillis)
}

/**
 * Boundary interface providing wall-clock and monotonic time to callers outside pure domain evaluators.
 */
interface TimeProvider {
  fun currentWallClock(): WallClockPoint

  fun currentMonotonic(): MonotonicTimestamp

  /**
   * Resolves a [WallClockPoint] for [epochMillis] using the local timezone when available.
   */
  fun wallClockAt(epochMillis: Long): WallClockPoint {
    val ref = currentWallClock()
    val localMillis = epochMillis + ref.utcOffsetMinutes.toLong() * 60_000L
    val totalDays = Math.floorDiv(localMillis, WallClockPoint.MILLIS_PER_DAY)
    val millisOfDay = Math.floorMod(localMillis, WallClockPoint.MILLIS_PER_DAY)
    val secondsOfDay = (millisOfDay / 1_000L).toInt()
    val hour = secondsOfDay / 3600
    val minute = (secondsOfDay % 3600) / 60
    val second = secondsOfDay % 60
    val date = epochDaysToDomainDate(totalDays)
    return WallClockPoint(
      date = date,
      time = DomainTime(hour, minute, second),
      epochMillis = epochMillis,
      utcOffsetMinutes = ref.utcOffsetMinutes,
      zoneId = ref.zoneId,
    )
  }

  /**
   * Returns the UTC epoch millis of the next local calendar midnight strictly after [after].
   *
   * Platform implementations should resolve using OS timezone rules (including DST); this pure
   * fallback advances cleanly across 24-hour calendar days.
   */
  fun nextLocalMidnightEpochMillis(after: WallClockPoint): Long {
    val subSecondRemainder = ((after.epochMillis % 1_000L) + 1_000L) % 1_000L
    val elapsedMillisInDay = after.time.secondsOfDay.toLong() * 1_000L + subSecondRemainder
    return (after.epochMillis - elapsedMillisInDay) + WallClockPoint.MILLIS_PER_DAY
  }
}

private fun epochDaysToDomainDate(epochDays: Long): DomainDate {
  // Howard Hinnant's civil-from-days algorithm
  val z = epochDays + 719468L
  val era = if (z >= 0) z / 146097L else (z - 146096L) / 146097L
  val doe = z - era * 146097L
  val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
  val y = yoe + era * 400L
  val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
  val mp = (5L * doy + 2L) / 153L
  val d = doy - (153L * mp + 2L) / 5L + 1L
  val m = mp + (if (mp < 10L) 3L else -9L)
  val finalYear = (y + if (m <= 2L) 1L else 0L).toInt().coerceIn(1970, 9999)
  return DomainDate(finalYear, m.toInt(), d.toInt())
}
