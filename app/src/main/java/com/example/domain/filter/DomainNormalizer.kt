package com.example.domain.filter

import java.net.IDN
import java.util.Locale

enum class DomainValidationError {
  EMPTY,
  CONTAINS_URL_OR_PATH,
  CONTAINS_WHITESPACE_OR_CONTROL,
  IP_LITERAL_NOT_ALLOWED,
  INVALID_IDN,
  SINGLE_LABEL_NOT_ALLOWED,
  INVALID_LABEL_SYNTAX,
  TOO_LONG,
}

sealed interface DomainNormalizationResult {
  data class Valid(val normalizedHost: String) : DomainNormalizationResult

  data class Invalid(val error: DomainValidationError) : DomainNormalizationResult
}

/**
 * Pure domain hostname validator and canonicalizer.
 *
 * Enforces canonical lowercase ASCII/punycode hostnames without schemes, paths, wildcards, ports,
 * IP literals, trailing dots, or empty labels.
 */
object DomainNormalizer {

  private const val MAX_DOMAIN_LENGTH = 253
  private const val MAX_LABEL_LENGTH = 63
  private val IPV4_PATTERN = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
  private val LABEL_PATTERN = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

  fun normalize(raw: String): DomainNormalizationResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
      return DomainNormalizationResult.Invalid(DomainValidationError.EMPTY)
    }
    if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
      return DomainNormalizationResult.Invalid(DomainValidationError.CONTAINS_WHITESPACE_OR_CONTROL)
    }
    if (
      trimmed.contains("://") ||
        trimmed.contains('/') ||
        trimmed.contains('?') ||
        trimmed.contains('#') ||
        trimmed.contains(':') ||
        trimmed.contains('*') ||
        trimmed.contains('@')
    ) {
      return DomainNormalizationResult.Invalid(
        if (trimmed.contains(':') && !trimmed.contains("://")) {
          // Covers both ports and IPv6 literals cleanly.
          if (trimmed.startsWith("[") || trimmed.count { it == ':' } >= 2) {
            DomainValidationError.IP_LITERAL_NOT_ALLOWED
          } else {
            DomainValidationError.CONTAINS_URL_OR_PATH
          }
        } else {
          DomainValidationError.CONTAINS_URL_OR_PATH
        }
      )
    }

    val strippedTrailingDot =
      if (trimmed.endsWith('.')) trimmed.dropLast(1) else trimmed
    if (strippedTrailingDot.isEmpty() || strippedTrailingDot.endsWith('.')) {
      return DomainNormalizationResult.Invalid(DomainValidationError.INVALID_LABEL_SYNTAX)
    }
    if (strippedTrailingDot.startsWith('.')) {
      return DomainNormalizationResult.Invalid(DomainValidationError.INVALID_LABEL_SYNTAX)
    }
    if (strippedTrailingDot.contains("..")) {
      return DomainNormalizationResult.Invalid(DomainValidationError.INVALID_LABEL_SYNTAX)
    }

    val ascii =
      try {
        IDN.toASCII(strippedTrailingDot, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
      } catch (_: IllegalArgumentException) {
        return DomainNormalizationResult.Invalid(DomainValidationError.INVALID_IDN)
      }

    if (ascii.length > MAX_DOMAIN_LENGTH) {
      return DomainNormalizationResult.Invalid(DomainValidationError.TOO_LONG)
    }
    if (IPV4_PATTERN.matches(ascii)) {
      return DomainNormalizationResult.Invalid(DomainValidationError.IP_LITERAL_NOT_ALLOWED)
    }

    val labels = ascii.split('.')
    if (labels.size < 2) {
      return DomainNormalizationResult.Invalid(DomainValidationError.SINGLE_LABEL_NOT_ALLOWED)
    }
    for (label in labels) {
      if (label.isEmpty() || label.length > MAX_LABEL_LENGTH || !LABEL_PATTERN.matches(label)) {
        return DomainNormalizationResult.Invalid(DomainValidationError.INVALID_LABEL_SYNTAX)
      }
    }

    return DomainNormalizationResult.Valid(ascii)
  }

  fun isValid(raw: String): Boolean = normalize(raw) is DomainNormalizationResult.Valid
}
