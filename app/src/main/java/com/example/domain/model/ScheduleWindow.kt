package com.example.domain.model

import com.example.domain.time.DomainDate
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime

/**
 * A half-open local schedule window `[start, end)` active on [daysOfWeek].
 *
 * - If [start] < [end], the window applies on the same calendar day (`start <= time < end`).
 * - If [end] < [start], the window crosses midnight:
 *   - From [start] to 23:59:59 on a selected day in [daysOfWeek]
 *   - From 00:00:00 until [end] (exclusive) on the following calendar day.
 * - Zero-length windows (`start == end`) and empty [daysOfWeek] are rejected.
 */
data class ScheduleWindow(
  val daysOfWeek: Set<DomainDayOfWeek>,
  val start: DomainTime,
  val end: DomainTime,
) {
  init {
    require(daysOfWeek.isNotEmpty()) { "ScheduleWindow requires at least one day of week" }
    require(start != end) { "ScheduleWindow must not be zero-length (start == end: $start)" }
  }

  val crossesMidnight: Boolean
    get() = end < start

  fun isActiveAt(date: DomainDate, time: DomainTime): Boolean {
    val today = date.dayOfWeek()
    return if (!crossesMidnight) {
      today in daysOfWeek && time >= start && time < end
    } else {
      val activeEvening = today in daysOfWeek && time >= start
      val activeMorningAfter = today.previous() in daysOfWeek && time < end
      activeEvening || activeMorningAfter
    }
  }
}
