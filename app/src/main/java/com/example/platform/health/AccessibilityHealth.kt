package com.example.platform.health

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.example.domain.time.TimeProvider
import com.example.platform.accessibility.AppRestrictionAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AccessibilityPermissionStatus {
  ENABLED,
  DISABLED,
  UNAVAILABLE,
}

enum class EnforcementHealthStatus {
  ACTIVE,
  ENABLED_SERVICE_DISCONNECTED,
  DISABLED,
  UNAVAILABLE,
}

data class EnforcementHealthSnapshot(
  val permissionStatus: AccessibilityPermissionStatus = AccessibilityPermissionStatus.DISABLED,
  val isServiceConnected: Boolean = false,
  val ruleStateAvailable: Boolean = true,
  val healthStatus: EnforcementHealthStatus = EnforcementHealthStatus.DISABLED,
  val lastUpdatedEpochMillis: Long? = null,
)

interface EnforcementHealthMonitor {
  val healthState: StateFlow<EnforcementHealthSnapshot>

  fun refreshSystemStatus()

  fun setServiceConnected(connected: Boolean)

  fun setRuleStateAvailable(available: Boolean)

  fun createAccessibilitySettingsIntent(): Intent
}

@Singleton
class AndroidEnforcementHealthMonitor
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val timeProvider: TimeProvider,
) : EnforcementHealthMonitor {

  private val serviceComponent: ComponentName by lazy {
    ComponentName(context.packageName, AppRestrictionAccessibilityService::class.java.name)
  }

  private val _healthState =
    MutableStateFlow(
      computeSnapshot(
        permissionStatus = queryPermissionStatus(),
        isServiceConnected = false,
        ruleStateAvailable = true,
        updatedAt = timeProvider.currentWallClock().epochMillis,
      )
    )

  override val healthState: StateFlow<EnforcementHealthSnapshot> = _healthState.asStateFlow()

  override fun refreshSystemStatus() {
    val permissionStatus = queryPermissionStatus()
    val nowMillis = timeProvider.currentWallClock().epochMillis
    _healthState.update { current ->
      val connected =
        if (permissionStatus != AccessibilityPermissionStatus.ENABLED) false
        else current.isServiceConnected
      computeSnapshot(
        permissionStatus = permissionStatus,
        isServiceConnected = connected,
        ruleStateAvailable = current.ruleStateAvailable,
        updatedAt = nowMillis,
      )
    }
  }

  override fun setServiceConnected(connected: Boolean) {
    val permissionStatus =
      if (connected) AccessibilityPermissionStatus.ENABLED else queryPermissionStatus()
    val nowMillis = timeProvider.currentWallClock().epochMillis
    _healthState.update { current ->
      computeSnapshot(
        permissionStatus = permissionStatus,
        isServiceConnected = connected,
        ruleStateAvailable = current.ruleStateAvailable,
        updatedAt = nowMillis,
      )
    }
  }

  override fun setRuleStateAvailable(available: Boolean) {
    val nowMillis = timeProvider.currentWallClock().epochMillis
    _healthState.update { current ->
      computeSnapshot(
        permissionStatus = current.permissionStatus,
        isServiceConnected = current.isServiceConnected,
        ruleStateAvailable = available,
        updatedAt = nowMillis,
      )
    }
  }

  override fun createAccessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

  private fun queryPermissionStatus(): AccessibilityPermissionStatus {
    return try {
      val manager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
          ?: return AccessibilityPermissionStatus.UNAVAILABLE

      val enabledByManager =
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
          info ->
          val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
          ComponentName(serviceInfo.packageName, serviceInfo.name) == serviceComponent
        }
      if (enabledByManager) {
        return AccessibilityPermissionStatus.ENABLED
      }

      val enabledServices =
        Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
      if (!enabledServices.isNullOrBlank()) {
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
          val parsed = ComponentName.unflattenFromString(splitter.next())
          if (parsed == serviceComponent) {
            return AccessibilityPermissionStatus.ENABLED
          }
        }
      }

      AccessibilityPermissionStatus.DISABLED
    } catch (_: SecurityException) {
      AccessibilityPermissionStatus.UNAVAILABLE
    } catch (_: RuntimeException) {
      AccessibilityPermissionStatus.UNAVAILABLE
    }
  }

  companion object {
    fun computeSnapshot(
      permissionStatus: AccessibilityPermissionStatus,
      isServiceConnected: Boolean,
      ruleStateAvailable: Boolean,
      updatedAt: Long?,
    ): EnforcementHealthSnapshot {
      val overall =
        when {
          permissionStatus == AccessibilityPermissionStatus.UNAVAILABLE ->
            EnforcementHealthStatus.UNAVAILABLE
          permissionStatus == AccessibilityPermissionStatus.DISABLED ->
            EnforcementHealthStatus.DISABLED
          !isServiceConnected -> EnforcementHealthStatus.ENABLED_SERVICE_DISCONNECTED
          !ruleStateAvailable -> EnforcementHealthStatus.UNAVAILABLE
          else -> EnforcementHealthStatus.ACTIVE
        }
      return EnforcementHealthSnapshot(
        permissionStatus = permissionStatus,
        isServiceConnected = isServiceConnected,
        ruleStateAvailable = ruleStateAvailable,
        healthStatus = overall,
        lastUpdatedEpochMillis = updatedAt,
      )
    }
  }
}
