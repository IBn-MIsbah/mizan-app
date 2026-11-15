package com.example.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.example.core.common.DispatcherProvider
import com.example.platform.enforcement.AppRestrictionCoordinator
import com.example.platform.usage.SessionEndReason
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Narrow AccessibilityService adapter for foreground window package detection and optional
 * in-memory YouTube Shorts player detection.
 *
 * Privacy and security guarantees:
 * - Observes `typeWindowStateChanged` for foreground app restrictions and
 *   `typeWindowContentChanged` strictly for detecting the active YouTube Shorts player when the
 *   user explicitly enables YouTube Shorts restriction in an active commitment.
 * - Never inspects window content when YouTube Shorts restriction is disabled or inactive, or for
 *   any package other than `com.google.android.youtube`.
 * - Never stores, persists, logs, or uploads window text, video titles, search queries,
 *   comments, watch history, or screenshots.
 * - Observes `ACTION_SCREEN_OFF` and `ACTION_SHUTDOWN` dynamically only while connected so active
 *   usage intervals close accurately when the screen turns off.
 */
@AndroidEntryPoint
class AppRestrictionAccessibilityService : AccessibilityService() {

  @Inject lateinit var coordinator: AppRestrictionCoordinator

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  private var serviceScope: CoroutineScope? = null
  private var screenReceiverRegistered: Boolean = false

  private val screenStateReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        val reason =
          when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> SessionEndReason.SCREEN_OFF
            Intent.ACTION_SHUTDOWN -> SessionEndReason.SERVICE_DISCONNECTED
            else -> return
          }
        if (::coordinator.isInitialized) {
          serviceScope?.launch { coordinator.onDeviceInteractiveEnded(reason) }
        }
      }
    }

  override fun onServiceConnected() {
    super.onServiceConnected()
    if (!::coordinator.isInitialized || !::dispatcherProvider.isInitialized) {
      return
    }
    serviceScope?.cancel()
    serviceScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    registerScreenReceiverSafely()
    coordinator.onServiceConnected()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val safeEvent = event ?: return
    if (!::coordinator.isInitialized || !::dispatcherProvider.isInitialized) {
      return
    }
    val eventType = safeEvent.eventType
    if (
      eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    ) {
      return
    }

    val eventPackage = safeEvent.packageName?.toString() ?: return
    val activeScope =
      serviceScope
        ?: CoroutineScope(SupervisorJob() + dispatcherProvider.default).also {
          serviceScope = it
        }

    if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      activeScope.launch { coordinator.onForegroundPackageObserved(eventPackage) }
    }

    if (
      eventPackage == YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME &&
        coordinator.shouldInspectYouTubeShortsNow()
    ) {
      val snapshot =
        AccessibilityTreeSnapshotExtractor.capture(
          root = rootInActiveWindow,
          expectedPackageName = YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME,
        )
      activeScope.launch {
        val shouldNavigateBack =
          coordinator.onYouTubeWindowObserved(
            rawPackageName = YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME,
            snapshot = snapshot,
          )
        if (shouldNavigateBack) {
          withContext(dispatcherProvider.main) {
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
          }
        }
      }
    }
  }

  override fun onInterrupt() {
    if (::coordinator.isInitialized) {
      coordinator.onServiceInterrupted()
    }
  }

  override fun onUnbind(intent: Intent?): Boolean {
    tearDownServiceWork()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    tearDownServiceWork()
    super.onDestroy()
  }

  private fun registerScreenReceiverSafely() {
    if (screenReceiverRegistered) return
    val filter =
      IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SHUTDOWN)
      }
    runCatching {
      registerReceiver(screenStateReceiver, filter)
      screenReceiverRegistered = true
    }
  }

  private fun tearDownServiceWork() {
    if (screenReceiverRegistered) {
      runCatching { unregisterReceiver(screenStateReceiver) }
      screenReceiverRegistered = false
    }
    if (::coordinator.isInitialized) {
      coordinator.onServiceDisconnectedOrDestroyed()
    }
    serviceScope?.cancel()
    serviceScope = null
  }
}
