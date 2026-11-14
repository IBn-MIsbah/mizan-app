package com.example.platform.health

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.example.domain.time.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UsageAccessPermissionStatus {
  ENABLED,
  DISABLED,
  UNAVAILABLE,
}

data class UsageAccessSnapshot(
  val permissionStatus: UsageAccessPermissionStatus = UsageAccessPermissionStatus.DISABLED,
  val lastCheckedEpochMillis: Long? = null,
)

interface UsageAccessMonitor {
  val usageAccessState: StateFlow<UsageAccessSnapshot>

  fun refreshSystemStatus()

  fun createUsageAccessSettingsIntent(): Intent
}

@Singleton
class AndroidUsageAccessMonitor
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val timeProvider: TimeProvider,
) : UsageAccessMonitor {

  private val _usageAccessState =
    MutableStateFlow(
      UsageAccessSnapshot(
        permissionStatus = queryPermissionStatus(),
        lastCheckedEpochMillis = timeProvider.currentWallClock().epochMillis,
      )
    )

  override val usageAccessState: StateFlow<UsageAccessSnapshot> = _usageAccessState.asStateFlow()

  override fun refreshSystemStatus() {
    _usageAccessState.value =
      UsageAccessSnapshot(
        permissionStatus = queryPermissionStatus(),
        lastCheckedEpochMillis = timeProvider.currentWallClock().epochMillis,
      )
  }

  override fun createUsageAccessSettingsIntent(): Intent =
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

  private fun queryPermissionStatus(): UsageAccessPermissionStatus {
    return try {
      val appOps =
        context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
          ?: return UsageAccessPermissionStatus.UNAVAILABLE

      val mode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
          )
        } else {
          @Suppress("DEPRECATION")
          appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
          )
        }

      when (mode) {
        AppOpsManager.MODE_ALLOWED -> UsageAccessPermissionStatus.ENABLED
        AppOpsManager.MODE_DEFAULT -> {
          val granted =
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
              PackageManager.PERMISSION_GRANTED
          if (granted) UsageAccessPermissionStatus.ENABLED else UsageAccessPermissionStatus.DISABLED
        }
        AppOpsManager.MODE_IGNORED,
        AppOpsManager.MODE_ERRORED -> UsageAccessPermissionStatus.DISABLED
        else -> UsageAccessPermissionStatus.UNAVAILABLE
      }
    } catch (_: SecurityException) {
      UsageAccessPermissionStatus.UNAVAILABLE
    } catch (_: RuntimeException) {
      UsageAccessPermissionStatus.UNAVAILABLE
    }
  }
}
