package com.example.data.local.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.domain.model.AppPreferences
import com.example.domain.model.ThemePreference
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class MizanPreferencesDataSource
@Inject
constructor(private val dataStore: DataStore<Preferences>) {

  val preferences: Flow<AppPreferences> =
    dataStore.data
      .catch { exception ->
        if (exception is IOException) {
          emit(emptyPreferences())
        } else {
          throw exception
        }
      }
      .map { prefs ->
        AppPreferences(
          themePreference = decodeThemePreference(prefs[KEY_THEME_PREFERENCE]),
          onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
          wifiOnlyFilterUpdates = prefs[KEY_WIFI_ONLY_FILTER_UPDATES] ?: true,
          websiteFilteringConsented = prefs[KEY_WEBSITE_FILTERING_CONSENTED] ?: false,
          vpnStoppedByUser = prefs[KEY_VPN_STOPPED_BY_USER] ?: false,
        )
      }

  suspend fun setThemePreference(themePreference: ThemePreference) {
    dataStore.edit { prefs -> prefs[KEY_THEME_PREFERENCE] = themePreference.name }
  }

  suspend fun setOnboardingCompleted(completed: Boolean) {
    dataStore.edit { prefs -> prefs[KEY_ONBOARDING_COMPLETED] = completed }
  }

  suspend fun setWifiOnlyFilterUpdates(wifiOnly: Boolean) {
    dataStore.edit { prefs -> prefs[KEY_WIFI_ONLY_FILTER_UPDATES] = wifiOnly }
  }

  suspend fun setWebsiteFilteringConsented(consented: Boolean) {
    dataStore.edit { prefs -> prefs[KEY_WEBSITE_FILTERING_CONSENTED] = consented }
  }

  suspend fun setVpnStoppedByUser(stoppedByUser: Boolean) {
    dataStore.edit { prefs -> prefs[KEY_VPN_STOPPED_BY_USER] = stoppedByUser }
  }

  internal fun decodeThemePreference(raw: String?): ThemePreference =
    ThemePreference.entries.firstOrNull { it.name == raw } ?: ThemePreference.SYSTEM

  companion object {
    val KEY_THEME_PREFERENCE = stringPreferencesKey("theme_preference")
    val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val KEY_WIFI_ONLY_FILTER_UPDATES = booleanPreferencesKey("wifi_only_filter_updates")
    val KEY_WEBSITE_FILTERING_CONSENTED = booleanPreferencesKey("website_filtering_consented")
    val KEY_VPN_STOPPED_BY_USER = booleanPreferencesKey("vpn_stopped_by_user")
  }
}
