package com.example.feature.settings

import android.app.Activity
import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.core.designsystem.component.ContextualGuideSheet
import com.example.core.designsystem.component.ContextualHelpButton
import com.example.core.designsystem.component.MizanExpandableSection
import com.example.core.designsystem.component.MizanSectionHeader
import com.example.core.designsystem.component.MizanStatusBadge
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.designsystem.component.UsageAccessCard
import com.example.core.designsystem.component.VpnFilterHealthCard
import com.example.data.filter.SignedLocalDatasetImporter
import com.example.domain.model.DatasetAvailabilityStatus
import com.example.domain.model.DomainCategory
import com.example.domain.model.ThemePreference
import com.example.feature.commitments.YouTubeShortsSetupCard
import com.example.feature.guide.ContextualGuideCatalog
import com.example.feature.guide.ContextualGuideContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsRoute(
  onRevisitOnboarding: () -> Unit,
  modifier: Modifier = Modifier,
  onNavigateToSetup: () -> Unit = {},
  onOpenUserGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit = { _, _ -> },
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var settingsLaunchError by remember { mutableStateOf<String?>(null) }
  var usageSettingsLaunchError by remember { mutableStateOf<String?>(null) }
  var batterySettingsLaunchError by remember { mutableStateOf<String?>(null) }
  var appInfoLaunchError by remember { mutableStateOf<String?>(null) }
  var vpnActionError by remember { mutableStateOf<String?>(null) }
  var datasetImportReadError by remember { mutableStateOf<String?>(null) }
  val launchErrorText = stringResource(R.string.health_open_settings_error)
  val usageLaunchErrorText = stringResource(R.string.usage_access_open_settings_error)
  val batteryLaunchErrorText = stringResource(R.string.battery_open_settings_error)

  val vpnPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      viewModel.onVpnPermissionResult(granted = result.resultCode == Activity.RESULT_OK)
    }

  val datasetImportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      datasetImportReadError = null
      coroutineScope.launch {
        val readResult =
          withContext(Dispatchers.IO) {
            runCatching {
              readBoundedDocumentBytes(
                contentResolver = context.contentResolver,
                uri = uri,
                maxAllowedBytes = SignedLocalDatasetImporter.MAX_PACKAGE_BYTES,
              )
            }
          }
        readResult
          .onSuccess { bytes -> viewModel.importSignedDatasetPackage(bytes) }
          .onFailure { error ->
            datasetImportReadError =
              error.message ?: "Unable to read the selected dataset file."
          }
      }
    }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    settingsLaunchError = null
    usageSettingsLaunchError = null
    batterySettingsLaunchError = null
    appInfoLaunchError = null
    vpnActionError = null
    datasetImportReadError = null
    viewModel.refreshEnforcementHealth()
  }

  SettingsScreen(
    uiState = uiState,
    onSelectTheme = viewModel::setThemePreference,
    onToggleWifiOnly = viewModel::setWifiOnlyFilterUpdates,
    onRevisitOnboarding = onRevisitOnboarding,
    onNavigateToSetup = onNavigateToSetup,
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
    onCheckDatasetUpdates = {
      datasetImportReadError = null
      viewModel.checkDatasetUpdatesNow()
    },
    onImportSignedDataset = {
      datasetImportReadError = null
      val launched =
        runCatching {
          datasetImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }.isSuccess
      if (!launched) {
        datasetImportReadError = "Unable to open Android document picker on this device."
      }
    },
    onRollbackDataset = { datasetId ->
      datasetImportReadError = null
      viewModel.rollbackDataset(datasetId)
    },
    datasetImportReadError = datasetImportReadError,
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
    onOpenUserGuide = onOpenUserGuide,
    onReplayWalkthrough = {
      viewModel.replayFirstUseWalkthrough()
      onOpenUserGuide(null, true)
    },
    onToggleYouTubeShorts = viewModel::setYouTubeShortsBlocked,
    onToggleReduceMotion = viewModel::setReduceGuideAnimations,
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
  onNavigateToSetup: () -> Unit = {},
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
  onCheckDatasetUpdates: () -> Unit = {},
  onImportSignedDataset: () -> Unit = {},
  onRollbackDataset: (String) -> Unit = {},
  datasetImportReadError: String? = null,
  onCurrentPinChange: (String) -> Unit = {},
  onNewPinChange: (String) -> Unit = {},
  onSavePin: () -> Unit = {},
  onRemovePin: () -> Unit = {},
  onRequestRemoval: () -> Unit = {},
  onCancelRemoval: () -> Unit = {},
  onExecuteRemoval: () -> Unit = {},
  onOpenAppInfo: () -> Unit = {},
  appInfoLaunchError: String? = null,
  onOpenUserGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit = { _, _ -> },
  onReplayWalkthrough: () -> Unit = {},
  onToggleYouTubeShorts: (Boolean) -> Unit = {},
  onToggleReduceMotion: (Boolean) -> Unit = {},
) {
  var activeHelpContext by remember { mutableStateOf<ContextualGuideContext?>(null) }

  activeHelpContext?.let { helpContext ->
    ContextualGuideSheet(
      title = ContextualGuideCatalog.titleFor(helpContext),
      slides = ContextualGuideCatalog.slidesFor(helpContext),
      onDismiss = { activeHelpContext = null },
      testTagPrefix = "settings_contextual_guide_sheet",
      onOpenFullGuide = {
        onOpenUserGuide(ContextualGuideCatalog.topicIdFor(helpContext), false)
      },
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("settings_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

          // Domain 1 & 2: System Health (Accessibility, Usage Access, Battery)
          item {
            MizanSectionHeader(
              title = stringResource(R.string.settings_domain_system_health),
              modifier = Modifier.testTag("settings_section_system_health"),
            )
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

          // Domain 3: Website Protection (Local VPN + Datasets)
          item {
            MizanSectionHeader(
              title = stringResource(R.string.settings_domain_website),
              modifier = Modifier.testTag("settings_section_website"),
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
            MizanSurfaceCard(modifier = Modifier.testTag("settings_filter_pref_card")) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Row(
                  modifier = Modifier.weight(1f),
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
                ContextualHelpButton(
                  onClick = { activeHelpContext = ContextualGuideContext.SETTINGS_DATASETS },
                  contentDescription = stringResource(R.string.guide_contextual_help_cd),
                  testTag = "settings_dataset_help_button",
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
              MizanExpandableSection(
                title = stringResource(R.string.disclosure_show_details),
                testTag = "settings_dataset_policy_toggle",
              ) {
                Text(
                  text = stringResource(R.string.settings_filter_wifi_only_note),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              val displayItems =
                if (summary.datasetItems.isNotEmpty()) {
                  summary.datasetItems
                } else {
                  summary.datasets.map { meta ->
                    DatasetManagementItem(
                      metadata = meta,
                      availabilityStatus =
                        if (meta.datasetId in summary.datasetSyncSnapshot.updatingDatasetIds) {
                          DatasetAvailabilityStatus.UPDATING
                        } else if (meta.isUsable) {
                          DatasetAvailabilityStatus.AVAILABLE
                        } else {
                          DatasetAvailabilityStatus.UNAVAILABLE
                        },
                    )
                  }
                }

              if (displayItems.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                displayItems.forEach { item ->
                  val dataset = item.metadata
                  DatasetMetadataRow(
                    item = item,
                    isBusy = summary.datasetSyncSnapshot.isAnyUpdating,
                    onRollback = { onRollbackDataset(dataset.datasetId) },
                  )
                }
              }

              OutlinedButton(
                onClick = onCheckDatasetUpdates,
                enabled = !summary.datasetSyncSnapshot.isAnyUpdating,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("settings_dataset_check_updates_button"),
              ) {
                Text(text = stringResource(R.string.settings_dataset_check_updates_button))
              }

              OutlinedButton(
                onClick = onImportSignedDataset,
                enabled = !summary.datasetSyncSnapshot.isAnyUpdating,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("settings_dataset_import_button"),
              ) {
                Text(text = stringResource(R.string.settings_dataset_import_button))
              }

              val syncFeedback =
                datasetImportReadError
                  ?: summary.datasetSyncSnapshot.errorMessage
                  ?: summary.datasetSyncSnapshot.statusMessage
              if (syncFeedback != null) {
                val isError =
                  datasetImportReadError != null ||
                    summary.datasetSyncSnapshot.errorMessage != null
                Text(
                  text = syncFeedback,
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    if (isError) {
                      MaterialTheme.colorScheme.error
                    } else {
                      MaterialTheme.colorScheme.primary
                    },
                  modifier = Modifier.testTag("settings_dataset_sync_feedback"),
                )
              }

              MizanExpandableSection(
                title = stringResource(R.string.disclosure_dns_limits),
                testTag = "settings_dataset_limitations_toggle",
              ) {
                Text(
                  text = stringResource(R.string.settings_dataset_limitations_note),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.testTag("settings_dataset_limitations_note"),
                )
              }
            }
          }

          // Domain 4: Commitment, Schedule & Allowance
          item {
            MizanSectionHeader(
              title = stringResource(R.string.settings_domain_commitment),
              modifier = Modifier.testTag("settings_section_commitment"),
            )
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("settings_commitment_card")) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Tune,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.settings_domain_commitment),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              OutlinedButton(
                onClick = onNavigateToSetup,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("settings_open_setup_button"),
              ) {
                Text(text = stringResource(R.string.settings_open_setup_button))
              }
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
            YouTubeShortsSetupCard(
              blockYouTubeShorts = summary.youtubeShortsBlocked,
              healthSnapshot = summary.healthSnapshot,
              onToggleBlockYouTubeShorts = onToggleYouTubeShorts,
              onOpenAccessibilitySettings = onOpenAccessibilitySettings,
              isLocked = summary.youtubeShortsRuleLocked,
              statusNoteOverride = stringResource(R.string.settings_youtube_shorts_draft_note),
              testTagPrefix = "settings_youtube_shorts",
            )
          }

          // Domain 5: Security (PIN + 24-Hour Voluntary Removal)
          item {
            MizanSectionHeader(
              title = stringResource(R.string.settings_domain_security),
              modifier = Modifier.testTag("settings_section_security"),
            )
          }

          item {
            AccountabilityPinSettingsCard(
              pinState = summary.pinState,
              onCurrentPinChange = onCurrentPinChange,
              onNewPinChange = onNewPinChange,
              onSavePin = onSavePin,
              onRemovePin = onRemovePin,
              onShowHelp = { activeHelpContext = ContextualGuideContext.SETTINGS_SECURITY },
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
              onShowHelp = { activeHelpContext = ContextualGuideContext.SETTINGS_SECURITY },
            )
          }

          // Domain 6 & 7: Preferences & Reference (Theme, Guide, Motion, App & Scope Info)
          item {
            MizanSectionHeader(
              title = stringResource(R.string.settings_domain_preferences),
              modifier = Modifier.testTag("settings_section_preferences"),
            )
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
            MizanSurfaceCard(modifier = Modifier.testTag("settings_help_guide_card")) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.settings_help_guide_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              OutlinedButton(
                onClick = { onOpenUserGuide(null, false) },
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("settings_open_guide_button"),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.settings_open_guide_button))
              }
              OutlinedButton(
                onClick = onReplayWalkthrough,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("settings_replay_walkthrough_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Replay,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.guide_replay_walkthrough_button))
              }
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
              Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                  Text(
                    text = stringResource(R.string.settings_reduce_motion_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Text(
                    text = stringResource(R.string.settings_reduce_motion_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = summary.preferences.reduceGuideAnimations,
                  onCheckedChange = onToggleReduceMotion,
                  modifier =
                    Modifier.minimumInteractiveComponentSize()
                      .testTag("settings_reduce_motion_switch"),
                )
              }
            }
          }

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

              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

              MizanExpandableSection(
                title = stringResource(R.string.disclosure_app_scope_info),
                testTag = "settings_privacy_details_toggle",
              ) {
                Text(
                  text = stringResource(R.string.settings_privacy_body),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = stringResource(R.string.status_not_active_notice_body),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DatasetMetadataRow(
  item: DatasetManagementItem,
  isBusy: Boolean,
  onRollback: () -> Unit,
) {
  val dataset = item.metadata
  val status = item.availabilityStatus
  val (icon, label, containerColor, contentColor) =
    when (status) {
      DatasetAvailabilityStatus.AVAILABLE ->
        Quadruple(
          Icons.Outlined.CheckCircle,
          stringResource(R.string.settings_dataset_status_available),
          MaterialTheme.colorScheme.primaryContainer,
          MaterialTheme.colorScheme.onPrimaryContainer,
        )
      DatasetAvailabilityStatus.UPDATING ->
        Quadruple(
          Icons.Outlined.Sync,
          stringResource(R.string.settings_dataset_status_updating),
          MaterialTheme.colorScheme.secondaryContainer,
          MaterialTheme.colorScheme.onSecondaryContainer,
        )
      DatasetAvailabilityStatus.OUTDATED ->
        Quadruple(
          Icons.Outlined.Schedule,
          stringResource(R.string.settings_dataset_status_outdated),
          MaterialTheme.colorScheme.tertiaryContainer,
          MaterialTheme.colorScheme.onTertiaryContainer,
        )
      DatasetAvailabilityStatus.UNAVAILABLE ->
        Quadruple(
          Icons.Outlined.CloudOff,
          stringResource(R.string.settings_dataset_status_unavailable),
          MaterialTheme.colorScheme.surfaceContainerHighest,
          MaterialTheme.colorScheme.onSurfaceVariant,
        )
      DatasetAvailabilityStatus.FAILED ->
        Quadruple(
          Icons.Outlined.ErrorOutline,
          stringResource(R.string.settings_dataset_status_failed),
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
        )
    }

  val categoryLabel =
    when (dataset.category) {
      DomainCategory.ADULT_CONTENT -> "Adult Content"
      DomainCategory.SOCIAL_MEDIA -> "Social Media"
      DomainCategory.CUSTOM -> "Custom"
    }

  Column(
    modifier = Modifier.fillMaxWidth().testTag("settings_dataset_${dataset.datasetId}"),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = categoryLabel,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).padding(end = 8.dp),
      )
      MizanStatusBadge(
        icon = icon,
        label = label,
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = Modifier.testTag("settings_dataset_status_${dataset.datasetId}"),
      )
    }

    val updatedSuffix =
      dataset.updatedAtEpochMillis?.let { millis ->
        val formatted = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
        " · Updated $formatted"
      } ?: ""
    Text(
      text =
        "v${dataset.versionCode} · ${dataset.recordCount} entries · ${dataset.sourceName}$updatedSuffix",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!dataset.lastError.isNullOrBlank()) {
      val fallbackPrefix =
        if (dataset.isUsable) {
          "Using cached v${dataset.versionCode} (${dataset.recordCount} domains). Last update issue: "
        } else {
          ""
        }
      Text(
        text = "$fallbackPrefix${dataset.lastError}",
        style = MaterialTheme.typography.bodySmall,
        color =
          if (status == DatasetAvailabilityStatus.FAILED) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }

    val previousVersion = dataset.previousVersionCode
    if (previousVersion != null) {
      OutlinedButton(
        onClick = onRollback,
        enabled = !isBusy,
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("settings_dataset_rollback_${dataset.datasetId}"),
      ) {
        Text(text = stringResource(R.string.settings_dataset_rollback_format, previousVersion))
      }
    }
  }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun readBoundedDocumentBytes(
  contentResolver: ContentResolver,
  uri: Uri,
  maxAllowedBytes: Int,
): ByteArray {
  val stream =
    contentResolver.openInputStream(uri)
      ?: throw IllegalStateException("Selected document could not be opened.")
  return stream.use { input ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
      total += read
      if (total > maxAllowedBytes) {
        throw IllegalStateException(
          "Selected dataset file exceeds ${maxAllowedBytes / (1024 * 1024)} MB limit."
        )
      }
      output.write(buffer, 0, read)
    }
    output.toByteArray()
  }
}
