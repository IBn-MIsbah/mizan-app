package com.example.platform.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.core.common.DispatcherProvider
import com.example.core.common.IdGenerator
import com.example.domain.model.Commitment
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.repository.UserPreferencesRepository
import com.example.domain.time.TimeProvider
import com.example.platform.health.BatteryOptimizationMonitor
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.health.UsageAccessMonitor
import com.example.platform.usage.ForegroundUsageTracker
import com.example.platform.vpn.VpnFilterMonitor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class BootRecoveryCoordinator
@Inject
constructor(
  private val commitmentRepository: CommitmentRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val usageTracker: ForegroundUsageTracker,
  private val enforcementHealthMonitor: EnforcementHealthMonitor,
  private val usageAccessMonitor: UsageAccessMonitor,
  private val batteryOptimizationMonitor: BatteryOptimizationMonitor,
  private val vpnFilterMonitor: VpnFilterMonitor,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
) {

  suspend fun handleRecovery(action: String?) {
    val eventType =
      when (action) {
        Intent.ACTION_BOOT_COMPLETED -> SecurityEventType.DEVICE_REBOOT_RECOVERED
        Intent.ACTION_MY_PACKAGE_REPLACED -> SecurityEventType.APP_UPDATED_RECOVERED
        else -> return
      }

    // Reconcile any interrupted pre-reboot session and update integrity checkpoint conservatively.
    runCatching { usageTracker.recoverUnfinishedSessionIfNeeded() }

    enforcementHealthMonitor.refreshSystemStatus()
    usageAccessMonitor.refreshSystemStatus()
    batteryOptimizationMonitor.refreshSystemStatus()
    vpnFilterMonitor.refreshSystemStatus()

    val activeResult = runCatching { commitmentRepository.observeActiveCommitment().first() }.getOrNull()
    val active = (activeResult as? PersistenceResult.Success)?.data as? Commitment.Active ?: return

    val now = timeProvider.currentWallClock()
    val summary =
      if (eventType == SecurityEventType.DEVICE_REBOOT_RECOVERED) {
        "Device rebooted; local commitment and usage checkpoints recovered conservatively"
      } else {
        "Application updated; local commitment and service health state refreshed"
      }

    runCatching {
      securityEventRepository.recordEvent(
        SecurityEvent(
          id = SecurityEventId(idGenerator.nextId()),
          commitmentId = active.id,
          eventType = eventType,
          occurredAt = now,
          summary = summary,
        )
      )
    }

    if (active.rules.hasConfiguredWebsiteFiltering()) {
      val prefs = runCatching { userPreferencesRepository.preferences.first() }.getOrNull()
      val canAttemptStart =
        prefs != null &&
          prefs.websiteFilteringConsented &&
          !prefs.vpnStoppedByUser &&
          vpnFilterMonitor.prepareVpnIntent() == null
      if (canAttemptStart) {
        runCatching { vpnFilterMonitor.requestStartFiltering() }
          .onFailure {
            vpnFilterMonitor.onTunnelError(
              "Android blocked background VPN restart after reboot; open MIZAN to resume DNS filtering."
            )
          }
      }
    }
  }
}

@AndroidEntryPoint
class BootAndUpdateRecoveryReceiver : BroadcastReceiver() {

  @Inject lateinit var bootRecoveryCoordinator: BootRecoveryCoordinator
  @Inject lateinit var dispatcherProvider: DispatcherProvider

  override fun onReceive(context: Context?, intent: Intent?) {
    val action = intent?.action ?: return
    if (
      action != Intent.ACTION_BOOT_COMPLETED &&
        action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      return
    }
    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + dispatcherProvider.io).launch {
      try {
        bootRecoveryCoordinator.handleRecovery(action)
      } finally {
        pendingResult.finish()
      }
    }
  }
}
