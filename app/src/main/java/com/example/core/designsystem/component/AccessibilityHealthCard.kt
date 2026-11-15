package com.example.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.platform.health.EnforcementHealthSnapshot
import com.example.platform.health.EnforcementHealthStatus

@Composable
fun AccessibilityHealthCard(
  healthSnapshot: EnforcementHealthSnapshot,
  onOpenAccessibilitySettings: () -> Unit,
  modifier: Modifier = Modifier,
  testTagPrefix: String = "health",
  settingsLaunchError: String? = null,
) {
  val (badgeIcon, badgeLabel, badgeBg, badgeFg, descriptionText) =
    when (healthSnapshot.healthStatus) {
      EnforcementHealthStatus.ACTIVE ->
        HealthPresentation(
          icon = Icons.Filled.CheckCircle,
          badgeLabel = stringResource(R.string.health_badge_active),
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          description = stringResource(R.string.health_desc_active),
        )
      EnforcementHealthStatus.ENABLED_SERVICE_DISCONNECTED ->
        HealthPresentation(
          icon = Icons.Filled.Warning,
          badgeLabel = stringResource(R.string.health_badge_disconnected),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          description = stringResource(R.string.health_desc_disconnected),
        )
      EnforcementHealthStatus.DISABLED ->
        HealthPresentation(
          icon = Icons.Filled.PauseCircleOutline,
          badgeLabel = stringResource(R.string.health_badge_disabled),
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          description = stringResource(R.string.health_desc_disabled),
        )
      EnforcementHealthStatus.UNAVAILABLE ->
        HealthPresentation(
          icon = Icons.Filled.ErrorOutline,
          badgeLabel = stringResource(R.string.health_badge_unavailable),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          description = stringResource(R.string.health_desc_unavailable),
        )
    }

  MizanSurfaceCard(modifier = modifier.testTag("${testTagPrefix}_card")) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.Security,
        contentDescription = stringResource(R.string.cd_permissions_icon),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Text(
        text = stringResource(R.string.health_card_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    MizanStatusBadge(
      icon = badgeIcon,
      label = badgeLabel,
      containerColor = badgeBg,
      contentColor = badgeFg,
      modifier = Modifier.testTag("${testTagPrefix}_status_badge"),
    )

    Text(
      text = descriptionText,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.testTag("${testTagPrefix}_status_description"),
    )

    if (settingsLaunchError != null) {
      Text(
        text = settingsLaunchError,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("${testTagPrefix}_settings_error_text"),
      )
    }

    OutlinedButton(
      onClick = onOpenAccessibilitySettings,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("${testTagPrefix}_open_accessibility_settings_button"),
    ) {
      Text(text = stringResource(R.string.health_open_settings_button))
    }

    MizanExpandableSection(
      title = stringResource(R.string.disclosure_show_details),
      testTag = "${testTagPrefix}_details_toggle",
    ) {
      Text(
        text = stringResource(R.string.health_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private data class HealthPresentation(
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
  val badgeLabel: String,
  val containerColor: androidx.compose.ui.graphics.Color,
  val contentColor: androidx.compose.ui.graphics.Color,
  val description: String,
)
