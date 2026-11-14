package com.example.platform.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.example.domain.time.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BatteryOptimizationStatus {
  UNRESTRICTED,
  OPTIMIZED,
  UNAVAILABLE,
}

data class BatteryOptimizationSnapshot(
  val status: BatteryOptimizationStatus = BatteryOptimizationStatus.UNAVAILABLE,
  val lastCheckedEpochMillis: Long? = null,
)

interface BatteryOptimizationMonitor {
  val batteryState: StateFlow<BatteryOptimizationSnapshot>

  fun refreshSystemStatus()

  fun createBatteryOptimizationSettingsIntent(): Intent

  fun createAppDetailsSettingsIntent(): Intent
}

/**
 * Play-compliant read-only monitor for Android battery optimization exemptions.
 * Does NOT declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; directs users to system settings.
 */
@Singleton
class AndroidBatteryOptimizationMonitor
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val timeProvider: TimeProvider,
) : BatteryOptimizationMonitor {

  private val _batteryState = MutableStateFlow(querySnapshot())
  override val batteryState: StateFlow<BatteryOptimizationSnapshot> = _batteryState.asStateFlow()

  override fun refreshSystemStatus() {
    _batteryState.value = querySnapshot()
  }

  override fun createBatteryOptimizationSettingsIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

  override fun createAppDetailsSettingsIntent(): Intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", context.packageName, null),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

  private fun querySnapshot(): BatteryOptimizationSnapshot {
    val status =
      try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
          BatteryOptimizationStatus.UNAVAILABLE
        } else if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
          BatteryOptimizationStatus.UNRESTRICTED
        } else {
          BatteryOptimizationStatus.OPTIMIZED
        }
      } catch (_: RuntimeException) {
        BatteryOptimizationStatus.UNAVAILABLE
      }

    return BatteryOptimizationSnapshot(
      status = status,
      lastCheckedEpochMillis = timeProvider.currentWallClock().epochMillis,
    )
  }
}
