package com.example

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.data.filter.DatasetFeedPolicy
import com.example.domain.repository.DomainDatasetSignatureVerifier
import com.example.domain.repository.UserPreferencesRepository
import com.example.platform.filter.DatasetUpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class MizanApplication : Application(), Configuration.Provider {

  @Inject lateinit var workerFactory: HiltWorkerFactory
  @Inject lateinit var datasetUpdateScheduler: DatasetUpdateScheduler
  @Inject lateinit var userPreferencesRepository: UserPreferencesRepository
  @Inject lateinit var feedPolicy: DatasetFeedPolicy
  @Inject lateinit var signatureVerifier: DomainDatasetSignatureVerifier

  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

  override fun onCreate() {
    super.onCreate()
    if (feedPolicy.hasAnyConfiguredFeed() && signatureVerifier.isTrustedKeyConfigured) {
      applicationScope.launch {
        val wifiOnly =
          runCatching { userPreferencesRepository.preferences.first().wifiOnlyFilterUpdates }
            .getOrDefault(true)
        runCatching { datasetUpdateScheduler.syncPeriodicSchedule(wifiOnly) }
      }
    }
  }
}
