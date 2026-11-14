package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.designsystem.component.ProvideMizanMotionPreferences
import com.example.core.navigation.MizanNavHost
import com.example.core.navigation.MizanRoute
import com.example.core.navigation.TopLevelDestination
import com.example.core.navigation.navigateToTopLevel

@Composable
fun MizanApp(
  rootState: MainUiState,
  modifier: Modifier = Modifier,
) {
  when (rootState) {
    MainUiState.Loading -> {
      Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
      ) { innerPadding ->
        Box(
          modifier = Modifier.fillMaxSize().padding(innerPadding),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(modifier = Modifier.testTag("app_root_loading_indicator"))
        }
      }
    }

    is MainUiState.Ready -> {
      ProvideMizanMotionPreferences(
        userReduceMotion = rootState.preferences.reduceGuideAnimations
      ) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val startDestination = remember {
          if (rootState.preferences.onboardingCompleted) {
            MizanRoute.Home
          } else {
            MizanRoute.Onboarding(revisitFromSettings = false)
          }
        }

        val isTopLevelRoute =
          currentDestination != null &&
            TopLevelDestination.entries.any { destination ->
              currentDestination.hasRoute(destination.route::class)
            }

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
          modifier = Modifier.fillMaxSize(),
          containerColor = MaterialTheme.colorScheme.background,
          bottomBar = {
            if (isTopLevelRoute && !useNavigationRail) {
              NavigationBar(modifier = Modifier.testTag("mizan_bottom_nav")) {
                TopLevelDestination.entries.forEach { destination ->
                  val selected = currentDestination?.hasRoute(destination.route::class) == true
                  val label = stringResource(destination.labelRes)
                  NavigationBarItem(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination.route) },
                    icon = {
                      Icon(
                        imageVector =
                          if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = label,
                      )
                    },
                    label = { Text(text = label) },
                    modifier = Modifier.testTag(destination.testTag),
                  )
                }
              }
            }
          },
        ) { innerPadding ->
          Row(
            modifier =
              Modifier.fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
          ) {
            if (isTopLevelRoute && useNavigationRail) {
              NavigationRail(
                modifier = Modifier.testTag("mizan_nav_rail"),
                windowInsets =
                  WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Vertical + WindowInsetsSides.Start
                  ),
              ) {
                TopLevelDestination.entries.forEach { destination ->
                  val selected = currentDestination?.hasRoute(destination.route::class) == true
                  val label = stringResource(destination.labelRes)
                  NavigationRailItem(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination.route) },
                    icon = {
                      Icon(
                        imageVector =
                          if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = label,
                      )
                    },
                    label = { Text(text = label) },
                    modifier = Modifier.testTag(destination.testTag),
                  )
                }
              }
            }

            MizanNavHost(
              navController = navController,
              startDestination = startDestination,
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
        }
      }
    }
  }
}

