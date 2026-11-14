package com.example.platform.usage

import android.content.Context
import android.os.Process
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface BootSessionProvider {
  fun currentBootSessionToken(): String
}

@Singleton
class AndroidBootSessionProvider
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
) : BootSessionProvider {

  override fun currentBootSessionToken(): String {
    val bootCount =
      runCatching {
          Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }
        .getOrDefault(-1)
    return if (bootCount >= 0) {
      "boot:$bootCount"
    } else {
      "proc:${Process.getStartElapsedRealtime()}"
    }
  }
}
