package com.example.core.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.R
import kotlinx.serialization.Serializable

sealed interface MizanRoute {
  @Serializable data class Onboarding(val revisitFromSettings: Boolean = false) : MizanRoute

  @Serializable data object Home : MizanRoute

  @Serializable data object Commitments : MizanRoute

  @Serializable data class CommitmentReview(val commitmentId: String) : MizanRoute

  @Serializable data class CommitmentCompletion(val commitmentId: String) : MizanRoute

  @Serializable data class EmergencyTermination(val commitmentId: String) : MizanRoute

  @Serializable data object Settings : MizanRoute

  @Serializable
  data class InteractiveGuide(
    val sectionId: String? = null,
    val walkthroughMode: Boolean = false,
  ) : MizanRoute
}

enum class TopLevelDestination(
  val route: MizanRoute,
  @param:StringRes val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
) {
  HOME(
    route = MizanRoute.Home,
    labelRes = R.string.nav_home,
    selectedIcon = Icons.Filled.Balance,
    unselectedIcon = Icons.Outlined.Balance,
    testTag = "nav_home_item",
  ),
  COMMITMENTS(
    route = MizanRoute.Commitments,
    labelRes = R.string.nav_commitments,
    selectedIcon = Icons.Filled.CheckCircle,
    unselectedIcon = Icons.Outlined.CheckCircle,
    testTag = "nav_commitments_item",
  ),
  SETTINGS(
    route = MizanRoute.Settings,
    labelRes = R.string.nav_settings,
    selectedIcon = Icons.Filled.Tune,
    unselectedIcon = Icons.Outlined.Tune,
    testTag = "nav_settings_item",
  ),
}
