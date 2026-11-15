package com.example.feature.home

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.designsystem.component.AccessibilityHealthCard
import com.example.core.designsystem.component.BatteryOptimizationCard
import com.example.core.designsystem.component.ContextualGuideSheet
import com.example.core.designsystem.component.ContextualHelpButton
import com.example.core.designsystem.component.DashboardInsightsCard
import com.example.core.designsystem.component.LocalMizanReducedMotion
import com.example.core.designsystem.component.MizanEmptyStateCard
import com.example.core.designsystem.component.MizanExpandableSection
import com.example.core.designsystem.component.MizanStatusBadge
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.designsystem.component.UsageAccessCard
import com.example.core.designsystem.component.VpnFilterHealthCard
import com.example.core.time.WallClockFormatting
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentRules
import com.example.domain.model.CompletionNextStep
import com.example.domain.model.DomainRuleType
import com.example.domain.model.RestrictionMode
import com.example.domain.model.UsageCoverageStatus
import com.example.feature.guide.ContextualGuideCatalog
import com.example.feature.guide.ContextualGuideContext
import com.example.platform.health.BatteryOptimizationStatus
import com.example.platform.vpn.VpnServiceLifecycleStatus
import kotlin.time.Duration

@Composable
fun HomeRoute(
  onNavigateToSetup: () -> Unit,
  onNavigateToReview: (String) -> Unit,
  onNavigateToCompletion: (String) -> Unit,
  onNavigateToEmergencyTermination: (String) -> Unit,
  modifier: Modifier = Modifier,
  onOpenUserGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit = { _, _ -> },
  viewModel: HomeViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var settingsLaunchError by remember { mutableStateOf<String?>(null) }
  var usageSettingsLaunchError by remember { mutableStateOf<String?>(null) }
  var batterySettingsLaunchError by remember { mutableStateOf<String?>(null) }
  var vpnActionError by remember { mutableStateOf<String?>(null) }
  val launchErrorText = stringResource(R.string.health_open_settings_error)
  val usageLaunchErrorText = stringResource(R.string.usage_access_open_settings_error)
  val batteryLaunchErrorText = stringResource(R.string.battery_open_settings_error)

  val vpnPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      viewModel.onVpnPermissionResult(granted = result.resultCode == Activity.RESULT_OK)
    }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    settingsLaunchError = null
    usageSettingsLaunchError = null
    batterySettingsLaunchError = null
    vpnActionError = null
    viewModel.refreshEnforcementHealth()
  }

  HomeScreen(
    uiState = uiState,
    onRetry = viewModel::refresh,
    onOpenAccessibilitySettings = {
      settingsLaunchError = null
      val started = runCatching { context.startActivity(viewModel.createAccessibilitySettingsIntent()) }.isSuccess
      if (!started) {
        settingsLaunchError = launchErrorText
      }
    },
    settingsLaunchError = settingsLaunchError,
    onOpenUsageAccessSettings = {
      usageSettingsLaunchError = null
      val started = runCatching { context.startActivity(viewModel.createUsageAccessSettingsIntent()) }.isSuccess
      if (!started) {
        usageSettingsLaunchError = usageLaunchErrorText
      }
    },
    usageSettingsLaunchError = usageSettingsLaunchError,
    onOpenBatterySettings = {
      batterySettingsLaunchError = null
      val started =
        runCatching { context.startActivity(viewModel.createBatteryOptimizationSettingsIntent()) }
          .isSuccess
      if (!started) {
        batterySettingsLaunchError = batteryLaunchErrorText
      }
    },
    batterySettingsLaunchError = batterySettingsLaunchError,
    onVpnConsentChanged = viewModel::setWebsiteFilteringConsent,
    onEnableAndStartVpn = {
      vpnActionError = null
      val prepareIntent = viewModel.prepareVpnIntent()
      if (prepareIntent != null) {
        val launched = runCatching { vpnPermissionLauncher.launch(prepareIntent) }.isSuccess
        if (!launched) {
          vpnActionError = "Unable to launch Android VPN authorization prompt."
        }
      } else {
        viewModel.requestStartVpnFiltering()
      }
    },
    onStopVpn = viewModel::requestStopVpnFiltering,
    vpnActionFeedback = vpnActionError,
    onNavigateToSetup = onNavigateToSetup,
    onNavigateToReview = onNavigateToReview,
    onNavigateToCompletion = onNavigateToCompletion,
    onNavigateToEmergencyTermination = onNavigateToEmergencyTermination,
    onDismissFirstUseGuide = viewModel::dismissFirstUseGuide,
    onOpenUserGuide = onOpenUserGuide,
    modifier = modifier,
  )
}

@Composable
fun HomeScreen(
  uiState: DashboardUiState,
  onRetry: () -> Unit,
  onNavigateToSetup: () -> Unit,
  onNavigateToReview: (String) -> Unit,
  onNavigateToCompletion: (String) -> Unit,
  onNavigateToEmergencyTermination: (String) -> Unit,
  modifier: Modifier = Modifier,
  onOpenAccessibilitySettings: () -> Unit = {},
  settingsLaunchError: String? = null,
  onOpenUsageAccessSettings: () -> Unit = {},
  usageSettingsLaunchError: String? = null,
  onOpenBatterySettings: () -> Unit = {},
  batterySettingsLaunchError: String? = null,
  onVpnConsentChanged: (Boolean) -> Unit = {},
  onEnableAndStartVpn: () -> Unit = {},
  onStopVpn: () -> Unit = {},
  vpnActionFeedback: String? = null,
  onDismissFirstUseGuide: () -> Unit = {},
  onOpenUserGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit = { _, _ -> },
) {
  var showDashboardHelpSheet by remember { mutableStateOf(false) }

  if (showDashboardHelpSheet) {
    ContextualGuideSheet(
      title = ContextualGuideCatalog.titleFor(ContextualGuideContext.DASHBOARD),
      slides = ContextualGuideCatalog.slidesFor(ContextualGuideContext.DASHBOARD),
      onDismiss = { showDashboardHelpSheet = false },
      testTagPrefix = "home_contextual_guide_sheet",
      onOpenFullGuide = {
        onOpenUserGuide(ContextualGuideCatalog.topicIdFor(ContextualGuideContext.DASHBOARD), false)
      },
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("home_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = stringResource(R.string.dashboard_headline),
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text = stringResource(R.string.dashboard_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          ContextualHelpButton(
            onClick = { showDashboardHelpSheet = true },
            contentDescription = stringResource(R.string.guide_contextual_help_cd),
            label = stringResource(R.string.guide_open_action),
            testTag = "home_contextual_help_button",
          )
        }
      }

      when (uiState) {
        DashboardUiState.Loading -> {
          item {
            MizanSurfaceCard {
              Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.testTag("home_loading_indicator"))
              }
            }
          }
        }

        is DashboardUiState.Error -> {
          item {
            MizanSurfaceCard(
              modifier = Modifier.testTag("dashboard_error_card"),
              containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
              Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 48.dp),
              ) {
                Text(text = stringResource(R.string.retry_label))
              }
            }
          }
        }

        is DashboardUiState.Success -> {
          val content = uiState.content

          item {
            when (val status = content.status) {
              DashboardStatus.NoCommitment -> {
                MizanEmptyStateCard(
                  icon = Icons.Outlined.HourglassEmpty,
                  iconContentDescription = stringResource(R.string.cd_status_indicator),
                  title = stringResource(R.string.dashboard_empty_title),
                  body = stringResource(R.string.dashboard_empty_body),
                  modifier = Modifier.testTag("dashboard_empty_card"),
                ) {
                  Button(
                    onClick = onNavigateToSetup,
                    modifier =
                      Modifier.fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .heightIn(min = 48.dp)
                        .testTag("dashboard_start_setup_button"),
                  ) {
                    Text(text = stringResource(R.string.dashboard_start_setup_action))
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                      contentDescription = null,
                      modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    )
                  }
                }
              }

              is DashboardStatus.DraftInProgress -> {
                DraftStatusCard(
                  draft = status.draft,
                  onEditSetup = onNavigateToSetup,
                  onReviewAndAgree = { onNavigateToReview(status.draft.id.value) },
                )
              }

              is DashboardStatus.Active -> {
                ActiveCommitmentCard(
                  activeState = status,
                  onCompleteCommitment = { onNavigateToCompletion(status.commitment.id.value) },
                  onEmergencyTerminate = {
                    onNavigateToEmergencyTermination(status.commitment.id.value)
                  },
                )
              }

              is DashboardStatus.Completed -> {
                CompletedStatusCard(
                  completed = status.commitment,
                  onChooseNextStep = { onNavigateToCompletion(status.commitment.id.value) },
                  onConfigureNew = onNavigateToSetup,
                )
              }

              is DashboardStatus.EmergencyTerminated -> {
                TerminatedStatusCard(
                  terminated = status.commitment,
                  onConfigureNew = onNavigateToSetup,
                )
              }
            }
          }

          item {
            ProtectionReadinessCard(
              content = content,
              onOpenAccessibilitySettings = onOpenAccessibilitySettings,
              settingsLaunchError = settingsLaunchError,
              onOpenUsageAccessSettings = onOpenUsageAccessSettings,
              usageSettingsLaunchError = usageSettingsLaunchError,
              onOpenBatterySettings = onOpenBatterySettings,
              batterySettingsLaunchError = batterySettingsLaunchError,
              onVpnConsentChanged = onVpnConsentChanged,
              onEnableAndStartVpn = onEnableAndStartVpn,
              onStopVpn = onStopVpn,
              vpnActionFeedback = vpnActionFeedback,
            )
          }

          if (content.insights != null) {
            item {
              DashboardInsightsCard(
                insights = content.insights,
                hasActiveCommitment = content.status is DashboardStatus.Active,
              )
            }
          }

          if (content.history.isNotEmpty()) {
            item {
              MizanSurfaceCard(modifier = Modifier.testTag("dashboard_history_card")) {
                MizanExpandableSection(
                  title = stringResource(R.string.disclosure_history_format, content.history.size),
                  testTag = "dashboard_history_toggle",
                ) {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    content.history.forEach { commitment ->
                      HistorySummaryCard(commitment = commitment)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProtectionReadinessCard(
  content: DashboardContent,
  onOpenAccessibilitySettings: () -> Unit,
  settingsLaunchError: String?,
  onOpenUsageAccessSettings: () -> Unit,
  usageSettingsLaunchError: String?,
  onOpenBatterySettings: () -> Unit,
  batterySettingsLaunchError: String?,
  onVpnConsentChanged: (Boolean) -> Unit,
  onEnableAndStartVpn: () -> Unit,
  onStopVpn: () -> Unit,
  vpnActionFeedback: String?,
) {
  val activeCommitment = (content.status as? DashboardStatus.Active)?.commitment
  val hasActiveAppRules = activeCommitment != null && activeCommitment.rules.restrictedApps.isNotEmpty()
  val hasActiveWebRules =
    activeCommitment != null && activeCommitment.rules.hasConfiguredWebsiteFiltering()

  val appActionNeeded =
    hasActiveAppRules && content.readiness.appStatus != OperationalReadiness.OPERATIONAL
  val batteryWarningNeeded =
    activeCommitment != null &&
      content.batteryOptimizationSnapshot.status == BatteryOptimizationStatus.OPTIMIZED
  val webActionNeeded =
    hasActiveWebRules &&
      (content.readiness.webStatus != OperationalReadiness.OPERATIONAL ||
        content.vpnFilterSnapshot.isPrivateDnsActive ||
        content.vpnFilterSnapshot.datasetWarningMessage != null)

  MizanSurfaceCard(modifier = Modifier.testTag("dashboard_protection_status_card")) {
    Text(
      text = stringResource(R.string.dashboard_readiness_title),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )

    ReadinessStatusRow(
      label = stringResource(R.string.dashboard_app_protection_label),
      status = content.readiness.appStatus,
      badgeTestTag = "dashboard_app_readiness_badge",
    )
    ReadinessStatusRow(
      label = stringResource(R.string.dashboard_web_protection_label),
      status = content.readiness.webStatus,
      badgeTestTag = "dashboard_web_readiness_badge",
    )

    if (appActionNeeded) {
      MizanSurfaceCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.testTag("dashboard_urgent_app_warning"),
      ) {
        Text(
          text =
            when (content.readiness.appStatus) {
              OperationalReadiness.ENABLED_NOT_OPERATIONAL ->
                stringResource(R.string.health_desc_disconnected)
              OperationalReadiness.UNAVAILABLE -> stringResource(R.string.health_desc_unavailable)
              else -> stringResource(R.string.health_desc_disabled)
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
        TextButton(
          onClick = onOpenAccessibilitySettings,
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("dashboard_urgent_fix_a11y_button"),
        ) {
          Text(
            text = stringResource(R.string.health_open_settings_button),
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }
    }

    if (batteryWarningNeeded) {
      MizanSurfaceCard(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.testTag("dashboard_urgent_battery_warning"),
      ) {
        Text(
          text = stringResource(R.string.battery_desc_optimized),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        TextButton(
          onClick = onOpenBatterySettings,
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("dashboard_urgent_fix_battery_button"),
        ) {
          Text(text = stringResource(R.string.battery_open_settings_button))
        }
      }
    }

    if (webActionNeeded) {
      val vpn = content.vpnFilterSnapshot
      val webWarningMessage =
        when {
          vpn.datasetWarningMessage != null -> vpn.datasetWarningMessage
          vpn.isPrivateDnsActive -> stringResource(R.string.vpn_private_dns_warning)
          vpn.status == VpnServiceLifecycleStatus.ANOTHER_VPN_ACTIVE ->
            stringResource(R.string.vpn_desc_another_vpn)
          vpn.status == VpnServiceLifecycleStatus.DEGRADED_ERROR ->
            vpn.lastError ?: stringResource(R.string.vpn_desc_error)
          else -> stringResource(R.string.vpn_desc_permission_required)
        }
      MizanSurfaceCard(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.testTag("dashboard_urgent_web_warning"),
      ) {
        Text(
          text = webWarningMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        if (vpn.hasConsented && !vpn.isTunnelEstablished) {
          TextButton(
            onClick = onEnableAndStartVpn,
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .testTag("dashboard_urgent_fix_vpn_button"),
          ) {
            Text(text = stringResource(R.string.vpn_enable_start_button))
          }
        }
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    MizanExpandableSection(
      title = stringResource(R.string.disclosure_system_health),
      testTag = "dashboard_system_health_toggle",
    ) {
      AccessibilityHealthCard(
        healthSnapshot = content.healthSnapshot,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        testTagPrefix = "dashboard_health",
        settingsLaunchError = settingsLaunchError,
      )
      UsageAccessCard(
        usageAccessSnapshot = content.usageAccessSnapshot,
        onOpenUsageAccessSettings = onOpenUsageAccessSettings,
        testTagPrefix = "dashboard_usage_access",
        settingsLaunchError = usageSettingsLaunchError,
      )
      if (content.batteryOptimizationSnapshot.status == BatteryOptimizationStatus.OPTIMIZED) {
        BatteryOptimizationCard(
          batterySnapshot = content.batteryOptimizationSnapshot,
          onOpenBatterySettings = onOpenBatterySettings,
          testTagPrefix = "dashboard_battery_optimization",
          settingsLaunchError = batterySettingsLaunchError,
        )
      }
      VpnFilterHealthCard(
        vpnSnapshot = content.vpnFilterSnapshot,
        onConsentChanged = onVpnConsentChanged,
        onEnableAndStartVpn = onEnableAndStartVpn,
        onStopVpn = onStopVpn,
        testTagPrefix = "dashboard_vpn_filter",
        actionFeedback = vpnActionFeedback,
        usePrimaryActionButton = false,
      )
    }
  }
}

@Composable
private fun ReadinessStatusRow(
  label: String,
  status: OperationalReadiness,
  badgeTestTag: String,
) {
  val style =
    when (status) {
      OperationalReadiness.NOT_CONFIGURED ->
        ReadinessBadgeStyle(
          icon = Icons.Filled.PauseCircleOutline,
          label = stringResource(R.string.status_not_configured),
          container = MaterialTheme.colorScheme.surfaceContainerHighest,
          content = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      OperationalReadiness.CONFIGURED_ACTION_NEEDED ->
        ReadinessBadgeStyle(
          icon = Icons.Filled.Warning,
          label = stringResource(R.string.status_configured_action_needed),
          container = MaterialTheme.colorScheme.tertiaryContainer,
          content = MaterialTheme.colorScheme.onTertiaryContainer,
        )
      OperationalReadiness.ENABLED_NOT_OPERATIONAL ->
        ReadinessBadgeStyle(
          icon = Icons.Filled.Warning,
          label = stringResource(R.string.status_enabled_not_operational),
          container = MaterialTheme.colorScheme.errorContainer,
          content = MaterialTheme.colorScheme.onErrorContainer,
        )
      OperationalReadiness.OPERATIONAL ->
        ReadinessBadgeStyle(
          icon = Icons.Filled.CheckCircle,
          label = stringResource(R.string.status_operational),
          container = MaterialTheme.colorScheme.primaryContainer,
          content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      OperationalReadiness.UNAVAILABLE ->
        ReadinessBadgeStyle(
          icon = Icons.Filled.ErrorOutline,
          label = stringResource(R.string.status_unavailable),
          container = MaterialTheme.colorScheme.errorContainer,
          content = MaterialTheme.colorScheme.onErrorContainer,
        )
    }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f).padding(end = 8.dp),
    )
    MizanStatusBadge(
      icon = style.icon,
      label = style.label,
      containerColor = style.container,
      contentColor = style.content,
      modifier = Modifier.testTag(badgeTestTag),
    )
  }
}

private data class ReadinessBadgeStyle(
  val icon: ImageVector,
  val label: String,
  val container: Color,
  val content: Color,
)

@Composable
private fun DraftStatusCard(
  draft: Commitment.Draft,
  onEditSetup: () -> Unit,
  onReviewAndAgree: () -> Unit,
) {
  MizanSurfaceCard(modifier = Modifier.testTag("dashboard_draft_card")) {
    MizanStatusBadge(
      icon = Icons.Filled.EditNote,
      label = stringResource(R.string.dashboard_draft_badge),
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Text(
      text = stringResource(R.string.dashboard_draft_title),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text =
        "${modeShortLabel(draft.rules.mode)} · ${draft.rules.restrictedApps.size} apps · " +
          "${draft.rules.allowedApps.size} allowed",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    MizanExpandableSection(
      title = stringResource(R.string.disclosure_active_rules),
      testTag = "dashboard_draft_rules_toggle",
    ) {
      RulesSummaryBlock(rules = draft.rules)
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        onClick = onEditSetup,
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("dashboard_edit_draft_button"),
      ) {
        Text(text = stringResource(R.string.dashboard_edit_draft_action))
      }
      Button(
        onClick = onReviewAndAgree,
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("dashboard_review_draft_button"),
      ) {
        Text(text = stringResource(R.string.dashboard_review_draft_action))
      }
    }
  }
}

@Composable
private fun ActiveCommitmentCard(
  activeState: DashboardStatus.Active,
  onCompleteCommitment: () -> Unit,
  onEmergencyTerminate: () -> Unit,
) {
  val active = activeState.commitment
  MizanSurfaceCard(modifier = Modifier.testTag("dashboard_active_card")) {
    MizanStatusBadge(
      icon = if (activeState.hasReachedEnd) Icons.Filled.CheckCircle else Icons.Filled.Lock,
      label =
        if (activeState.hasReachedEnd) {
          stringResource(R.string.dashboard_active_ended_badge)
        } else {
          stringResource(R.string.dashboard_active_badge)
        },
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.testTag("dashboard_active_status_badge"),
    )

    val reducedMotion = LocalMizanReducedMotion.current
    val animatedElapsed by
      animateFloatAsState(
        targetValue = if (activeState.hasReachedEnd) 1f else activeState.elapsedProgressFraction,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 420),
        label = "commitment_7day_progress",
      )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text =
          if (activeState.hasReachedEnd) {
            stringResource(R.string.dashboard_progress_ended_label)
          } else {
            stringResource(
              R.string.dashboard_progress_days_format,
              activeState.currentDayOfSeven,
              activeState.remainingDurationLabel,
            )
          },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("dashboard_active_progress_label"),
      )
      LinearProgressIndicator(
        progress = { animatedElapsed },
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .testTag("dashboard_active_progress_bar"),
      )
      Text(
        text =
          "${modeShortLabel(active.rules.mode)} · Ends ${WallClockFormatting.formatDateTime(active.endsAt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (active.rules.mode == RestrictionMode.HYBRID) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      HybridAllowanceSection(activeState = activeState)
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    MizanExpandableSection(
      title = stringResource(R.string.disclosure_active_rules),
      testTag = "dashboard_active_rules_toggle",
    ) {
      Text(
        text = "Started: ${WallClockFormatting.formatDateTime(active.activatedAt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      RulesSummaryBlock(rules = active.rules)
    }

    if (activeState.hasReachedEnd) {
      MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
          text = stringResource(R.string.dashboard_active_ended_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Button(
          onClick = onCompleteCommitment,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("dashboard_complete_commitment_button"),
        ) {
          Text(text = stringResource(R.string.dashboard_complete_action))
        }
      }
    }

    OutlinedButton(
      onClick = onEmergencyTerminate,
      colors =
        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("dashboard_emergency_terminate_button"),
    ) {
      Text(text = stringResource(R.string.dashboard_emergency_action))
    }
  }
}

@Composable
private fun HybridAllowanceSection(activeState: DashboardStatus.Active) {
  val active = activeState.commitment
  val allowanceText = WallClockFormatting.formatDuration(active.rules.dailyAllowance)
  val usage = activeState.dailyUsage

  if (activeState.usageLoadError != null || usage == null) {
    Text(
      text = "Usage unavailable (Shared daily limit: $allowanceText)",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.testTag("dashboard_allowance_summary"),
    )
    Text(
      text = activeState.usageLoadError ?: stringResource(R.string.dashboard_usage_unavailable),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.testTag("dashboard_usage_coverage_status"),
    )
    return
  }

  val consumedText = WallClockFormatting.formatDuration(usage.consumed)
  val remainingText = WallClockFormatting.formatDuration(activeState.remainingAllowance)
  val isExhausted = activeState.remainingAllowance <= Duration.ZERO

  val headlineText =
    if (isExhausted) {
      "${stringResource(R.string.dashboard_usage_shared_exhausted)} ($consumedText / $allowanceText used)"
    } else {
      "Shared Daily Allowance: $remainingText remaining ($consumedText / $allowanceText used)"
    }

  Text(
    text = headlineText,
    style = MaterialTheme.typography.titleSmall,
    color = if (isExhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.testTag("dashboard_allowance_summary"),
  )

  val allowanceTotalMs = active.rules.dailyAllowance.inWholeMilliseconds.coerceAtLeast(1L)
  val allowanceRemainingFraction =
    (activeState.remainingAllowance.inWholeMilliseconds.toFloat() / allowanceTotalMs.toFloat())
      .coerceIn(0f, 1f)
  val animatedAllowance by
    animateFloatAsState(
      targetValue = allowanceRemainingFraction,
      animationSpec = tween(durationMillis = if (LocalMizanReducedMotion.current) 0 else 360),
      label = "hybrid_allowance_bar",
    )
  LinearProgressIndicator(
    progress = { animatedAllowance },
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(4.dp))
        .testTag("dashboard_allowance_progress_bar"),
  )

  Text(
    text = stringResource(R.string.dashboard_allowance_note),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  val coverageText =
    when (val coverage = activeState.usageCoverage) {
      DashboardUsageCoverage.Unavailable -> stringResource(R.string.dashboard_usage_unavailable)
      is DashboardUsageCoverage.Incomplete -> "Partial coverage: ${coverage.reason}"
      is DashboardUsageCoverage.Measured -> {
        val sourceLabel =
          when (coverage.coverageStatus) {
            UsageCoverageStatus.MEASURED -> "Measured via live foreground tracking"
            UsageCoverageStatus.MEASURED_AND_RECONCILED ->
              "Measured + reconciled with local UsageStats history"
            UsageCoverageStatus.RECONCILED_ONLY ->
              "Reconciled from local UsageStats history"
            UsageCoverageStatus.INCOMPLETE -> "Partial coverage"
          }
        val extra =
          when {
            !coverage.isAccessibilityActive ->
              "Accessibility service is inactive; live foreground tracking paused."
            !coverage.isUsageAccessEnabled ->
              "Usage Access is disabled; missed intervals are not backfilled."
            else -> "Usage Access enabled for local reconciliation."
          }
        "$sourceLabel · $extra"
      }
    }

  Text(
    text = coverageText,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.testTag("dashboard_usage_coverage_status"),
  )
}

@Composable
private fun CompletedStatusCard(
  completed: Commitment.Completed,
  onChooseNextStep: () -> Unit,
  onConfigureNew: () -> Unit,
) {
  MizanSurfaceCard(modifier = Modifier.testTag("dashboard_completed_card")) {
    MizanStatusBadge(
      icon = Icons.Filled.CheckCircle,
      label = stringResource(R.string.dashboard_completed_badge),
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Text(
      text = stringResource(R.string.dashboard_completed_title),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = "Completed: ${WallClockFormatting.formatDateTime(completed.completedAt)}",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val nextStepLabel =
      when (completed.nextStepChoice) {
        CompletionNextStep.FINISH -> "Next step chosen: Finished cleanly"
        CompletionNextStep.BEGIN_ANOTHER_DETOX -> "Next step chosen: Begin another detox"
        CompletionNextStep.ENTER_DISCIPLINE_MODE ->
          "Next step chosen: Discipline Mode (enforcement specification pending)"
        null -> "Next step pending — choose how to proceed (never auto-renews)."
      }
    Text(
      text = nextStepLabel,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    if (completed.nextStepChoice == null) {
      Button(
        onClick = onChooseNextStep,
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("dashboard_choose_next_step_button"),
      ) {
        Text(text = stringResource(R.string.dashboard_choose_next_step_action))
      }
    } else {
      OutlinedButton(
        onClick = onConfigureNew,
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("dashboard_new_commitment_button"),
      ) {
        Text(text = stringResource(R.string.dashboard_new_commitment_action))
      }
    }
  }
}

@Composable
private fun TerminatedStatusCard(
  terminated: Commitment.EmergencyTerminated,
  onConfigureNew: () -> Unit,
) {
  MizanSurfaceCard(modifier = Modifier.testTag("dashboard_terminated_card")) {
    MizanStatusBadge(
      icon = Icons.Filled.Warning,
      label = stringResource(R.string.dashboard_terminated_badge),
      containerColor = MaterialTheme.colorScheme.errorContainer,
      contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    Text(
      text = stringResource(R.string.dashboard_terminated_title),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = stringResource(R.string.dashboard_terminated_body),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text =
        "Terminated: ${WallClockFormatting.formatDateTime(terminated.terminatedAt)} · " +
          "Planned end: ${WallClockFormatting.formatDateTime(terminated.plannedEndsAt)}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
      onClick = onConfigureNew,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("dashboard_new_commitment_button"),
    ) {
      Text(text = stringResource(R.string.dashboard_new_commitment_action))
    }
  }
}

@Composable
private fun HistorySummaryCard(commitment: Commitment) {
  val label =
    when (commitment) {
      is Commitment.Completed ->
        "Completed · ${WallClockFormatting.formatDate(commitment.completedAt.date)}"
      is Commitment.EmergencyTerminated ->
        "Emergency Terminated · ${WallClockFormatting.formatDate(commitment.terminatedAt.date)}"
      is Commitment.Active ->
        "Active · ${WallClockFormatting.formatDate(commitment.activatedAt.date)}"
      is Commitment.Draft ->
        "Draft · ${WallClockFormatting.formatDate(commitment.createdAt.date)}"
    }
  MizanSurfaceCard(
    modifier = Modifier.testTag("dashboard_history_item_${commitment.id.value}"),
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text =
        "${modeShortLabel(commitment.rules.mode)} · " +
          "${commitment.rules.restrictedApps.size} restricted · ${commitment.rules.allowedApps.size} allowlisted",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun modeShortLabel(mode: RestrictionMode): String =
  when (mode) {
    RestrictionMode.STRICT -> "Strict"
    RestrictionMode.SCHEDULED -> "Scheduled"
    RestrictionMode.HYBRID -> "Hybrid"
  }

@Composable
internal fun RulesSummaryBlock(rules: CommitmentRules) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val modeLabel =
      when (rules.mode) {
        RestrictionMode.STRICT -> "Strict (blocked throughout commitment)"
        RestrictionMode.SCHEDULED -> "Scheduled (blocked during configured windows)"
        RestrictionMode.HYBRID ->
          "Hybrid (scheduled blocking + ${WallClockFormatting.formatDuration(rules.dailyAllowance)} shared allowance)"
      }
    Text(
      text = "Mode: $modeLabel",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    val restrictedNames =
      if (rules.restrictedApps.isEmpty()) "None selected"
      else rules.restrictedApps.joinToString(", ") { it.displayLabel }
    Text(
      text = "Restricted apps (${rules.restrictedApps.size}): $restrictedNames",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val allowedNames =
      if (rules.allowedApps.isEmpty()) "None selected"
      else rules.allowedApps.joinToString(", ") { it.displayLabel }
    Text(
      text = "Allowlist (${rules.allowedApps.size}): $allowedNames",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (rules.scheduleWindows.isNotEmpty()) {
      Text(
        text = "Schedule windows (${rules.scheduleWindows.size}):",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      rules.scheduleWindows.forEach { window ->
        Text(
          text = "• ${WallClockFormatting.formatScheduleWindow(window)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (rules.websiteFilteringEnabled) {
      val blockDomains =
        rules.blockedDomains.filter { it.ruleType == DomainRuleType.BLOCK }.map { it.domain.value }
      val allowDomains =
        rules.blockedDomains.filter { it.ruleType == DomainRuleType.ALLOW }.map { it.domain.value }
      val categoryLabels =
        rules.blockedDomainCategories.map { it.name.replace('_', ' ').lowercase() }
      Text(
        text =
          "Website DNS filtering: Enabled · ${blockDomains.size} blocked domain(s), ${allowDomains.size} exception(s)" +
            if (categoryLabels.isNotEmpty()) " · categories: ${categoryLabels.joinToString(", ")}" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (blockDomains.isNotEmpty()) {
        Text(
          text = "Blocked domains: ${blockDomains.sorted().joinToString(", ")}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (allowDomains.isNotEmpty()) {
        Text(
          text = "Allowed domain exceptions: ${allowDomains.sorted().joinToString(", ")}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Text(
        text = "Website DNS filtering: Off",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text =
        if (rules.blockYouTubeShorts) {
          "YouTube Shorts restriction: Enabled (normal YouTube allowed)"
        } else {
          "YouTube Shorts restriction: Off"
        },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text =
        if (rules.requirePinForEmergencyTermination) {
          "Emergency termination PIN protection: Required"
        } else {
          "Emergency termination PIN protection: Off"
        },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
