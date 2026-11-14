package com.example.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.platform.health.BatteryOptimizationSnapshot
import com.example.platform.health.BatteryOptimizationStatus

@Composable
fun BatteryOptimizationCard(
  batterySnapshot: BatteryOptimizationSnapshot,
  onOpenBatterySettings: () -> Unit,
  modifier: Modifier = Modifier,
  testTagPrefix: String = "battery_optimization",
  settingsLaunchError: String? = null,
) {
  val presentation =
    when (batterySnapshot.status) {
      BatteryOptimizationStatus.UNRESTRICTED ->
        BatteryPresentation(
          icon = Icons.Filled.BatteryChargingFull,
          badgeLabel = stringResource(R.string.battery_badge_unrestricted),
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          description = stringResource(R.string.battery_desc_unrestricted),
        )
      BatteryOptimizationStatus.OPTIMIZED ->
        BatteryPresentation(
          icon = Icons.Filled.BatteryAlert,
          badgeLabel = stringResource(R.string.battery_badge_optimized),
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          description = stringResource(R.string.battery_desc_optimized),
        )
      BatteryOptimizationStatus.UNAVAILABLE ->
        BatteryPresentation(
          icon = Icons.Filled.ErrorOutline,
          badgeLabel = stringResource(R.string.battery_badge_unavailable),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          description = stringResource(R.string.battery_desc_unavailable),
        )
    }

  MizanSurfaceCard(modifier = modifier.testTag("${testTagPrefix}_card")) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.BatterySaver,
        contentDescription = stringResource(R.string.cd_permissions_icon),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Text(
        text = stringResource(R.string.battery_card_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    MizanStatusBadge(
      icon = presentation.icon,
      label = presentation.badgeLabel,
      containerColor = presentation.containerColor,
      contentColor = presentation.contentColor,
      modifier = Modifier.testTag("${testTagPrefix}_status_badge"),
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = presentation.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("${testTagPrefix}_status_description"),
      )
      Text(
        text = stringResource(R.string.battery_limitation_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (settingsLaunchError != null) {
      Text(
        text = settingsLaunchError,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("${testTagPrefix}_settings_error_text"),
      )
    }

    OutlinedButton(
      onClick = onOpenBatterySettings,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("${testTagPrefix}_open_settings_button"),
    ) {
      Text(text = stringResource(R.string.battery_open_settings_button))
    }
  }
}

private data class BatteryPresentation(
  val icon: ImageVector,
  val badgeLabel: String,
  val containerColor: Color,
  val contentColor: Color,
  val description: String,
)
