package com.example.feature.blocking

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.domain.model.ThemePreference
import com.example.ui.theme.MizanTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BlockingActivity : ComponentActivity() {

  private val viewModel: BlockingViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    intent?.getStringExtra(EXTRA_BLOCKED_PACKAGE)?.takeIf { it.isNotBlank() }?.let { pkg ->
      viewModel.updateTarget(pkg, intent.getStringExtra(EXTRA_BLOCKED_APP_LABEL))
    }

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          navigateToHomeLauncherAndFinish()
        }
      },
    )

    setContent {
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()
      LaunchedEffect(uiState) {
        if (uiState is BlockingUiState.ShouldDismiss) {
          finish()
        }
      }

      val themePreference =
        when (val state = uiState) {
          is BlockingUiState.Loading -> state.themePreference
          is BlockingUiState.Blocked -> state.themePreference
          is BlockingUiState.ShouldDismiss -> state.themePreference
        }
      val systemDark = isSystemInDarkTheme()
      val darkTheme =
        when (themePreference) {
          ThemePreference.SYSTEM -> systemDark
          ThemePreference.LIGHT -> false
          ThemePreference.DARK -> true
        }

      MizanTheme(darkTheme = darkTheme) {
        BlockingScreen(
          uiState = uiState,
          onReturnHome = ::navigateToHomeLauncherAndFinish,
          onOpenMizan = ::openMizanAndFinish,
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val pkg = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE).orEmpty()
    val label = intent.getStringExtra(EXTRA_BLOCKED_APP_LABEL)
    viewModel.updateTarget(pkg, label)
  }

  private fun navigateToHomeLauncherAndFinish() {
    val homeIntent =
      Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    runCatching { startActivity(homeIntent) }
    finish()
  }

  private fun openMizanAndFinish() {
    val mainIntent =
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
    runCatching { startActivity(mainIntent) }
    finish()
  }

  companion object {
    const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
    const val EXTRA_BLOCKED_APP_LABEL = "extra_blocked_app_label"
    const val EXTRA_RESTRICTION_REASON = "extra_restriction_reason"
    const val EXTRA_COMMITMENT_ID = "extra_commitment_id"
  }
}
