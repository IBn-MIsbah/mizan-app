package com.example.domain.pin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

interface PinHasher {
  fun hash(pin: CharArray): PinHashRecord

  fun verify(pin: CharArray, record: PinHashRecord): Boolean
}

@Singleton
class Pbkdf2PinHasher @Inject constructor() : PinHasher {

  private val secureRandom = SecureRandom()

  override fun hash(pin: CharArray): PinHashRecord {
    require(pin.isNotEmpty()) { "PIN must not be empty" }
    val salt = ByteArray(SALT_LENGTH_BYTES)
    secureRandom.nextBytes(salt)
    val derived = deriveKey(pin = pin, salt = salt, algorithm = ALGORITHM, iterations = ITERATIONS)
    return try {
      PinHashRecord(
        saltBase64 = Base64.getEncoder().encodeToString(salt),
        hashBase64 = Base64.getEncoder().encodeToString(derived),
        algorithm = ALGORITHM,
        iterations = ITERATIONS,
      )
    } finally {
      derived.fill(0)
      salt.fill(0)
    }
  }

  override fun verify(pin: CharArray, record: PinHashRecord): Boolean {
    if (pin.isEmpty()) return false
    val salt =
      try {
        Base64.getDecoder().decode(record.saltBase64)
      } catch (_: IllegalArgumentException) {
        return false
      }
    val expectedHash =
      try {
        Base64.getDecoder().decode(record.hashBase64)
      } catch (_: IllegalArgumentException) {
        return false
      }
    if (salt.isEmpty() || expectedHash.isEmpty()) {
      return false
    }

    val actualHash =
      try {
        deriveKey(
          pin = pin,
          salt = salt,
          algorithm = record.algorithm,
          iterations = record.iterations,
          keyLengthBits = expectedHash.size * 8,
        )
      } catch (_: Exception) {
        return false
      }

    return try {
      MessageDigest.isEqual(expectedHash, actualHash)
    } finally {
      actualHash.fill(0)
      expectedHash.fill(0)
      salt.fill(0)
    }
  }

  private fun deriveKey(
    pin: CharArray,
    salt: ByteArray,
    algorithm: String,
    iterations: Int,
    keyLengthBits: Int = KEY_LENGTH_BITS,
  ): ByteArray {
    val factory = SecretKeyFactory.getInstance(algorithm)
    val spec = PBEKeySpec(pin, salt, iterations, keyLengthBits)
    return try {
      factory.generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }

  companion object {
    const val ALGORITHM = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 120_000
    const val SALT_LENGTH_BYTES = 16
    const val KEY_LENGTH_BITS = 256
  }
}
