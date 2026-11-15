package com.example.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.designsystem.component.ContextualHelpButton
import com.example.core.designsystem.component.MizanExpandableSection
import com.example.core.designsystem.component.MizanStatusBadge
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.time.WallClockFormatting

@Composable
internal fun AccountabilityPinSettingsCard(
  pinState: PinManagementUiState,
  onCurrentPinChange: (String) -> Unit,
  onNewPinChange: (String) -> Unit,
  onSavePin: () -> Unit,
  onRemovePin: () -> Unit,
  modifier: Modifier = Modifier,
  onShowHelp: (() -> Unit)? = null,
) {
  var showRemovePinConfirmDialog by remember { mutableStateOf(false) }

  if (showRemovePinConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showRemovePinConfirmDialog = false },
      modifier = Modifier.testTag("settings_pin_remove_confirm_dialog"),
      title = { Text(stringResource(R.string.settings_pin_remove_confirm_title)) },
      text = { Text(stringResource(R.string.settings_pin_remove_confirm_body)) },
      confirmButton = {
        Button(
          onClick = {
            showRemovePinConfirmDialog = false
            onRemovePin()
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("settings_pin_remove_confirm_button"),
        ) {
          Text(stringResource(R.string.settings_pin_remove_confirm_action))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showRemovePinConfirmDialog = false },
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("settings_pin_remove_cancel_button"),
        ) {
          Text(stringResource(R.string.cancel_label))
        }
      },
    )
  }
  val (badgeIcon, badgeLabel, badgeBg, badgeFg) =
    when {
      !pinState.isPinEnabled ->
        PinBadgeStyle(
          icon = Icons.Filled.PauseCircleOutline,
          label = stringResource(R.string.settings_pin_status_disabled),
          container = MaterialTheme.colorScheme.secondaryContainer,
          content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      pinState.isCredentialAvailable ->
        PinBadgeStyle(
          icon = Icons.Filled.CheckCircle,
          label = stringResource(R.string.settings_pin_status_enabled),
          container = MaterialTheme.colorScheme.primaryContainer,
          content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      else ->
        PinBadgeStyle(
          icon = Icons.Filled.ErrorOutline,
          label = stringResource(R.string.settings_pin_status_unavailable),
          container = MaterialTheme.colorScheme.errorContainer,
          content = MaterialTheme.colorScheme.onErrorContainer,
        )
    }

  MizanSurfaceCard(modifier = modifier.testTag("settings_pin_card")) {
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
          imageVector = Icons.Outlined.Pin,
          contentDescription = stringResource(R.string.cd_privacy_icon),
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp),
        )
        Text(
          text = stringResource(R.string.settings_pin_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      if (onShowHelp != null) {
        ContextualHelpButton(
          onClick = onShowHelp,
          contentDescription = stringResource(R.string.guide_contextual_help_cd),
          testTag = "settings_pin_help_button",
        )
      }
    }

    MizanStatusBadge(
      icon = badgeIcon,
      label = badgeLabel,
      containerColor = badgeBg,
      contentColor = badgeFg,
      modifier = Modifier.testTag("settings_pin_status_badge"),
    )

    MizanExpandableSection(
      title = stringResource(R.string.disclosure_pin_details),
      testTag = "settings_pin_details_toggle",
    ) {
      Text(
        text = stringResource(R.string.settings_pin_disclosure),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (pinState.isPinEnabled) {
      OutlinedTextField(
        value = pinState.currentPinInput,
        onValueChange = onCurrentPinChange,
        label = { Text(stringResource(R.string.settings_pin_current_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().testTag("settings_pin_current_input"),
      )
    }

    OutlinedTextField(
      value = pinState.newPinInput,
      onValueChange = onNewPinChange,
      label = { Text(stringResource(R.string.settings_pin_new_label)) },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
      modifier = Modifier.fillMaxWidth().testTag("settings_pin_new_input"),
    )

    if (pinState.lockoutRemainingSeconds > 0L) {
      Text(
        text =
          stringResource(
            R.string.emergency_pin_lockout_format,
            pinState.lockoutRemainingSeconds,
          ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("settings_pin_lockout_text"),
      )
    }

    if (pinState.errorMessage != null) {
      Text(
        text = pinState.errorMessage,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("settings_pin_error_text"),
      )
    }

    if (pinState.statusMessage != null) {
      Text(
        text = pinState.statusMessage,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("settings_pin_status_message"),
      )
    }

    Button(
      onClick = onSavePin,
      enabled = !pinState.isSubmitting,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("settings_pin_save_button"),
    ) {
      Text(
        text =
          if (pinState.isPinEnabled) {
            stringResource(R.string.settings_pin_change_button)
          } else {
            stringResource(R.string.settings_pin_set_button)
          }
      )
    }

    if (pinState.isPinEnabled) {
      OutlinedButton(
        onClick = { showRemovePinConfirmDialog = true },
        enabled = !pinState.isSubmitting,
        colors =
          ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier =
          Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("settings_pin_remove_button"),
      ) {
        Text(text = stringResource(R.string.settings_pin_remove_button))
      }
    }
  }
}

@Composable
internal fun VoluntaryRemovalRequestCard(
  removalState: RemovalRequestUiState,
  onRequestRemoval: () -> Unit,
  onCancelRemoval: () -> Unit,
  onExecuteRemoval: () -> Unit,
  onOpenAppInfo: () -> Unit,
  appInfoError: String? = null,
  modifier: Modifier = Modifier,
  onShowHelp: (() -> Unit)? = null,
) {
  MizanSurfaceCard(modifier = modifier.testTag("settings_removal_card")) {
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
          imageVector = Icons.Outlined.Timer,
          contentDescription = stringResource(R.string.cd_schedule_icon),
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp),
        )
        Text(
          text = stringResource(R.string.settings_removal_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      if (onShowHelp != null) {
        ContextualHelpButton(
          onClick = onShowHelp,
          contentDescription = stringResource(R.string.guide_contextual_help_cd),
          testTag = "settings_removal_help_button",
        )
      }
    }

    val (badgeIcon, badgeLabel, badgeBg, badgeFg) =
      when (removalState.status) {
        RemovalUiStatus.NONE ->
          PinBadgeStyle(
            icon = Icons.Filled.PauseCircleOutline,
            label = "No Active Removal Request",
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        RemovalUiStatus.PENDING ->
          PinBadgeStyle(
            icon = Icons.Filled.HourglassTop,
            label = "Cooling-Off Pending (${WallClockFormatting.formatDuration(removalState.remainingDuration)} remaining)",
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        RemovalUiStatus.READY_TO_EXECUTE ->
          PinBadgeStyle(
            icon = Icons.Filled.LockOpen,
            label = "24-Hour Cooling-Off Elapsed · Ready",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        RemovalUiStatus.CLOCK_ANOMALY_BLOCKED ->
          PinBadgeStyle(
            icon = Icons.Filled.Warning,
            label = "Clock Change Detected · Waiting",
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
          )
      }

    MizanStatusBadge(
      icon = badgeIcon,
      label = badgeLabel,
      containerColor = badgeBg,
      contentColor = badgeFg,
      modifier = Modifier.testTag("settings_removal_status_badge"),
    )

    Text(
      text = stringResource(R.string.settings_removal_desc),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (removalState.requestedAt != null && removalState.unlocksAt != null) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "Requested: ${WallClockFormatting.formatDateTime(removalState.requestedAt)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("settings_removal_requested_at"),
        )
        Text(
          text = "Earliest unlock: ${WallClockFormatting.formatDateTime(removalState.unlocksAt)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("settings_removal_unlocks_at"),
        )
      }
    }

    MizanExpandableSection(
      title = stringResource(R.string.disclosure_removal_details),
      testTag = "settings_removal_details_toggle",
    ) {
      Text(
        text = stringResource(R.string.settings_removal_honest_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (removalState.errorMessage != null) {
      Text(
        text = removalState.errorMessage,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("settings_removal_error_text"),
      )
    }

    if (removalState.statusMessage != null) {
      Text(
        text = removalState.statusMessage,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("settings_removal_status_message"),
      )
    }

    if (appInfoError != null) {
      Text(
        text = appInfoError,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    when (removalState.status) {
      RemovalUiStatus.NONE -> {
        OutlinedButton(
          onClick = onRequestRemoval,
          enabled = !removalState.isSubmitting,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("settings_removal_request_button"),
        ) {
          Text(text = stringResource(R.string.settings_removal_request_button))
        }
      }
      RemovalUiStatus.PENDING,
      RemovalUiStatus.CLOCK_ANOMALY_BLOCKED -> {
        OutlinedButton(
          onClick = onCancelRemoval,
          enabled = !removalState.isSubmitting,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("settings_removal_cancel_button"),
        ) {
          Text(text = stringResource(R.string.settings_removal_cancel_button))
        }
      }
      RemovalUiStatus.READY_TO_EXECUTE -> {
        Button(
          onClick = onExecuteRemoval,
          enabled = !removalState.isSubmitting,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("settings_removal_execute_button"),
        ) {
          Text(text = stringResource(R.string.settings_removal_execute_button))
        }
        OutlinedButton(
          onClick = onCancelRemoval,
          enabled = !removalState.isSubmitting,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("settings_removal_cancel_button"),
        ) {
          Text(text = stringResource(R.string.settings_removal_cancel_button))
        }
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    OutlinedButton(
      onClick = onOpenAppInfo,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("settings_removal_open_app_info_button"),
    ) {
      Text(text = stringResource(R.string.settings_removal_open_app_info_button))
    }
  }
}

private data class PinBadgeStyle(
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
  val label: String,
  val container: androidx.compose.ui.graphics.Color,
  val content: androidx.compose.ui.graphics.Color,
)
