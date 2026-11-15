package com.example.feature.onboarding

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.designsystem.component.MizanBrandHeader
import com.example.core.designsystem.component.MizanExpandableSection
import com.example.core.designsystem.component.MizanFlowTopBar
import com.example.core.designsystem.component.MizanStatusBadge
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.time.WallClockFormatting
import com.example.domain.model.AllowlistCategory
import com.example.domain.model.AppId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DomainCategory
import com.example.domain.model.DomainName
import com.example.domain.model.DomainRuleType
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.RestrictionMode
import com.example.domain.time.DomainDayOfWeek
import com.example.feature.commitments.AccountabilityPinSetupCard
import com.example.feature.commitments.AllowlistSelectionCard
import com.example.feature.commitments.AllowanceSelectionCard
import com.example.feature.commitments.ModeSelectionCard
import com.example.feature.commitments.RestrictedAppsSelectionCard
import com.example.feature.commitments.ScheduleBuilderCard
import com.example.feature.commitments.WebsiteFilteringSetupCard
import com.example.feature.commitments.YouTubeShortsSetupCard
import com.example.feature.home.RulesSummaryBlock
import com.example.platform.health.EnforcementHealthStatus
import com.example.platform.health.UsageAccessPermissionStatus
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.collectLatest

@Composable
fun OnboardingRoute(
  onOnboardingCompleted: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenUserGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit = { _, _ -> },
  viewModel: OnboardingViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val readyState = uiState as OnboardingUiState.Ready
  val context = LocalContext.current
  var permissionLaunchError by remember { mutableStateOf<String?>(null) }
  val a11yErrorText = stringResource(R.string.health_open_settings_error)
  val usageErrorText = stringResource(R.string.usage_access_open_settings_error)

  val vpnPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      viewModel.onVpnPermissionResult(granted = result.resultCode == Activity.RESULT_OK)
    }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    permissionLaunchError = null
    viewModel.refreshPermissions()
  }

  LaunchedEffect(viewModel) {
    viewModel.effects.collectLatest { effect ->
      when (effect) {
        OnboardingEffect.NavigateToDashboard -> onOnboardingCompleted()
      }
    }
  }

  BackHandler(enabled = readyState.revisitFromSettings || readyState.stage != OnboardingStage.INTRODUCTION) {
    val movedBack = viewModel.goBackStage()
    if (!movedBack && readyState.revisitFromSettings) {
      onNavigateBack()
    }
  }

  OnboardingScreen(
    uiState = readyState,
    onContinue = viewModel::skipOnboarding,
    onNavigateBack = {
      val movedBack = viewModel.goBackStage()
      if (!movedBack && readyState.revisitFromSettings) {
        onNavigateBack()
      }
    },
    onOpenAccessibilitySettings = {
      permissionLaunchError = null
      val started =
        runCatching { context.startActivity(viewModel.createAccessibilitySettingsIntent()) }.isSuccess
      if (!started) permissionLaunchError = a11yErrorText
    },
    onOpenUsageAccessSettings = {
      permissionLaunchError = null
      val started =
        runCatching { context.startActivity(viewModel.createUsageAccessSettingsIntent()) }.isSuccess
      if (!started) permissionLaunchError = usageErrorText
    },
    onRequestVpnAuthorization = {
      permissionLaunchError = null
      val intent = viewModel.prepareVpnIntent()
      if (intent != null) {
        val launched = runCatching { vpnPermissionLauncher.launch(intent) }.isSuccess
        if (!launched) {
          permissionLaunchError = "Unable to launch Android VPN authorization prompt."
        }
      } else {
        viewModel.onVpnPermissionResult(granted = true)
      }
    },
    permissionLaunchError = permissionLaunchError,
    onAdvanceFromIntroduction = viewModel::advanceFromIntroduction,
    onAdvanceFromPermissions = viewModel::advanceFromPermissions,
    onAdvanceFromConfiguration = viewModel::advanceFromConfiguration,
    onAdvanceFromReview = viewModel::advanceFromReview,
    onPreviousStage = { viewModel.goBackStage() },
    onAgreementChanged = viewModel::setAgreementChecked,
    onActivateCommitment = viewModel::activateCommitment,
    onSelectMode = viewModel::selectMode,
    onRestrictedSearchChange = viewModel::onRestrictedSearchChange,
    onToggleInstalledRestrictedApp = viewModel::toggleInstalledRestrictedApp,
    onCustomRestrictedLabelChange = viewModel::onCustomRestrictedLabelChange,
    onCustomRestrictedPackageChange = viewModel::onCustomRestrictedPackageChange,
    onAddCustomRestrictedApp = viewModel::addCustomRestrictedApp,
    onRemoveRestrictedApp = viewModel::removeRestrictedApp,
    onAllowlistSearchChange = viewModel::onAllowlistSearchChange,
    onToggleInstalledAllowedApp = viewModel::toggleInstalledAllowedApp,
    onCustomAllowedLabelChange = viewModel::onCustomAllowedLabelChange,
    onCustomAllowedPackageChange = viewModel::onCustomAllowedPackageChange,
    onCustomAllowedCategoryChange = viewModel::onCustomAllowedCategoryChange,
    onAddCustomAllowedApp = viewModel::addCustomAllowedApp,
    onRemoveAllowedApp = viewModel::removeAllowedApp,
    onToggleScheduleDay = viewModel::toggleScheduleDay,
    onAdjustWindowStart = viewModel::adjustWindowStart,
    onAdjustWindowEnd = viewModel::adjustWindowEnd,
    onAddScheduleWindow = viewModel::addScheduleWindow,
    onRemoveScheduleWindow = viewModel::removeScheduleWindow,
    onSetDailyAllowance = viewModel::setDailyAllowanceMinutes,
    onAdjustDailyAllowance = viewModel::adjustDailyAllowance,
    onToggleWebsiteFiltering = viewModel::setWebsiteFilteringEnabled,
    onDomainInputChange = viewModel::onCustomDomainInputChange,
    onAddBlockedDomain = { viewModel.addCustomDomainRule(DomainRuleType.BLOCK) },
    onAddAllowedDomain = { viewModel.addCustomDomainRule(DomainRuleType.ALLOW) },
    onRemoveDomainRule = viewModel::removeDomainRule,
    onToggleDomainCategory = viewModel::toggleDomainCategory,
    onToggleBlockYouTubeShorts = viewModel::setBlockYouTubeShorts,
    onToggleRequirePin = viewModel::setRequirePinForEmergencyTermination,
    onOpenUserGuide = onOpenUserGuide,
    modifier = modifier,
  )
}

@Composable
fun OnboardingScreen(
  uiState: OnboardingUiState,
  onContinue: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenAccessibilitySettings: () -> Unit = {},
  onOpenUsageAccessSettings: () -> Unit = {},
  onRequestVpnAuthorization: () -> Unit = {},
  permissionLaunchError: String? = null,
  onAdvanceFromIntroduction: () -> Unit = {},
  onAdvanceFromPermissions: () -> Unit = {},
  onAdvanceFromConfiguration: () -> Unit = {},
  onAdvanceFromReview: () -> Unit = {},
  onPreviousStage: () -> Unit = {},
  onAgreementChanged: (Boolean) -> Unit = {},
  onActivateCommitment: () -> Unit = {},
  onSelectMode: (RestrictionMode) -> Unit = {},
  onRestrictedSearchChange: (String) -> Unit = {},
  onToggleInstalledRestrictedApp: (InstalledAppInfo) -> Unit = {},
  onCustomRestrictedLabelChange: (String) -> Unit = {},
  onCustomRestrictedPackageChange: (String) -> Unit = {},
  onAddCustomRestrictedApp: () -> Unit = {},
  onRemoveRestrictedApp: (AppId) -> Unit = {},
  onAllowlistSearchChange: (String) -> Unit = {},
  onToggleInstalledAllowedApp: (InstalledAppInfo) -> Unit = {},
  onCustomAllowedLabelChange: (String) -> Unit = {},
  onCustomAllowedPackageChange: (String) -> Unit = {},
  onCustomAllowedCategoryChange: (AllowlistCategory) -> Unit = {},
  onAddCustomAllowedApp: () -> Unit = {},
  onRemoveAllowedApp: (AppId) -> Unit = {},
  onToggleScheduleDay: (DomainDayOfWeek) -> Unit = {},
  onAdjustWindowStart: (Int) -> Unit = {},
  onAdjustWindowEnd: (Int) -> Unit = {},
  onAddScheduleWindow: () -> Unit = {},
  onRemoveScheduleWindow: (Int) -> Unit = {},
  onSetDailyAllowance: (Int) -> Unit = {},
  onAdjustDailyAllowance: (Int) -> Unit = {},
  onToggleWebsiteFiltering: (Boolean) -> Unit = {},
  onDomainInputChange: (String) -> Unit = {},
  onAddBlockedDomain: () -> Unit = {},
  onAddAllowedDomain: () -> Unit = {},
  onRemoveDomainRule: (DomainName, DomainRuleType) -> Unit = { _, _ -> },
  onToggleDomainCategory: (DomainCategory) -> Unit = {},
  onToggleBlockYouTubeShorts: (Boolean) -> Unit = {},
  onToggleRequirePin: (Boolean) -> Unit = {},
  onOpenUserGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit = { _, _ -> },
) {
  val state = uiState as OnboardingUiState.Ready
  val stage = state.stage

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("onboarding_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      if (state.revisitFromSettings) {
        item {
          MizanFlowTopBar(
            title = stringResource(R.string.onboarding_title),
            onNavigateBack = onNavigateBack,
            backTestTag = "onboarding_back_button",
          )
        }
      } else if (stage == OnboardingStage.INTRODUCTION) {
        item { MizanBrandHeader() }
      }

      item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = stringResource(R.string.onboarding_stage_format, stage.stepNumber),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("onboarding_stage_indicator"),
            )
            if (stage != OnboardingStage.INTRODUCTION) {
              TextButton(
                onClick = onPreviousStage,
                modifier =
                  Modifier.minimumInteractiveComponentSize().testTag("onboarding_stage_back_button"),
              ) {
                Text(text = stringResource(R.string.back_label))
              }
            }
          }
          LinearProgressIndicator(
            progress = { stage.stepNumber / 5f },
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .testTag("onboarding_stage_progress"),
          )
        }
      }

      when (stage) {
        OnboardingStage.INTRODUCTION -> {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = stringResource(R.string.onboarding_intro_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = stringResource(R.string.onboarding_intro_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("onboarding_intro_card")) {
              Text(
                text = "• ${stringResource(R.string.onboarding_intro_point_1)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "• ${stringResource(R.string.onboarding_intro_point_2)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "• ${stringResource(R.string.onboarding_intro_point_3)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
              MizanExpandableSection(
                title = stringResource(R.string.onboarding_intro_privacy_toggle),
                testTag = "onboarding_intro_privacy_toggle",
              ) {
                Text(
                  text = stringResource(R.string.settings_privacy_body),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          item {
            Button(
              onClick = onAdvanceFromIntroduction,
              modifier =
                Modifier.fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .heightIn(min = 48.dp)
                  .testTag("onboarding_stage_next_button"),
            ) {
              Text(text = stringResource(R.string.onboarding_intro_start_button))
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
              )
            }
          }
        }

        OnboardingStage.PERMISSIONS -> {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = stringResource(R.string.onboarding_permissions_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = stringResource(R.string.onboarding_permissions_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          item {
            val a11yBadge =
              when (state.healthSnapshot.healthStatus) {
                EnforcementHealthStatus.ACTIVE ->
                  PermissionBadge(
                    Icons.Filled.CheckCircle,
                    stringResource(R.string.health_badge_active),
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                EnforcementHealthStatus.ENABLED_SERVICE_DISCONNECTED ->
                  PermissionBadge(
                    Icons.Filled.Warning,
                    stringResource(R.string.health_badge_disconnected),
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                  )
                EnforcementHealthStatus.DISABLED ->
                  PermissionBadge(
                    Icons.Filled.PauseCircleOutline,
                    stringResource(R.string.health_badge_disabled),
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                  )
                EnforcementHealthStatus.UNAVAILABLE ->
                  PermissionBadge(
                    Icons.Filled.ErrorOutline,
                    stringResource(R.string.health_badge_unavailable),
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                  )
              }
            PermissionStageCard(
              title = stringResource(R.string.onboarding_perm_a11y_title),
              shortBody = stringResource(R.string.onboarding_perm_a11y_short),
              detailBody = stringResource(R.string.onboarding_perm_a11y_detail),
              icon = Icons.Outlined.Security,
              badge = a11yBadge,
              actionLabel = stringResource(R.string.health_open_settings_button),
              actionEnabled = true,
              onActionClick = onOpenAccessibilitySettings,
              cardTag = "onboarding_perm_a11y_card",
              badgeTag = "onboarding_perm_a11y_status_badge",
              detailsToggleTag = "onboarding_perm_a11y_details_toggle",
              actionTag = "onboarding_perm_a11y_open_button",
            )
          }

          item {
            val usageBadge =
              when (state.usageAccessSnapshot.permissionStatus) {
                UsageAccessPermissionStatus.ENABLED ->
                  PermissionBadge(
                    Icons.Filled.CheckCircle,
                    stringResource(R.string.usage_access_badge_enabled),
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                UsageAccessPermissionStatus.DISABLED ->
                  PermissionBadge(
                    Icons.Filled.PauseCircleOutline,
                    stringResource(R.string.usage_access_badge_disabled),
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                  )
                UsageAccessPermissionStatus.UNAVAILABLE ->
                  PermissionBadge(
                    Icons.Filled.ErrorOutline,
                    stringResource(R.string.usage_access_badge_unavailable),
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                  )
              }
            PermissionStageCard(
              title = stringResource(R.string.onboarding_perm_usage_title),
              shortBody = stringResource(R.string.onboarding_perm_usage_short),
              detailBody = stringResource(R.string.onboarding_perm_usage_detail),
              icon = Icons.Outlined.QueryStats,
              badge = usageBadge,
              actionLabel = stringResource(R.string.usage_access_open_settings_button),
              actionEnabled = true,
              onActionClick = onOpenUsageAccessSettings,
              cardTag = "onboarding_perm_usage_card",
              badgeTag = "onboarding_perm_usage_status_badge",
              detailsToggleTag = "onboarding_perm_usage_details_toggle",
              actionTag = "onboarding_perm_usage_open_button",
            )
          }

          item {
            val vpnBadge =
              if (state.vpnPrepared) {
                PermissionBadge(
                  Icons.Filled.CheckCircle,
                  stringResource(R.string.onboarding_perm_authorized),
                  MaterialTheme.colorScheme.primaryContainer,
                  MaterialTheme.colorScheme.onPrimaryContainer,
                )
              } else {
                PermissionBadge(
                  Icons.Filled.PauseCircleOutline,
                  stringResource(R.string.vpn_badge_not_configured),
                  MaterialTheme.colorScheme.secondaryContainer,
                  MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            PermissionStageCard(
              title = stringResource(R.string.onboarding_perm_vpn_title),
              shortBody = stringResource(R.string.onboarding_perm_vpn_short),
              detailBody = stringResource(R.string.onboarding_perm_vpn_detail),
              icon = Icons.Outlined.VpnLock,
              badge = vpnBadge,
              actionLabel =
                if (state.vpnPrepared) {
                  stringResource(R.string.onboarding_perm_authorized)
                } else {
                  stringResource(R.string.onboarding_perm_authorize_vpn)
                },
              actionEnabled = !state.vpnPrepared,
              onActionClick = onRequestVpnAuthorization,
              cardTag = "onboarding_perm_vpn_card",
              badgeTag = "onboarding_perm_vpn_status_badge",
              detailsToggleTag = "onboarding_perm_vpn_details_toggle",
              actionTag = "onboarding_perm_vpn_button",
            )
          }

          if (permissionLaunchError != null) {
            item {
              MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                  text = permissionLaunchError,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }

          item {
            Button(
              onClick = onAdvanceFromPermissions,
              modifier =
                Modifier.fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .heightIn(min = 48.dp)
                  .testTag("onboarding_stage_next_button"),
            ) {
              Text(text = stringResource(R.string.onboarding_permissions_next_button))
            }
          }
        }

        OnboardingStage.CONFIGURATION -> {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = stringResource(R.string.onboarding_config_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = stringResource(R.string.onboarding_config_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          if (state.activeCommitmentLocked) {
            item {
              MizanSurfaceCard(modifier = Modifier.testTag("onboarding_config_locked_card")) {
                MizanStatusBadge(
                  icon = Icons.Filled.Lock,
                  label = stringResource(R.string.setup_locked_title),
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                  text = stringResource(R.string.setup_locked_body),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                  onClick = onContinue,
                  modifier =
                    Modifier.fillMaxWidth()
                      .minimumInteractiveComponentSize()
                      .heightIn(min = 48.dp)
                      .testTag("onboarding_stage_next_button"),
                ) {
                  Text(text = stringResource(R.string.setup_go_dashboard))
                }
              }
            }
          } else {
            val editor = state.editor
            item {
              ModeSelectionCard(
                selectedMode = editor.mode,
                onSelectMode = onSelectMode,
                onShowHelp = { onOpenUserGuide("purpose_modes", false) },
              )
            }
            item {
              RestrictedAppsSelectionCard(
                editor = editor,
                onSearchChange = onRestrictedSearchChange,
                onToggleInstalledApp = onToggleInstalledRestrictedApp,
                onCustomLabelChange = onCustomRestrictedLabelChange,
                onCustomPackageChange = onCustomRestrictedPackageChange,
                onAddCustom = onAddCustomRestrictedApp,
                onRemoveApp = onRemoveRestrictedApp,
                onShowHelp = { onOpenUserGuide("selecting_apps", false) },
              )
            }
            item {
              AllowlistSelectionCard(
                editor = editor,
                onSearchChange = onAllowlistSearchChange,
                onToggleInstalledApp = onToggleInstalledAllowedApp,
                onCustomLabelChange = onCustomAllowedLabelChange,
                onCustomPackageChange = onCustomAllowedPackageChange,
                onCategoryChange = onCustomAllowedCategoryChange,
                onAddCustom = onAddCustomAllowedApp,
                onRemoveApp = onRemoveAllowedApp,
                onShowHelp = { onOpenUserGuide("allowlist", false) },
              )
            }
            if (editor.mode != RestrictionMode.STRICT) {
              item {
                ScheduleBuilderCard(
                  editor = editor,
                  onToggleDay = onToggleScheduleDay,
                  onAdjustStart = onAdjustWindowStart,
                  onAdjustEnd = onAdjustWindowEnd,
                  onAddWindow = onAddScheduleWindow,
                  onRemoveWindow = onRemoveScheduleWindow,
                  onShowHelp = { onOpenUserGuide("schedule_mode", false) },
                )
              }
            }
            if (editor.mode == RestrictionMode.HYBRID) {
              item {
                AllowanceSelectionCard(
                  allowanceMinutes = editor.dailyAllowanceMinutes,
                  onSetAllowance = onSetDailyAllowance,
                  onAdjustAllowance = onAdjustDailyAllowance,
                  onShowHelp = { onOpenUserGuide("daily_allowance", false) },
                )
              }
            }
            item {
              WebsiteFilteringSetupCard(
                editor = editor,
                onToggleEnabled = onToggleWebsiteFiltering,
                onDomainInputChange = onDomainInputChange,
                onAddBlockedDomain = onAddBlockedDomain,
                onAddAllowedDomain = onAddAllowedDomain,
                onRemoveDomain = onRemoveDomainRule,
                onToggleCategory = onToggleDomainCategory,
                onShowHelp = { onOpenUserGuide("vpn_website_filtering", false) },
              )
            }
            item {
              YouTubeShortsSetupCard(
                blockYouTubeShorts = editor.blockYouTubeShorts,
                healthSnapshot = state.healthSnapshot,
                onToggleBlockYouTubeShorts = onToggleBlockYouTubeShorts,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onShowHelp = { onOpenUserGuide("selecting_apps", false) },
                testTagPrefix = "onboarding_youtube_shorts",
              )
            }
            item {
              AccountabilityPinSetupCard(
                editor = editor,
                onToggleRequirePin = onToggleRequirePin,
                onShowHelp = { onOpenUserGuide("emergency_pin", false) },
              )
            }

            if (editor.validationMessages.isNotEmpty()) {
              item {
                MizanSurfaceCard(
                  modifier = Modifier.testTag("onboarding_config_error_card"),
                  containerColor = MaterialTheme.colorScheme.errorContainer,
                ) {
                  editor.validationMessages.forEach { message ->
                    Text(
                      text = "• $message",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                  }
                }
              }
            }

            item {
              Button(
                onClick = onAdvanceFromConfiguration,
                enabled = !state.isSubmitting,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("onboarding_stage_next_button"),
              ) {
                if (state.isSubmitting) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                  )
                } else {
                  Text(text = stringResource(R.string.onboarding_config_next_button))
                }
              }
            }
          }
        }

        OnboardingStage.REVIEW -> {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = stringResource(R.string.onboarding_review_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = stringResource(R.string.onboarding_review_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("onboarding_review_duration_card")) {
              Text(
                text = stringResource(R.string.review_duration_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              val previewStart = state.previewStart
              val previewEnd = state.previewEnd
              if (previewStart != null && previewEnd != null) {
                Text(
                  text = "If activated now: ${WallClockFormatting.formatDateTime(previewStart)}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "Ends after 7 days: ${WallClockFormatting.formatDateTime(previewEnd)}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }

          item {
            val reviewRules =
              CommitmentRules(
                mode = state.editor.mode,
                restrictedApps = state.editor.restrictedApps,
                allowedApps = state.editor.allowedApps,
                scheduleWindows = state.editor.scheduleWindows,
                dailyAllowance = state.editor.dailyAllowanceMinutes.minutes,
                requirePinForEmergencyTermination = state.editor.requirePinForEmergencyTermination,
                websiteFilteringEnabled = state.editor.websiteFilteringEnabled,
                blockedDomains = state.editor.blockedDomains,
                blockedDomainCategories = state.editor.blockedDomainCategories,
                blockYouTubeShorts = state.editor.blockYouTubeShorts,
              )
            MizanSurfaceCard(modifier = Modifier.testTag("onboarding_review_rules_card")) {
              RulesSummaryBlock(rules = reviewRules)
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
              MizanExpandableSection(
                title = stringResource(R.string.onboarding_review_limits_toggle),
                testTag = "onboarding_review_limits_toggle",
              ) {
                Text(
                  text = stringResource(R.string.review_duration_body),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = stringResource(R.string.vpn_disclosure_body),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          item {
            Button(
              onClick = onAdvanceFromReview,
              modifier =
                Modifier.fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .heightIn(min = 48.dp)
                  .testTag("onboarding_stage_next_button"),
            ) {
              Text(text = stringResource(R.string.onboarding_review_next_button))
            }
          }
        }

        OnboardingStage.AGREEMENT -> {
          item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = stringResource(R.string.onboarding_agreement_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = stringResource(R.string.onboarding_agreement_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("onboarding_agreement_card")) {
              Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                  checked = state.agreedToExactRules,
                  onCheckedChange = onAgreementChanged,
                  modifier =
                    Modifier.minimumInteractiveComponentSize()
                      .testTag("onboarding_agreement_checkbox"),
                )
                Text(
                  text = stringResource(R.string.onboarding_agreement_checkbox),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.padding(start = 6.dp),
                )
              }

              Button(
                onClick = onActivateCommitment,
                enabled =
                  state.agreedToExactRules &&
                    !state.isSubmitting &&
                    !state.activeCommitmentLocked,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("onboarding_activate_button"),
              ) {
                if (state.isSubmitting) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                  )
                } else {
                  Text(text = stringResource(R.string.onboarding_agreement_activate_button))
                }
              }
            }
          }
        }
      }

      if (state.errorMessage != null) {
        item {
          MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
            Text(
              text = state.errorMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.testTag("onboarding_error_text"),
            )
          }
        }
      }

      item {
        if (state.revisitFromSettings) {
          OutlinedButton(
            onClick = onNavigateBack,
            modifier =
              Modifier.fillMaxWidth()
                .minimumInteractiveComponentSize()
                .heightIn(min = 48.dp)
                .testTag("onboarding_return_settings_button"),
          ) {
            Text(text = stringResource(R.string.onboarding_return_settings_button))
          }
        } else {
          val skipLabel =
            when (stage) {
              OnboardingStage.CONFIGURATION ->
                stringResource(R.string.onboarding_config_skip_save_button)
              OnboardingStage.AGREEMENT ->
                stringResource(R.string.onboarding_agreement_save_exit_button)
              else -> stringResource(R.string.onboarding_skip_button)
            }
          OutlinedButton(
            onClick = onContinue,
            enabled = !state.isSubmitting,
            modifier =
              Modifier.fillMaxWidth()
                .minimumInteractiveComponentSize()
                .heightIn(min = 48.dp)
                .testTag("onboarding_skip_button"),
          ) {
            Text(text = skipLabel)
          }
        }
      }
    }
  }
}

@Composable
private fun PermissionStageCard(
  title: String,
  shortBody: String,
  detailBody: String,
  icon: ImageVector,
  badge: PermissionBadge,
  actionLabel: String,
  actionEnabled: Boolean,
  onActionClick: () -> Unit,
  cardTag: String,
  badgeTag: String,
  detailsToggleTag: String,
  actionTag: String,
) {
  MizanSurfaceCard(modifier = Modifier.testTag(cardTag)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
    }
    MizanStatusBadge(
      icon = badge.icon,
      label = badge.label,
      containerColor = badge.container,
      contentColor = badge.content,
      modifier = Modifier.testTag(badgeTag),
    )
    Text(
      text = shortBody,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    MizanExpandableSection(
      title = stringResource(R.string.onboarding_perm_why_toggle),
      testTag = detailsToggleTag,
    ) {
      Text(
        text = detailBody,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    OutlinedButton(
      onClick = onActionClick,
      enabled = actionEnabled,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag(actionTag),
    ) {
      Text(text = actionLabel)
    }
  }
}

private data class PermissionBadge(
  val icon: ImageVector,
  val label: String,
  val container: Color,
  val content: Color,
)
