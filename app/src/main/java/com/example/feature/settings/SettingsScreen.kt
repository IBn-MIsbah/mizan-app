package com.example.feature.settings

import android.app.Activity
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.core.designsystem.component.AccessibilityHealthCard
import com.example.core.designsystem.component.BatteryOptimizationCard
import com.example.core.designsystem.component.MizanScopeNoticeCard
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.designsystem.component.UsageAccessCard
import com.example.core.designsystem.component.VpnFilterHealthCard
import com.example.domain.model.ThemePreference

@Composable
fun SettingsRoute(
  onRevisitOnboarding: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var settingsLaunchError by remember { mutableStateOf<String?>(null) }
  var usageSettingsLaunchError by remember { mutableStateOf<String?>(null) }
  var batterySettingsLaunchError by remember { mutableStateOf<String?>(null) }
  var appInfoLaunchError by remember { mutableStateOf<String?>(null) }
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
    appInfoLaunchError = null
    vpnActionError = null
    viewModel.refreshEnforcementHealth()
  }

  SettingsScreen(
    uiState = uiState,
    onSelectTheme = viewModel::setThemePreference,
    onToggleWifiOnly = viewModel::setWifiOnlyFilterUpdates,
    onRevisitOnboarding = onRevisitOnboarding,
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
    onCurrentPinChange = viewModel::onCurrentPinInputChange,
    onNewPinChange = viewModel::onNewPinInputChange,
    onSavePin = viewModel::savePin,
    onRemovePin = viewModel::removePin,
    onRequestRemoval = viewModel::request24HourRemoval,
    onCancelRemoval = viewModel::cancelRemovalRequest,
    onExecuteRemoval = viewModel::executeRemovalRequest,
    onOpenAppInfo = {
      appInfoLaunchError = null
      val started = runCatching { context.startActivity(viewModel.createAppDetailsSettingsIntent()) }.isSuccess
      if (!started) {
        appInfoLaunchError = "Unable to open Android App Info on this device."
      }
    },
    appInfoLaunchError = appInfoLaunchError,
    modifier = modifier,
  )
}

@Composable
fun SettingsScreen(
  uiState: SettingsUiState,
  onSelectTheme: (ThemePreference) -> Unit,
  onToggleWifiOnly: (Boolean) -> Unit,
  onRevisitOnboarding: () -> Unit,
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
  onCurrentPinChange: (String) -> Unit = {},
  onNewPinChange: (String) -> Unit = {},
  onSavePin: () -> Unit = {},
  onRemovePin: () -> Unit = {},
  onRequestRemoval: () -> Unit = {},
  onCancelRemoval: () -> Unit = {},
  onExecuteRemoval: () -> Unit = {},
  onOpenAppInfo: () -> Unit = {},
  appInfoLaunchError: String? = null,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("settings_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = stringResource(R.string.settings_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
          )
          Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      when (uiState) {
        SettingsUiState.Loading -> {
          item {
            MizanSurfaceCard {
              Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.testTag("settings_loading_indicator"))
              }
            }
          }
        }

        is SettingsUiState.Ready -> {
          val summary = uiState.summary

          if (summary.errorMessage != null) {
            item {
              MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                  text = summary.errorMessage,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.testTag("settings_error_text"),
                )
              }
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("settings_theme_card")) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Palette,
                  contentDescription = stringResource(R.string.cd_theme_icon),
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.settings_theme_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }

              val options = ThemePreference.entries
              SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                  val label =
                    when (option) {
                      ThemePreference.SYSTEM -> stringResource(R.string.settings_theme_system)
                      ThemePreference.LIGHT -> stringResource(R.string.settings_theme_light)
                      ThemePreference.DARK -> stringResource(R.string.settings_theme_dark)
                    }
                  SegmentedButton(
                    selected = summary.preferences.themePreference == option,
                    onClick = { onSelectTheme(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier =
                      Modifier.minimumInteractiveComponentSize()
                        .testTag("settings_theme_${option.name.lowercase()}"),
                  ) {
                    Text(text = label)
                  }
                }
              }
            }
          }

          item {
            AccountabilityPinSettingsCard(
              pinState = summary.pinState,
              onCurrentPinChange = onCurrentPinChange,
              onNewPinChange = onNewPinChange,
              onSavePin = onSavePin,
              onRemovePin = onRemovePin,
            )
          }

          item {
            VoluntaryRemovalRequestCard(
              removalState = summary.removalState,
              onRequestRemoval = onRequestRemoval,
              onCancelRemoval = onCancelRemoval,
              onExecuteRemoval = onExecuteRemoval,
              onOpenAppInfo = onOpenAppInfo,
              appInfoError = appInfoLaunchError,
            )
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("settings_filter_pref_card")) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Wifi,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.settings_filter_pref_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = stringResource(R.string.settings_filter_wifi_only_label),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                  checked = summary.preferences.wifiOnlyFilterUpdates,
                  onCheckedChange = onToggleWifiOnly,
                  modifier =
                    Modifier.minimumInteractiveComponentSize()
                      .testTag("settings_wifi_only_switch"),
                )
              }
              Text(
                text = stringResource(R.string.settings_filter_wifi_only_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (summary.datasets.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                summary.datasets.forEach { dataset ->
                  Text(
                    text =
                      "${dataset.category.name.replace('_', ' ')}: ${dataset.trustStatus.name} " +
                        "(${dataset.recordCount} entries · ${dataset.sourceName})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings_dataset_${dataset.datasetId}"),
                  )
                }
              }
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("settings_privacy_card")) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Lock,
                  contentDescription = stringResource(R.string.cd_privacy_icon),
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.settings_privacy_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              Text(
                text = stringResource(R.string.settings_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              OutlinedButton(
                onClick = onRevisitOnboarding,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("settings_revisit_onboarding_button"),
              ) {
                Text(text = stringResource(R.string.settings_onboarding_revisit_button))
              }
            }
          }

          item {
            AccessibilityHealthCard(
              healthSnapshot = summary.healthSnapshot,
              onOpenAccessibilitySettings = onOpenAccessibilitySettings,
              testTagPrefix = "settings_health",
              settingsLaunchError = settingsLaunchError,
            )
          }

          item {
            UsageAccessCard(
              usageAccessSnapshot = summary.usageAccessSnapshot,
              onOpenUsageAccessSettings = onOpenUsageAccessSettings,
              testTagPrefix = "settings_usage_access",
              settingsLaunchError = usageSettingsLaunchError,
            )
          }

          item {
            BatteryOptimizationCard(
              batterySnapshot = summary.batteryOptimizationSnapshot,
              onOpenBatterySettings = onOpenBatterySettings,
              testTagPrefix = "settings_battery_optimization",
              settingsLaunchError = batterySettingsLaunchError,
            )
          }

          item {
            VpnFilterHealthCard(
              vpnSnapshot = summary.vpnFilterSnapshot,
              onConsentChanged = onVpnConsentChanged,
              onEnableAndStartVpn = onEnableAndStartVpn,
              onStopVpn = onStopVpn,
              testTagPrefix = "settings_vpn_filter",
              actionFeedback = vpnActionFeedback,
            )
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("settings_permissions_card")) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Security,
                  contentDescription = stringResource(R.string.cd_permissions_icon),
                  tint = MaterialTheme.colorScheme.tertiary,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.settings_permissions_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              Text(
                text = stringResource(R.string.settings_permissions_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          item { MizanScopeNoticeCard() }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("settings_build_card")) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Info,
                  contentDescription = stringResource(R.string.cd_architecture_icon),
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.settings_build_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }

              Text(
                text = stringResource(R.string.settings_item_package),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = summary.applicationId,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("settings_application_id_text"),
              )

              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

              Text(
                text = stringResource(R.string.settings_item_version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = summary.versionName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("settings_version_text"),
              )
            }
          }
        }
      }
    }
  }
}
