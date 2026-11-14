package com.example.platform.enforcement

/**
 * Validates and normalizes Android package names from accessibility events before domain evaluation.
 * Rejects null, blank, whitespace-containing, or malformed package identifiers.
 */
object PackageNameValidator {
  private val PACKAGE_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")

  fun normalize(raw: CharSequence?): String? {
    val trimmed = raw?.toString()?.trim() ?: return null
    if (trimmed.isEmpty() || trimmed.length > 255) return null
    if (!PACKAGE_REGEX.matches(trimmed)) return null
    return trimmed
  }
}
