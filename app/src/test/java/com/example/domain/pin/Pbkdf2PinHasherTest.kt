package com.example.domain.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pbkdf2PinHasherTest {

  private val hasher = Pbkdf2PinHasher()

  @Test
  fun `hash produces distinct random salts and verifies matching PIN`() {
    val first = hasher.hash("4829".toCharArray())
    val second = hasher.hash("4829".toCharArray())

    assertEquals(Pbkdf2PinHasher.ALGORITHM, first.algorithm)
    assertEquals(Pbkdf2PinHasher.ITERATIONS, first.iterations)
    assertNotEquals(first.saltBase64, second.saltBase64)
    assertNotEquals(first.hashBase64, second.hashBase64)

    assertTrue(hasher.verify("4829".toCharArray(), first))
    assertTrue(hasher.verify("4829".toCharArray(), second))
  }

  @Test
  fun `verify rejects wrong PIN and tampered salt or hash`() {
    val record = hasher.hash("123456".toCharArray())

    assertFalse(hasher.verify("123457".toCharArray(), record))
    assertFalse(hasher.verify("12345".toCharArray(), record))
    assertFalse(hasher.verify(charArrayOf(), record))
    assertFalse(
      hasher.verify(
        "123456".toCharArray(),
        record.copy(saltBase64 = "AAAA"),
      )
    )
    assertFalse(
      hasher.verify(
        "123456".toCharArray(),
        record.copy(hashBase64 = "AAAA"),
      )
    )
    assertFalse(
      hasher.verify(
        "123456".toCharArray(),
        record.copy(algorithm = "NoSuchAlgorithm"),
      )
    )
  }

  @Test
  fun `PinFormatValidator enforces 4 to 8 numeric digits`() {
    assertEquals(PinValidationError.BLANK, PinFormatValidator.validate(""))
    assertEquals(PinValidationError.BLANK, PinFormatValidator.validate("   "))
    assertEquals(PinValidationError.NON_DIGIT, PinFormatValidator.validate("12a4"))
    assertEquals(PinValidationError.TOO_SHORT, PinFormatValidator.validate("123"))
    assertEquals(PinValidationError.TOO_LONG, PinFormatValidator.validate("123456789"))
    assertEquals(null, PinFormatValidator.validate("1234"))
    assertEquals(null, PinFormatValidator.validate("12345678"))
  }
}
