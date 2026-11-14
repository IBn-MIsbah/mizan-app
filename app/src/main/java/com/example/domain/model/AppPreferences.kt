package com.example.domain.model

enum class ThemePreference {
  SYSTEM,
  LIGHT,
  DARK,
}

data class AppPreferences(
  val themePreference: ThemePreference = ThemePreference.SYSTEM,
  val onboardingCompleted: Boolean = false,
  val wifiOnlyFilterUpdates: Boolean = true,
  val websiteFilteringConsented: Boolean = false,
  val vpnStoppedByUser: Boolean = false,
  val firstUseGuideCompleted: Boolean = false,
  val reduceGuideAnimations: Boolean = false,
)
