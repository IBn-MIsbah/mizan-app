package com.example.core.time

import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemTimeProviderTest {

  @Test
  fun `fromEpochMillis converts UTC epoch timestamp accurately`() {
    val provider = SystemTimeProvider()
    // 2026-11-13 09:00:00 UTC -> epochMillis = 1794560400000L
    val point =
      provider.fromEpochMillis(
        epochMillis = 1_794_560_400_000L,
        timeZone = TimeZone.getTimeZone("UTC"),
      )

    assertEquals(DomainDate(2026, 11, 13), point.date)
    assertEquals(DomainTime(9, 0, 0), point.time)
    assertEquals(0, point.utcOffsetMinutes)
    assertEquals("UTC", point.zoneId)
  }
}
