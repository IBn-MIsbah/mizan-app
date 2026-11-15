package com.example.platform.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import com.example.core.common.DispatcherProvider
import com.example.domain.model.Commitment
import com.example.domain.model.DomainCategory
import com.example.domain.model.FilterDatasetMetadata
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.DomainFilterRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VpnServiceLifecycleStatus {
  NOT_CONFIGURED,
  PERMISSION_REQUIRED,
  PERMISSION_DENIED,
  READY_INACTIVE,
  STARTING,
  ACTIVE,
  STOPPED_BY_USER,
  REVOKED,
  ANOTHER_VPN_ACTIVE,
  DEGRADED_ERROR,
}

data class VpnFilterSnapshot(
  val status: VpnServiceLifecycleStatus = VpnServiceLifecycleStatus.NOT_CONFIGURED,
  val hasActiveWebsiteCommitment: Boolean = false,
  val hasConsented: Boolean = false,
  val isPermissionPrepared: Boolean = false,
  val isTunnelEstablished: Boolean = false,
  val stoppedByUser: Boolean = false,
  val permissionDenied: Boolean = false,
  val wasRevoked: Boolean = false,
  val anotherVpnActive: Boolean = false,
  val isPrivateDnsActive: Boolean = false,
  val upstreamDnsCount: Int = 0,
  val hasMissingOrIncompleteDataset: Boolean = false,
  val datasetWarningMessage: String? = null,
  val lastError: String? = null,
  val lastUpdatedEpochMillis: Long? = null,
  val forwardedQueryCount: Long = 0L,
  val blockedQueryCount: Long = 0L,
  val upstreamFailureCount: Long = 0L,
)

enum class VpnStartRequestOutcome {
  STARTED,
  NOT_REQUIRED,
  CONSENT_REQUIRED,
  PERMISSION_REQUIRED,
  CONFLICT_WITH_OTHER_VPN,
}

interface VpnFilterMonitor {
  val vpnState: StateFlow<VpnFilterSnapshot>

  fun prepareVpnIntent(): Intent?

  fun refreshSystemStatus()

  suspend fun requestStartFiltering(): VpnStartRequestOutcome

  suspend fun requestStopFiltering(markStoppedByUser: Boolean = true)

  suspend fun onCommitmentEnded()

  fun markPermissionDenied()

  fun onServiceStarting()

  fun onTunnelEstablished(upstreamDnsCount: Int, isPrivateDnsActive: Boolean)

  fun onTunnelRevoked(anotherVpnActive: Boolean)

  fun onTunnelStopped(stoppedByUser: Boolean)

  fun onTunnelError(message: String)

  fun onDnsStatsUpdated(
    forwardedCount: Long,
    blockedCount: Long,
    upstreamFailureCount: Long,
  ) {}
}

@Singleton
class AndroidVpnFilterMonitor
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val commitmentRepository: CommitmentRepository,
  private val domainFilterRepository: DomainFilterRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val timeProvider: TimeProvider,
  dispatcherProvider: DispatcherProvider,
) : VpnFilterMonitor {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)

  private data class InternalRuntimeSignals(
    val isStarting: Boolean = false,
    val isTunnelEstablished: Boolean = false,
    val permissionDenied: Boolean = false,
    val wasRevoked: Boolean = false,
    val anotherVpnActive: Boolean = false,
    val isPrivateDnsActive: Boolean = false,
    val upstreamDnsCount: Int = 0,
    val lastError: String? = null,
    val isPermissionPrepared: Boolean = false,
    val refreshTick: Int = 0,
    val forwardedQueryCount: Long = 0L,
    val blockedQueryCount: Long = 0L,
    val upstreamFailureCount: Long = 0L,
  )

  private data class ObservedRepoState(
    val activeCommitment: Commitment.Active? = null,
    val consented: Boolean = false,
    val stoppedByUser: Boolean = false,
    val datasets: List<FilterDatasetMetadata> = emptyList(),
  )

  private val runtimeSignals = MutableStateFlow(readSystemSignals(InternalRuntimeSignals()))
  private val _vpnState = MutableStateFlow(VpnFilterSnapshot())
  override val vpnState: StateFlow<VpnFilterSnapshot> = _vpnState.asStateFlow()

  init {
    scope.launch {
      val repoFlow =
        combine(
          commitmentRepository.observeActiveCommitment(),
          userPreferencesRepository.preferences,
          domainFilterRepository.observeDatasets(),
        ) { activeResult, prefs, datasetsResult ->
          val active =
            (activeResult as? PersistenceResult.Success)?.data as? Commitment.Active
          val datasets =
            (datasetsResult as? PersistenceResult.Success)?.data.orEmpty()
          ObservedRepoState(
            activeCommitment = active,
            consented = prefs.websiteFilteringConsented,
            stoppedByUser = prefs.vpnStoppedByUser,
            datasets = datasets,
          )
        }

      combine(repoFlow, runtimeSignals) { repo, signals ->
          computeSnapshot(
            activeCommitment = repo.activeCommitment,
            hasConsented = repo.consented,
            stoppedByUser = repo.stoppedByUser,
            datasets = repo.datasets,
            isPermissionPrepared = signals.isPermissionPrepared,
            isStarting = signals.isStarting,
            isTunnelEstablished = signals.isTunnelEstablished,
            permissionDenied = signals.permissionDenied,
            wasRevoked = signals.wasRevoked,
            anotherVpnActive = signals.anotherVpnActive,
            isPrivateDnsActive = signals.isPrivateDnsActive,
            upstreamDnsCount = signals.upstreamDnsCount,
            lastError = signals.lastError,
            nowEpochMillis = timeProvider.currentWallClock().epochMillis,
            forwardedQueryCount = signals.forwardedQueryCount,
            blockedQueryCount = signals.blockedQueryCount,
            upstreamFailureCount = signals.upstreamFailureCount,
          )
        }
        .collect { snapshot -> _vpnState.value = snapshot }
    }
  }

  override fun prepareVpnIntent(): Intent? =
    try {
      VpnService.prepare(context)
    } catch (_: RuntimeException) {
      null
    }

  override fun refreshSystemStatus() {
    runtimeSignals.update { current ->
      val updated = readSystemSignals(current)
      updated.copy(refreshTick = current.refreshTick + 1)
    }
  }

  override suspend fun requestStartFiltering(): VpnStartRequestOutcome {
    val active =
      (_vpnState.value.hasActiveWebsiteCommitment).takeIf { it }
        ?: run {
          refreshSystemStatus()
          return VpnStartRequestOutcome.NOT_REQUIRED
        }
    if (!active) {
      return VpnStartRequestOutcome.NOT_REQUIRED
    }

    val currentSnapshot = _vpnState.value
    if (!currentSnapshot.hasConsented) {
      return VpnStartRequestOutcome.CONSENT_REQUIRED
    }
    if (prepareVpnIntent() != null) {
      refreshSystemStatus()
      return VpnStartRequestOutcome.PERMISSION_REQUIRED
    }

    val sys = readSystemSignals(runtimeSignals.value)
    if (sys.anotherVpnActive && !sys.isTunnelEstablished) {
      runtimeSignals.value = sys.copy(isStarting = false, lastError = null)
      return VpnStartRequestOutcome.CONFLICT_WITH_OTHER_VPN
    }

    userPreferencesRepository.setVpnStoppedByUser(false)
    runtimeSignals.update {
      it.copy(
        isPermissionPrepared = true,
        permissionDenied = false,
        wasRevoked = false,
        anotherVpnActive = false,
        lastError = null,
        isStarting = !it.isTunnelEstablished,
        refreshTick = it.refreshTick + 1,
      )
    }

    DomainFilterVpnService.start(context)
    return VpnStartRequestOutcome.STARTED
  }

  override suspend fun requestStopFiltering(markStoppedByUser: Boolean) {
    userPreferencesRepository.setVpnStoppedByUser(markStoppedByUser)
    runtimeSignals.update {
      it.copy(
        isStarting = false,
        isTunnelEstablished = false,
        lastError = null,
        refreshTick = it.refreshTick + 1,
      )
    }
    DomainFilterVpnService.stop(context)
  }

  override suspend fun onCommitmentEnded() {
    userPreferencesRepository.setVpnStoppedByUser(false)
    runtimeSignals.update {
      it.copy(
        isStarting = false,
        isTunnelEstablished = false,
        wasRevoked = false,
        lastError = null,
        refreshTick = it.refreshTick + 1,
      )
    }
    DomainFilterVpnService.stop(context)
  }

  override fun markPermissionDenied() {
    runtimeSignals.update {
      it.copy(
        isPermissionPrepared = false,
        isStarting = false,
        isTunnelEstablished = false,
        permissionDenied = true,
        wasRevoked = false,
        lastError = null,
        refreshTick = it.refreshTick + 1,
      )
    }
  }

  override fun onServiceStarting() {
    runtimeSignals.update {
      it.copy(
        isStarting = true,
        wasRevoked = false,
        permissionDenied = false,
        lastError = null,
        forwardedQueryCount = 0L,
        blockedQueryCount = 0L,
        upstreamFailureCount = 0L,
        refreshTick = it.refreshTick + 1,
      )
    }
  }

  override fun onTunnelEstablished(upstreamDnsCount: Int, isPrivateDnsActive: Boolean) {
    runtimeSignals.update {
      it.copy(
        isPermissionPrepared = true,
        isStarting = false,
        isTunnelEstablished = true,
        permissionDenied = false,
        wasRevoked = false,
        anotherVpnActive = false,
        isPrivateDnsActive = isPrivateDnsActive,
        upstreamDnsCount = upstreamDnsCount,
        lastError = null,
        refreshTick = it.refreshTick + 1,
      )
    }
  }

  override fun onTunnelRevoked(anotherVpnActive: Boolean) {
    val refreshed = readSystemSignals(runtimeSignals.value)
    runtimeSignals.value =
      refreshed.copy(
        isStarting = false,
        isTunnelEstablished = false,
        wasRevoked = !anotherVpnActive,
        anotherVpnActive = anotherVpnActive || refreshed.anotherVpnActive,
        lastError = null,
        forwardedQueryCount = 0L,
        blockedQueryCount = 0L,
        upstreamFailureCount = 0L,
        refreshTick = refreshed.refreshTick + 1,
      )
  }

  override fun onTunnelStopped(stoppedByUser: Boolean) {
    val refreshed = readSystemSignals(runtimeSignals.value)
    runtimeSignals.value =
      refreshed.copy(
        isStarting = false,
        isTunnelEstablished = false,
        lastError = null,
        forwardedQueryCount = 0L,
        blockedQueryCount = 0L,
        upstreamFailureCount = 0L,
        refreshTick = refreshed.refreshTick + 1,
      )
  }

  override fun onTunnelError(message: String) {
    val refreshed = readSystemSignals(runtimeSignals.value)
    runtimeSignals.value =
      refreshed.copy(
        isStarting = false,
        isTunnelEstablished = false,
        lastError = message,
        forwardedQueryCount = 0L,
        blockedQueryCount = 0L,
        upstreamFailureCount = 0L,
        refreshTick = refreshed.refreshTick + 1,
      )
  }

  override fun onDnsStatsUpdated(
    forwardedCount: Long,
    blockedCount: Long,
    upstreamFailureCount: Long,
  ) {
    runtimeSignals.update {
      it.copy(
        forwardedQueryCount = forwardedCount,
        blockedQueryCount = blockedCount,
        upstreamFailureCount = upstreamFailureCount,
      )
    }
  }

  private fun readSystemSignals(current: InternalRuntimeSignals): InternalRuntimeSignals {
    val prepared =
      try {
        VpnService.prepare(context) == null
      } catch (_: RuntimeException) {
        false
      }

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    var anotherVpn = false
    val rankedDnsConfigs = UnderlyingDnsResolver.discoverNetworkDnsConfigs(context)
    val privateDns = rankedDnsConfigs.any { it.isPrivateDnsActive }
    val dnsCount = rankedDnsConfigs.firstOrNull()?.dnsServers?.size ?: 0

    if (cm != null) {
      val networks =
        try {
          @Suppress("DEPRECATION")
          cm.allNetworks
        } catch (_: RuntimeException) {
          emptyArray()
        }

      var hasAnyVpnTransport = false
      for (network in networks) {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
          hasAnyVpnTransport = true
          break
        }
      }

      if (hasAnyVpnTransport && !current.isTunnelEstablished && !current.isStarting) {
        anotherVpn = true
      }
    }

    return current.copy(
      isPermissionPrepared = prepared,
      permissionDenied = if (prepared) false else current.permissionDenied,
      anotherVpnActive = anotherVpn,
      isPrivateDnsActive = privateDns,
      upstreamDnsCount = dnsCount,
    )
  }

  companion object {
    fun computeSnapshot(
      activeCommitment: Commitment.Active?,
      hasConsented: Boolean,
      stoppedByUser: Boolean,
      datasets: List<FilterDatasetMetadata>,
      isPermissionPrepared: Boolean,
      isStarting: Boolean,
      isTunnelEstablished: Boolean,
      permissionDenied: Boolean,
      wasRevoked: Boolean,
      anotherVpnActive: Boolean,
      isPrivateDnsActive: Boolean,
      upstreamDnsCount: Int,
      lastError: String?,
      nowEpochMillis: Long?,
    forwardedQueryCount: Long = 0L,
    blockedQueryCount: Long = 0L,
    upstreamFailureCount: Long = 0L,
  ): VpnFilterSnapshot {
      val hasWebsiteRules =
        activeCommitment != null && activeCommitment.rules.hasConfiguredWebsiteFiltering()

      val selectedCategories =
        activeCommitment?.rules?.blockedDomainCategories.orEmpty() - DomainCategory.CUSTOM
      val missingCategoryNames =
        selectedCategories.mapNotNull { category ->
          val meta = datasets.firstOrNull { it.category == category }
          if (meta == null || !meta.isUsable) category.name.replace('_', ' ').lowercase() else null
        }

      val hasMissingDataset = hasWebsiteRules && missingCategoryNames.isNotEmpty()
      val datasetWarning =
        if (hasMissingDataset) {
          "Selected category dataset(s) (${missingCategoryNames.joinToString(", ")}) are empty or missing. Custom domains still filter locally, but category lists cannot block domains until a verified dataset is imported."
        } else {
          null
        }

      val status =
        when {
          !hasWebsiteRules -> VpnServiceLifecycleStatus.NOT_CONFIGURED
          lastError != null -> VpnServiceLifecycleStatus.DEGRADED_ERROR
          isTunnelEstablished -> VpnServiceLifecycleStatus.ACTIVE
          anotherVpnActive -> VpnServiceLifecycleStatus.ANOTHER_VPN_ACTIVE
          wasRevoked -> VpnServiceLifecycleStatus.REVOKED
          permissionDenied -> VpnServiceLifecycleStatus.PERMISSION_DENIED
          !isPermissionPrepared -> VpnServiceLifecycleStatus.PERMISSION_REQUIRED
          stoppedByUser -> VpnServiceLifecycleStatus.STOPPED_BY_USER
          isStarting -> VpnServiceLifecycleStatus.STARTING
          else -> VpnServiceLifecycleStatus.READY_INACTIVE
        }

      return VpnFilterSnapshot(
        status = status,
        hasActiveWebsiteCommitment = hasWebsiteRules,
        hasConsented = hasConsented,
        isPermissionPrepared = isPermissionPrepared,
        isTunnelEstablished = isTunnelEstablished,
        stoppedByUser = stoppedByUser,
        permissionDenied = permissionDenied,
        wasRevoked = wasRevoked,
        anotherVpnActive = anotherVpnActive,
        isPrivateDnsActive = isPrivateDnsActive,
        upstreamDnsCount = upstreamDnsCount,
        hasMissingOrIncompleteDataset = hasMissingDataset,
        datasetWarningMessage = datasetWarning,
        lastError = lastError,
        lastUpdatedEpochMillis = nowEpochMillis,
        forwardedQueryCount = forwardedQueryCount,
        blockedQueryCount = blockedQueryCount,
        upstreamFailureCount = upstreamFailureCount,
      )
    }
  }
}
