package com.example.data.repository

import com.example.core.common.DispatcherProvider
import com.example.data.local.preferences.MizanPreferencesDataSource
import com.example.domain.model.AppPreferences
import com.example.domain.model.ThemePreference
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.UserPreferencesRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

@Singleton
class DataStoreUserPreferencesRepository
@Inject
constructor(
  private val dataSource: MizanPreferencesDataSource,
  private val dispatcherProvider: DispatcherProvider,
) : UserPreferencesRepository {

  override val preferences: Flow<AppPreferences> =
    dataSource.preferences.flowOn(dispatcherProvider.io)

  override suspend fun setThemePreference(themePreference: ThemePreference): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dataSource.setThemePreference(themePreference)
        PersistenceResult.Success(Unit)
      } catch (_: IOException) {
        PersistenceResult.Failure(PersistenceError.StorageFailure("Failed to write theme preference"))
      }
    }

  override suspend fun setOnboardingCompleted(completed: Boolean): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dataSource.setOnboardingCompleted(completed)
        PersistenceResult.Success(Unit)
      } catch (_: IOException) {
        PersistenceResult.Failure(
          PersistenceError.StorageFailure("Failed to write onboarding preference")
        )
      }
    }

  override suspend fun setWifiOnlyFilterUpdates(wifiOnly: Boolean): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dataSource.setWifiOnlyFilterUpdates(wifiOnly)
        PersistenceResult.Success(Unit)
      } catch (_: IOException) {
        PersistenceResult.Failure(
          PersistenceError.StorageFailure("Failed to write Wi-Fi preference")
        )
      }
    }

  override suspend fun setWebsiteFilteringConsented(consented: Boolean): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dataSource.setWebsiteFilteringConsented(consented)
        PersistenceResult.Success(Unit)
      } catch (_: IOException) {
        PersistenceResult.Failure(
          PersistenceError.StorageFailure("Failed to write website filtering consent")
        )
      }
    }

  override suspend fun setVpnStoppedByUser(stoppedByUser: Boolean): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dataSource.setVpnStoppedByUser(stoppedByUser)
        PersistenceResult.Success(Unit)
      } catch (_: IOException) {
        PersistenceResult.Failure(
          PersistenceError.StorageFailure("Failed to write VPN stop preference")
        )
      }
    }

  override suspend fun setFirstUseGuideCompleted(completed: Boolean): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dataSource.setFirstUseGuideCompleted(completed)
        PersistenceResult.Success(Unit)
      } catch (_: IOException) {
        PersistenceResult.Failure(
          PersistenceError.StorageFailure("Failed to write guide completion preference")
        )
      }
    }

  override suspend fun setReduceGuideAnimations(reduce: Boolean): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dataSource.setReduceGuideAnimations(reduce)
        PersistenceResult.Success(Unit)
      } catch (_: IOException) {
        PersistenceResult.Failure(
          PersistenceError.StorageFailure("Failed to write reduced-motion preference")
        )
      }
    }
}
