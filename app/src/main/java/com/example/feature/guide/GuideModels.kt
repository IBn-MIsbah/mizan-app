package com.example.feature.guide

import androidx.compose.runtime.Immutable
import com.example.core.designsystem.component.MizanExplanationSlide

enum class GuideCategory(val title: String) {
  GETTING_STARTED("Getting Started"),
  SETUP_MODES("Setup & Modes"),
  PERMISSIONS_VPN("Permissions & VPN"),
  ACTIVE_EMERGENCY("Active & Emergency"),
  DATASETS_PRIVACY("Datasets & Privacy"),
}

@Immutable
data class GuideActionDetail(
  val actionUiLabel: String,
  val whatItDoes: String,
  val consequence: String,
  val whatToExpect: String,
)

@Immutable
data class GuideTopic(
  val id: String,
  val stepNumber: Int,
  val category: GuideCategory,
  val title: String,
  val summary: String,
  val slides: List<MizanExplanationSlide>,
  val actionDetail: GuideActionDetail? = null,
)

enum class ContextualGuideContext {
  ONBOARDING,
  DASHBOARD,
  SETUP_MODES,
  SETUP_APPS,
  SETUP_SCHEDULE_ALLOWANCE,
  SETUP_WEBSITE,
  SETUP_PIN,
  REVIEW,
  COMPLETION,
  EMERGENCY,
  PERMISSIONS,
  SETTINGS_SECURITY,
  SETTINGS_DATASETS,
}
