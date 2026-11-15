package com.example.core.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.feature.commitments.CommitmentsRoute
import com.example.feature.completion.CommitmentCompletionRoute
import com.example.feature.emergency.EmergencyTerminationRoute
import com.example.feature.guide.InteractiveGuideRoute
import com.example.feature.home.HomeRoute
import com.example.feature.onboarding.OnboardingRoute
import com.example.feature.review.CommitmentReviewRoute
import com.example.feature.settings.SettingsRoute

@Composable
fun MizanNavHost(
  navController: NavHostController,
  startDestination: MizanRoute,
  modifier: Modifier = Modifier,
) {
  val openGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit =
    { sectionId, walkthroughMode ->
      navController.navigate(
        MizanRoute.InteractiveGuide(sectionId = sectionId, walkthroughMode = walkthroughMode)
      )
    }

  NavHost(
    navController = navController,
    startDestination = startDestination,
    modifier = modifier,
    enterTransition = { fadeIn(animationSpec = tween(220)) },
    exitTransition = { fadeOut(animationSpec = tween(180)) },
  ) {
    composable<MizanRoute.Onboarding> {
      OnboardingRoute(
        onOnboardingCompleted = {
          navController.navigate(MizanRoute.Home) {
            popUpTo<MizanRoute.Onboarding> { inclusive = true }
            launchSingleTop = true
          }
        },
        onNavigateBack = { navController.popBackStack() },
        onOpenUserGuide = openGuide,
      )
    }

    composable<MizanRoute.Home> {
      HomeRoute(
        onNavigateToSetup = { navController.navigateToTopLevel(MizanRoute.Commitments) },
        onNavigateToReview = { commitmentId ->
          navController.navigate(MizanRoute.CommitmentReview(commitmentId))
        },
        onNavigateToCompletion = { commitmentId ->
          navController.navigate(MizanRoute.CommitmentCompletion(commitmentId))
        },
        onNavigateToEmergencyTermination = { commitmentId ->
          navController.navigate(MizanRoute.EmergencyTermination(commitmentId))
        },
        onOpenUserGuide = openGuide,
      )
    }

    composable<MizanRoute.Commitments> {
      CommitmentsRoute(
        onNavigateToReview = { commitmentId ->
          navController.navigate(MizanRoute.CommitmentReview(commitmentId))
        },
        onNavigateToDashboard = { navController.navigateToTopLevel(MizanRoute.Home) },
        onOpenUserGuide = openGuide,
      )
    }

    composable<MizanRoute.CommitmentReview> {
      CommitmentReviewRoute(
        onNavigateBack = { navController.popBackStack() },
        onActivated = {
          navController.navigate(MizanRoute.Home) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
            launchSingleTop = true
          }
        },
        onOpenUserGuide = openGuide,
      )
    }

    composable<MizanRoute.CommitmentCompletion> {
      CommitmentCompletionRoute(
        onNavigateBack = { navController.popBackStack() },
        onNavigateToDashboard = {
          navController.navigate(MizanRoute.Home) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
            launchSingleTop = true
          }
        },
        onNavigateToSetup = {
          navController.navigate(MizanRoute.Commitments) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
            launchSingleTop = true
          }
        },
        onOpenUserGuide = openGuide,
      )
    }

    composable<MizanRoute.EmergencyTermination> {
      EmergencyTerminationRoute(
        onNavigateBack = { navController.popBackStack() },
        onTerminated = {
          navController.navigate(MizanRoute.Home) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
            launchSingleTop = true
          }
        },
        onOpenUserGuide = openGuide,
      )
    }

    composable<MizanRoute.Settings> {
      SettingsRoute(
        onRevisitOnboarding = {
          navController.navigate(MizanRoute.Onboarding(revisitFromSettings = true))
        },
        onNavigateToSetup = { navController.navigateToTopLevel(MizanRoute.Commitments) },
        onOpenUserGuide = openGuide,
      )
    }

    composable<MizanRoute.InteractiveGuide> {
      InteractiveGuideRoute(
        onNavigateBack = { navController.popBackStack() },
      )
    }
  }
}

fun NavHostController.navigateToTopLevel(route: MizanRoute) {
  navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
  }
}
