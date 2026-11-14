package com.example.domain.pin

import com.example.domain.repository.PersistenceResult

enum class PinValidationError {
  BLANK,
  TOO_SHORT,
  TOO_LONG,
  NON_DIGIT,
}

data class PinHashRecord(
  val saltBase64: String,
  val hashBase64: String,
  val algorithm: String,
  val iterations: Int,
) {
  init {
    require(saltBase64.isNotBlank()) { "saltBase64 must not be blank" }
    require(hashBase64.isNotBlank()) { "hashBase64 must not be blank" }
    require(algorithm.isNotBlank()) { "algorithm must not be blank" }
    require(iterations >= 10_000) { "iterations must be >= 10000" }
  }
}

sealed interface PinConfigureResult {
  data object Success : PinConfigureResult

  data class InvalidFormat(val error: PinValidationError) : PinConfigureResult

  data object CurrentPinMismatch : PinConfigureResult

  data class LockedOut(val remainingMillis: Long) : PinConfigureResult

  data object PinNotConfigured : PinConfigureResult

  data class StorageFailure(val reason: String) : PinConfigureResult
}

sealed interface PinVerificationStatus {
  data object Verified : PinVerificationStatus

  data class Failed(
    val attemptsRemainingBeforeCooldown: Int,
    val lockoutRemainingMillis: Long = 0L,
  ) : PinVerificationStatus

  data class LockedOut(val remainingMillis: Long) : PinVerificationStatus

  data object NotConfigured : PinVerificationStatus

  data class Unavailable(val reason: String) : PinVerificationStatus
}

object PinFormatValidator {
  const val MIN_PIN_LENGTH = 4
  const val MAX_PIN_LENGTH = 8

  fun validate(rawPin: String): PinValidationError? {
    if (rawPin.isBlank()) return PinValidationError.BLANK
    if (!rawPin.all { it in '0'..'9' }) return PinValidationError.NON_DIGIT
    if (rawPin.length < MIN_PIN_LENGTH) return PinValidationError.TOO_SHORT
    if (rawPin.length > MAX_PIN_LENGTH) return PinValidationError.TOO_LONG
    return null
  }
}

interface AccountabilityPinRepository {
  suspend fun isCredentialAvailable(): Boolean

  suspend fun currentLockoutRemainingMillis(): Long

  suspend fun configurePin(newPin: String): PinConfigureResult

  suspend fun changePin(currentPin: String, newPin: String): PinConfigureResult

  suspend fun removePin(currentPin: String): PinConfigureResult

  suspend fun verifyPin(pin: String): PinVerificationStatus

  suspend fun clearPinOnVerifiedRemoval(): PersistenceResult<Unit>
}
