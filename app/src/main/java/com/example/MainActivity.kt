package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.ThemePreference
import com.example.ui.MainUiState
import com.example.ui.MizanApp
import com.example.ui.MizanRootViewModel
import com.example.ui.theme.MizanTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val rootViewModel: MizanRootViewModel = hiltViewModel()
      val rootState by rootViewModel.uiState.collectAsStateWithLifecycle()
      val systemDark = isSystemInDarkTheme()
      val darkTheme =
        when (val state = rootState) {
          MainUiState.Loading -> systemDark
          is MainUiState.Ready ->
            when (state.preferences.themePreference) {
              ThemePreference.SYSTEM -> systemDark
              ThemePreference.LIGHT -> false
              ThemePreference.DARK -> true
            }
        }
      MizanTheme(darkTheme = darkTheme) {
        MizanApp(rootState = rootState)
      }
    }
  }
}
