package com.example.domain.model

/**
 * Pure domain representation of a user-selected restricted application.
 * Does not hold Android PackageManager or UI state.
 */
data class RestrictedApp(
  val appId: AppId,
  val displayLabel: String,
) {
  init {
    require(displayLabel.isNotBlank()) { "RestrictedApp displayLabel must not be blank" }
  }
}

enum class AllowlistCategory {
  EMERGENCY_PHONE,
  SMS,
  BANKING,
  MAPS,
  WORK,
  CUSTOM,
}

/**
 * Pure domain representation of an explicitly allowlisted application.
 * Allowlisted apps always take precedence over restriction rules.
 */
data class AllowedApp(
  val appId: AppId,
  val displayLabel: String,
  val category: AllowlistCategory = AllowlistCategory.CUSTOM,
) {
  init {
    require(displayLabel.isNotBlank()) { "AllowedApp displayLabel must not be blank" }
  }
}
