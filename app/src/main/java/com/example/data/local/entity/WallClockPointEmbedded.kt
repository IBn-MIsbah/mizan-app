package com.example.data.local.entity

/**
 * Embedded Room value object storing [com.example.domain.time.WallClockPoint] losslessly
 * without altering local calendar date/time, epoch timestamp, UTC offset, or zone ID.
 */
data class WallClockPointEmbedded(
  val dateIso: String,
  val timeIso: String,
  val epochMillis: Long,
  val utcOffsetMinutes: Int,
  val zoneId: String,
)
