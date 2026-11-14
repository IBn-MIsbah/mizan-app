package com.example.domain.repository

import com.example.domain.model.AppPreferences
import com.example.domain.model.ThemePreference
import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
  val preferences: Flow<AppPreferences>

  suspend fun setThemePreference(themePreference: ThemePreference): PersistenceResult<Unit>

  suspend fun setOnboardingCompleted(completed: Boolean): PersistenceResult<Unit>

  suspend fun setWifiOnlyFilterUpdates(wifiOnly: Boolean): PersistenceResult<Unit>

  suspend fun setWebsiteFilteringConsented(consented: Boolean): PersistenceResult<Unit>

  suspend fun setVpnStoppedByUser(stoppedByUser: Boolean): PersistenceResult<Unit>
}
