package com.example.core.time

import android.os.SystemClock
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.TimeProvider
import com.example.domain.time.WallClockPoint
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-backed [TimeProvider] compatible with minSdk 24 using [Calendar] and [SystemClock].
 *
 * Offline software cannot guarantee trusted wall-clock time if the device clock is modified;
 * monotonic elapsed time resets on reboot.
 */
@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {

  override fun currentWallClock(): WallClockPoint {
    val epochMillis = System.currentTimeMillis()
    val timeZone = TimeZone.getDefault()
    return fromEpochMillis(epochMillis = epochMillis, timeZone = timeZone)
  }

  override fun currentMonotonic(): MonotonicTimestamp =
    MonotonicTimestamp(SystemClock.elapsedRealtime().coerceAtLeast(0L))

  internal fun fromEpochMillis(epochMillis: Long, timeZone: TimeZone): WallClockPoint {
    val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
    val date =
      DomainDate(
        year = calendar.get(Calendar.YEAR),
        month = calendar.get(Calendar.MONTH) + 1,
        dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH),
      )
    val time =
      DomainTime(
        hour = calendar.get(Calendar.HOUR_OF_DAY),
        minute = calendar.get(Calendar.MINUTE),
        second = calendar.get(Calendar.SECOND),
      )
    val offsetMinutes = timeZone.getOffset(epochMillis) / 60_000
    val zoneId = timeZone.id.ifBlank { "UTC" }
    return WallClockPoint(
      date = date,
      time = time,
      epochMillis = epochMillis,
      utcOffsetMinutes = offsetMinutes.coerceIn(-18 * 60, 18 * 60),
      zoneId = zoneId,
    )
  }

  override fun wallClockAt(epochMillis: Long): WallClockPoint =
    fromEpochMillis(epochMillis = epochMillis, timeZone = TimeZone.getDefault())

  override fun nextLocalMidnightEpochMillis(after: WallClockPoint): Long {
    val timeZone = TimeZone.getTimeZone(after.zoneId)
    val calendar =
      Calendar.getInstance(timeZone).apply {
        timeInMillis = after.epochMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, 1)
      }
    return calendar.timeInMillis
  }
}
