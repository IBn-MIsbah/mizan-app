package com.example.platform.usage

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface DeviceInteractiveStateProvider {
  fun isInteractiveAndUnlocked(): Boolean
}

@Singleton
class AndroidDeviceInteractiveStateProvider
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
) : DeviceInteractiveStateProvider {

  override fun isInteractiveAndUnlocked(): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val isInteractive = powerManager?.isInteractive ?: true
    val isLocked = keyguardManager?.isKeyguardLocked ?: false
    return isInteractive && !isLocked
  }
}
