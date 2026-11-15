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
import androidx.compose.material.icons.outlined.QueryStats
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
import com.example.platform.health.UsageAccessPermissionStatus
import com.example.platform.health.UsageAccessSnapshot

@Composable
fun UsageAccessCard(
  usageAccessSnapshot: UsageAccessSnapshot,
  onOpenUsageAccessSettings: () -> Unit,
  modifier: Modifier = Modifier,
  testTagPrefix: String = "usage_access",
  settingsLaunchError: String? = null,
) {
  val presentation =
    when (usageAccessSnapshot.permissionStatus) {
      UsageAccessPermissionStatus.ENABLED ->
        UsageAccessPresentation(
          icon = Icons.Filled.CheckCircle,
          badgeLabel = stringResource(R.string.usage_access_badge_enabled),
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          description = stringResource(R.string.usage_access_desc_enabled),
        )
      UsageAccessPermissionStatus.DISABLED ->
        UsageAccessPresentation(
          icon = Icons.Filled.PauseCircleOutline,
          badgeLabel = stringResource(R.string.usage_access_badge_disabled),
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          description = stringResource(R.string.usage_access_desc_disabled),
        )
      UsageAccessPermissionStatus.UNAVAILABLE ->
        UsageAccessPresentation(
          icon = Icons.Filled.ErrorOutline,
          badgeLabel = stringResource(R.string.usage_access_badge_unavailable),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          description = stringResource(R.string.usage_access_desc_unavailable),
        )
    }

  MizanSurfaceCard(modifier = modifier.testTag("${testTagPrefix}_card")) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.QueryStats,
        contentDescription = stringResource(R.string.cd_permissions_icon),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Text(
        text = stringResource(R.string.usage_access_card_title),
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

    Text(
      text = presentation.description,
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
      onClick = onOpenUsageAccessSettings,
      modifier =
        Modifier.fillMaxWidth()
          .minimumInteractiveComponentSize()
          .heightIn(min = 48.dp)
          .testTag("${testTagPrefix}_open_usage_settings_button"),
    ) {
      Text(text = stringResource(R.string.usage_access_open_settings_button))
    }

    MizanExpandableSection(
      title = stringResource(R.string.disclosure_show_details),
      testTag = "${testTagPrefix}_details_toggle",
    ) {
      Text(
        text = stringResource(R.string.usage_access_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private data class UsageAccessPresentation(
  val icon: ImageVector,
  val badgeLabel: String,
  val containerColor: Color,
  val contentColor: Color,
  val description: String,
)
