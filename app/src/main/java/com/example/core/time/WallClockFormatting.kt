package com.example.core.time

import com.example.domain.model.ScheduleWindow
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import java.util.Locale
import kotlin.time.Duration

object WallClockFormatting {

  fun formatDate(date: DomainDate): String =
    String.format(Locale.US, "%04d-%02d-%02d", date.year, date.month, date.dayOfMonth)

  fun formatTime(time: DomainTime): String =
    String.format(Locale.US, "%02d:%02d", time.hour, time.minute)

  fun formatDateTime(point: WallClockPoint): String =
    "${formatDate(point.date)} · ${formatTime(point.time)} (${point.zoneId})"

  fun formatDuration(duration: Duration): String {
    val totalMinutes = duration.inWholeMinutes
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
      hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
      hours > 0 -> "${hours}h"
      else -> "${minutes}m"
    }
  }

  fun formatShortDay(day: DomainDayOfWeek): String =
    when (day) {
      DomainDayOfWeek.MONDAY -> "Mon"
      DomainDayOfWeek.TUESDAY -> "Tue"
      DomainDayOfWeek.WEDNESDAY -> "Wed"
      DomainDayOfWeek.THURSDAY -> "Thu"
      DomainDayOfWeek.FRIDAY -> "Fri"
      DomainDayOfWeek.SATURDAY -> "Sat"
      DomainDayOfWeek.SUNDAY -> "Sun"
    }

  fun formatScheduleWindow(window: ScheduleWindow): String {
    val days = window.daysOfWeek.sortedBy { it.isoValue }.joinToString(", ") { formatShortDay(it) }
    val overnightSuffix = if (window.crossesMidnight) " (+1d)" else ""
    return "$days · ${formatTime(window.start)}–${formatTime(window.end)}$overnightSuffix"
  }
}
