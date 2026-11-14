package com.example.domain.model

/**
 * Non-secret metadata indicating whether local accountability PIN protection is enabled.
 * Never holds plaintext PINs, hashes, salts, or Keystore material.
 */
data class AccountabilityMetadata(
  val isPinEnabled: Boolean = false,
  val updatedAtEpochMillis: Long = 0L,
) {
  init {
    require(updatedAtEpochMillis >= 0L) {
      "updatedAtEpochMillis must be non-negative, got $updatedAtEpochMillis"
    }
  }
}
