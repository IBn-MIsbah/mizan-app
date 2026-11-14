package com.example.domain.model

import com.example.domain.time.WallClockPoint

@JvmInline
value class SecurityEventId(val value: String) {
  init {
    require(value.isNotBlank()) { "SecurityEventId must not be blank" }
  }
}

enum class SecurityEventType {
  COMMITMENT_ACTIVATED,
  COMMITMENT_COMPLETED,
  EMERGENCY_TERMINATED,
  WALL_CLOCK_ANOMALY_OBSERVED,
  PERMISSION_REVOKED,
  SERVICE_INTERRUPTED,
  VPN_PERMISSION_REVOKED,
  VPN_CONFLICT_DETECTED,
  VPN_FILTER_STARTED,
  VPN_FILTER_STOPPED,
  PIN_CONFIGURED,
  PIN_REMOVED,
  PIN_VERIFICATION_FAILED,
  REMOVAL_REQUEST_CREATED,
  REMOVAL_REQUEST_CANCELLED,
  REMOVAL_REQUEST_EXECUTED,
  DEVICE_REBOOT_RECOVERED,
  APP_UPDATED_RECOVERED,
}

/**
 * Non-sensitive local integrity and lifecycle audit event.
 * Never store PINs, hashes, secrets, or sensitive browsing/usage details here.
 */
data class SecurityEvent(
  val id: SecurityEventId,
  val commitmentId: CommitmentId?,
  val eventType: SecurityEventType,
  val occurredAt: WallClockPoint,
  val summary: String? = null,
)
