package com.example.data.local.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.core.common.DispatcherProvider
import com.example.data.repository.DataStoreUserPreferencesRepository
import com.example.domain.model.AppPreferences
import com.example.domain.model.ThemePreference
import com.example.domain.repository.PersistenceResult
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesRepositoryTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val testDispatcher = UnconfinedTestDispatcher()
  private val dispatcherProvider =
    object : DispatcherProvider {
      override val main: CoroutineDispatcher = testDispatcher
      override val io: CoroutineDispatcher = testDispatcher
      override val default: CoroutineDispatcher = testDispatcher
    }

  private fun createDataStore(file: File, scope: TestScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
      scope = scope.backgroundScope,
      corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
      produceFile = { file },
    )

  @Test
  fun `missing keys emit safe defaults and updates persist cleanly`() = runTest(testDispatcher) {
    val prefsFile = File(tempFolder.root, "test_prefs.preferences_pb")
    val dataStore = createDataStore(prefsFile, this)
    val repository =
      DataStoreUserPreferencesRepository(MizanPreferencesDataSource(dataStore), dispatcherProvider)

    assertEquals(
      AppPreferences(
        themePreference = ThemePreference.SYSTEM,
        onboardingCompleted = false,
        wifiOnlyFilterUpdates = true,
      ),
      repository.preferences.first(),
    )

    assertEquals(
      PersistenceResult.Success(Unit),
      repository.setThemePreference(ThemePreference.DARK),
    )
    assertEquals(PersistenceResult.Success(Unit), repository.setOnboardingCompleted(true))
    assertEquals(PersistenceResult.Success(Unit), repository.setWifiOnlyFilterUpdates(false))

    assertEquals(
      AppPreferences(
        themePreference = ThemePreference.DARK,
        onboardingCompleted = true,
        wifiOnlyFilterUpdates = false,
      ),
      repository.preferences.first(),
    )
  }

  @Test
  fun `invalid stored theme preference string safely defaults to system`() =
    runTest(testDispatcher) {
      val prefsFile = File(tempFolder.root, "invalid_enum.preferences_pb")
      val dataStore = createDataStore(prefsFile, this)
      dataStore.edit { prefs ->
        prefs[stringPreferencesKey("theme_preference")] = "UNSUPPORTED_FUTURE_THEME"
      }
      val repository =
        DataStoreUserPreferencesRepository(MizanPreferencesDataSource(dataStore), dispatcherProvider)

      val loaded = repository.preferences.first()
      assertEquals(ThemePreference.SYSTEM, loaded.themePreference)
    }

  @Test
  fun `corrupt preferences file recovers to safe defaults via corruption handler`() =
    runTest(testDispatcher) {
      val prefsFile = File(tempFolder.root, "corrupt.preferences_pb")
      prefsFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x7F, -0x01, 0x42))

      val dataStore = createDataStore(prefsFile, this)
      val repository =
        DataStoreUserPreferencesRepository(MizanPreferencesDataSource(dataStore), dispatcherProvider)

      val recovered = repository.preferences.first()
      assertEquals(AppPreferences(), recovered)
    }
}
