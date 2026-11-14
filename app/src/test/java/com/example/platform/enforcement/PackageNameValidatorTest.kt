package com.example.platform.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageNameValidatorTest {

  @Test
  fun `valid android package names are normalized`() {
    assertEquals("com.instagram.android", PackageNameValidator.normalize("com.instagram.android"))
    assertEquals("org.example.app_1", PackageNameValidator.normalize("  org.example.app_1  "))
  }

  @Test
  fun `null empty whitespace dotless and malformed package names are rejected`() {
    assertNull(PackageNameValidator.normalize(null))
    assertNull(PackageNameValidator.normalize(""))
    assertNull(PackageNameValidator.normalize("   "))
    assertNull(PackageNameValidator.normalize("android"))
    assertNull(PackageNameValidator.normalize("com..double"))
    assertNull(PackageNameValidator.normalize(".leading.dot"))
    assertNull(PackageNameValidator.normalize("trailing.dot."))
    assertNull(PackageNameValidator.normalize("com.bad package.name"))
    assertNull(PackageNameValidator.normalize("com.bad/slash"))
  }
}
