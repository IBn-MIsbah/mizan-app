package com.example.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.core.common.toDisplayMessage
import com.example.domain.model.AppPreferences
import com.example.domain.model.Commitment
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.model.ThemePreference
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.UserPreferencesRepository
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.health.EnforcementHealthSnapshot
import com.example.platform.health.UsageAccessMonitor
import com.example.platform.health.UsageAccessSnapshot
import com.example.platform.usage.UsageStatsReconciler
import com.example.platform.vpn.VpnFilterMonitor
import com.example.platform.vpn.VpnFilterSnapshot
import com.example.platform.vpn.VpnStartRequestOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsSummary(
  val preferences: AppPreferences,
  val applicationId: String,
  val versionName: String,
  val healthSnapshot: EnforcementHealthSnapshot = EnforcementHealthSnapshot(),
  val usageAccessSnapshot: UsageAccessSnapshot = UsageAccessSnapshot(),
  val vpnFilterSnapshot: VpnFilterSnapshot = VpnFilterSnapshot(),
  val datasets: List<FilterDatasetMetadata> = DatasetSeedDefaults.defaultMetadata(),
  val errorMessage: String? = null,
)

sealed interface SettingsUiState {
  data object Loading : SettingsUiState

  data class Ready(val summary: SettingsSummary) : SettingsUiState
}

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
  private val userPreferencesRepository: UserPreferencesRepository,
  private val enforcementHealthMonitor: EnforcementHealthMonitor,
  private val usageAccessMonitor: UsageAccessMonitor,
  private val vpnFilterMonitor: VpnFilterMonitor,
  private val domainFilterRepository: DomainFilterRepository,
  private val commitmentRepository: CommitmentRepository,
  private val usageStatsReconciler: UsageStatsReconciler,
) : ViewModel() {

  private val _errorMessage = MutableStateFlow<String?>(null)

  init {
    viewModelScope.launch {
      runCatching { domainFilterRepository.ensureDefaultDatasetsInitialized() }
    }
  }

  val uiState: StateFlow<SettingsUiState> =
    combine(
        combine(
          userPreferencesRepository.preferences,
          enforcementHealthMonitor.healthState,
          usageAccessMonitor.usageAccessState,
        ) { prefs, health, usageAccess ->
          Triple(prefs, health, usageAccess)
        },
        combine(
          vpnFilterMonitor.vpnState,
          domainFilterRepository.observeDatasets(),
          _errorMessage,
        ) { vpn, datasetsResult, error ->
          val datasets =
            when (datasetsResult) {
              is PersistenceResult.Success ->
                if (datasetsResult.data.isEmpty()) DatasetSeedDefaults.defaultMetadata()
                else datasetsResult.data
              is PersistenceResult.Failure -> DatasetSeedDefaults.defaultMetadata()
            }
          Triple(vpn, datasets, error)
        },
      ) { (prefs, health, usageAccess), (vpn, datasets, error) ->
        SettingsUiState.Ready(
          SettingsSummary(
            preferences = prefs,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            healthSnapshot = health,
            usageAccessSnapshot = usageAccess,
            vpnFilterSnapshot = vpn,
            datasets = datasets,
            errorMessage = error,
          )
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState.Loading,
      )

  fun refreshEnforcementHealth() {
    enforcementHealthMonitor.refreshSystemStatus()
    usageAccessMonitor.refreshSystemStatus()
    vpnFilterMonitor.refreshSystemStatus()
    viewModelScope.launch {
      val activeRes = runCatching { commitmentRepository.observeActiveCommitment().first() }.getOrNull()
      val active = (activeRes as? PersistenceResult.Success)?.data as? Commitment.Active ?: return@launch
      runCatching { usageStatsReconciler.reconcileActiveCommitment(active) }
    }
  }

  fun createAccessibilitySettingsIntent(): Intent =
    enforcementHealthMonitor.createAccessibilitySettingsIntent()

  fun createUsageAccessSettingsIntent(): Intent =
    usageAccessMonitor.createUsageAccessSettingsIntent()

  fun prepareVpnIntent(): Intent? = vpnFilterMonitor.prepareVpnIntent()

  fun setWebsiteFilteringConsent(consented: Boolean) {
    _errorMessage.value = null
    viewModelScope.launch {
      val result = userPreferencesRepository.setWebsiteFilteringConsented(consented)
      if (result is PersistenceResult.Failure) {
        _errorMessage.value = result.error.toDisplayMessage()
      }
    }
  }

  fun onVpnPermissionResult(granted: Boolean) {
    viewModelScope.launch {
      if (granted) {
        vpnFilterMonitor.refreshSystemStatus()
        vpnFilterMonitor.requestStartFiltering()
      } else {
        vpnFilterMonitor.markPermissionDenied()
      }
    }
  }

  fun requestStartVpnFiltering() {
    viewModelScope.launch {
      val outcome = vpnFilterMonitor.requestStartFiltering()
      if (outcome == VpnStartRequestOutcome.PERMISSION_REQUIRED) {
        vpnFilterMonitor.refreshSystemStatus()
      }
    }
  }

  fun requestStopVpnFiltering() {
    viewModelScope.launch { vpnFilterMonitor.requestStopFiltering(markStoppedByUser = true) }
  }

  fun setThemePreference(themePreference: ThemePreference) {
    _errorMessage.value = null
    viewModelScope.launch {
      val result = userPreferencesRepository.setThemePreference(themePreference)
      if (result is PersistenceResult.Failure) {
        _errorMessage.value = result.error.toDisplayMessage()
      }
    }
  }

  fun setWifiOnlyFilterUpdates(wifiOnly: Boolean) {
    _errorMessage.value = null
    viewModelScope.launch {
      val result = userPreferencesRepository.setWifiOnlyFilterUpdates(wifiOnly)
      if (result is PersistenceResult.Failure) {
        _errorMessage.value = result.error.toDisplayMessage()
      }
    }
  }
}
