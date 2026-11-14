package com.example.domain.model

import com.example.domain.time.DomainDate
import com.example.domain.time.DomainDayOfWeek
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWindowTest {

  @Test
  fun `same day schedule uses half open interval start inclusive end exclusive`() {
    val window =
      ScheduleWindow(
        daysOfWeek = setOf(DomainDayOfWeek.MONDAY),
        start = DomainTime(9, 0, 0),
        end = DomainTime(17, 0, 0),
      )
    val monday = DomainDate(2026, 11, 16) // Monday
    val tuesday = DomainDate(2026, 11, 17) // Tuesday

    assertFalse(window.isActiveAt(monday, DomainTime(8, 59, 59)))
    assertTrue(window.isActiveAt(monday, DomainTime(9, 0, 0)))
    assertTrue(window.isActiveAt(monday, DomainTime(16, 59, 59)))
    assertFalse(window.isActiveAt(monday, DomainTime(17, 0, 0)))
    assertFalse(window.isActiveAt(tuesday, DomainTime(12, 0, 0)))
  }

  @Test
  fun `overnight window crosses midnight and ends exclusively on following morning`() {
    val overnight =
      ScheduleWindow(
        daysOfWeek = setOf(DomainDayOfWeek.FRIDAY),
        start = DomainTime(22, 0, 0),
        end = DomainTime(6, 0, 0),
      )
    val friday = DomainDate(2026, 11, 13) // Friday
    val saturday = DomainDate(2026, 11, 14) // Saturday

    assertTrue(overnight.crossesMidnight)
    // Friday morning is not active because Thursday is unselected
    assertFalse(overnight.isActiveAt(friday, DomainTime(3, 0, 0)))
    // Friday 21:59:59 before start
    assertFalse(overnight.isActiveAt(friday, DomainTime(21, 59, 59)))
    // Friday 22:00:00 inclusive start
    assertTrue(overnight.isActiveAt(friday, DomainTime(22, 0, 0)))
    // Saturday 00:00:00 and 05:59:59 still active from Friday night
    assertTrue(overnight.isActiveAt(saturday, DomainTime(0, 0, 0)))
    assertTrue(overnight.isActiveAt(saturday, DomainTime(5, 59, 59)))
    // Saturday 06:00:00 exclusive end
    assertFalse(overnight.isActiveAt(saturday, DomainTime(6, 0, 0)))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `zero length schedule window is rejected`() {
    ScheduleWindow(
      daysOfWeek = setOf(DomainDayOfWeek.MONDAY),
      start = DomainTime(10, 0, 0),
      end = DomainTime(10, 0, 0),
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `schedule window with empty days of week is rejected`() {
    ScheduleWindow(
      daysOfWeek = emptySet(),
      start = DomainTime(10, 0, 0),
      end = DomainTime(11, 0, 0),
    )
  }

  @Test
  fun `overnight schedule across dst offset transition remains anchored to local wall clock`() {
    val window =
      ScheduleWindow(
        daysOfWeek = setOf(DomainDayOfWeek.SATURDAY),
        start = DomainTime(22, 0, 0),
        end = DomainTime(6, 0, 0),
      )
    // Simulate US fall-back night (UTC-4 -> UTC-5 at 02:00 local)
    val beforeFallback =
      WallClockPoint(
        date = DomainDate(2026, 10, 31), // Saturday
        time = DomainTime(23, 30, 0),
        epochMillis = 1_793_503_800_000L,
        utcOffsetMinutes = -240,
        zoneId = "America/New_York",
      )
    val afterFallback =
      WallClockPoint(
        date = DomainDate(2026, 11, 1), // Sunday
        time = DomainTime(5, 30, 0),
        epochMillis = beforeFallback.epochMillis + 7L * 3_600_000L,
        utcOffsetMinutes = -300,
        zoneId = "America/New_York",
      )

    assertTrue(window.isActiveAt(beforeFallback.date, beforeFallback.time))
    assertTrue(window.isActiveAt(afterFallback.date, afterFallback.time))
    assertEquals(-300, afterFallback.utcOffsetMinutes)
  }
}
