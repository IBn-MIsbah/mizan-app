package com.example.core.designsystem.component

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Propagates reduced-motion preference (either user toggle or OS Animator Duration Scale = 0)
 * across all instructional animations and transitions.
 */
val LocalMizanReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberSystemReducedMotion(): Boolean {
  val context = LocalContext.current
  return remember(context) {
    runCatching {
      val scale =
        Settings.Global.getFloat(
          context.contentResolver,
          Settings.Global.ANIMATOR_DURATION_SCALE,
          1f,
        )
      scale <= 0f
    }
      .getOrDefault(false)
  }
}

@Composable
fun ProvideMizanMotionPreferences(
  userReduceMotion: Boolean,
  content: @Composable () -> Unit,
) {
  val systemReduce = rememberSystemReducedMotion()
  val effectiveReduce = userReduceMotion || systemReduce
  CompositionLocalProvider(LocalMizanReducedMotion provides effectiveReduce, content = content)
}
