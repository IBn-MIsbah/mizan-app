package com.example.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.platform.vpn.VpnFilterSnapshot
import com.example.platform.vpn.VpnServiceLifecycleStatus

@Composable
fun VpnFilterHealthCard(
  vpnSnapshot: VpnFilterSnapshot,
  onConsentChanged: (Boolean) -> Unit,
  onEnableAndStartVpn: () -> Unit,
  onStopVpn: () -> Unit,
  modifier: Modifier = Modifier,
  testTagPrefix: String = "vpn_filter",
  actionFeedback: String? = null,
  usePrimaryActionButton: Boolean = true,
) {
  val presentation =
    when (vpnSnapshot.status) {
      VpnServiceLifecycleStatus.NOT_CONFIGURED ->
        VpnPresentation(
          icon = Icons.Filled.PauseCircleOutline,
          badgeLabel = stringResource(R.string.vpn_badge_not_configured),
          containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          description = stringResource(R.string.vpn_desc_not_configured),
        )
      VpnServiceLifecycleStatus.PERMISSION_REQUIRED ->
        VpnPresentation(
          icon = Icons.Filled.Warning,
          badgeLabel = stringResource(R.string.vpn_badge_permission_required),
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          description = stringResource(R.string.vpn_desc_permission_required),
        )
      VpnServiceLifecycleStatus.PERMISSION_DENIED ->
        VpnPresentation(
          icon = Icons.Filled.ErrorOutline,
          badgeLabel = stringResource(R.string.vpn_badge_permission_denied),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          description = stringResource(R.string.vpn_desc_permission_denied),
        )
      VpnServiceLifecycleStatus.READY_INACTIVE ->
        VpnPresentation(
          icon = Icons.Filled.PauseCircleOutline,
          badgeLabel = stringResource(R.string.vpn_badge_ready_inactive),
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          description = stringResource(R.string.vpn_desc_ready_inactive),
        )
      VpnServiceLifecycleStatus.STARTING ->
        VpnPresentation(
          icon = Icons.Filled.VpnLock,
          badgeLabel = stringResource(R.string.vpn_badge_starting),
          containerColor = MaterialTheme.colorScheme.tertiaryContainer,
          contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
          description = stringResource(R.string.vpn_desc_starting),
        )
      VpnServiceLifecycleStatus.ACTIVE ->
        VpnPresentation(
          icon = Icons.Filled.CheckCircle,
          badgeLabel = stringResource(R.string.vpn_badge_active),
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          description = stringResource(R.string.vpn_desc_active),
        )
      VpnServiceLifecycleStatus.STOPPED_BY_USER ->
        VpnPresentation(
          icon = Icons.Filled.PauseCircleOutline,
          badgeLabel = stringResource(R.string.vpn_badge_stopped_by_user),
          containerColor = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          description = stringResource(R.string.vpn_desc_stopped_by_user),
        )
      VpnServiceLifecycleStatus.REVOKED ->
        VpnPresentation(
          icon = Icons.Filled.Warning,
          badgeLabel = stringResource(R.string.vpn_badge_revoked),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          description = stringResource(R.string.vpn_desc_revoked),
        )
      VpnServiceLifecycleStatus.ANOTHER_VPN_ACTIVE ->
        VpnPresentation(
          icon = Icons.Filled.Warning,
          badgeLabel = stringResource(R.string.vpn_badge_another_vpn),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          description = stringResource(R.string.vpn_desc_another_vpn),
        )
      VpnServiceLifecycleStatus.DEGRADED_ERROR ->
        VpnPresentation(
          icon = Icons.Filled.ErrorOutline,
          badgeLabel = stringResource(R.string.vpn_badge_error),
          containerColor = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          description = vpnSnapshot.lastError ?: stringResource(R.string.vpn_desc_error),
        )
    }

  MizanSurfaceCard(modifier = modifier.testTag("${testTagPrefix}_card")) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.VpnLock,
        contentDescription = stringResource(R.string.cd_permissions_icon),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Text(
        text = stringResource(R.string.vpn_card_title),
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

    if (vpnSnapshot.hasActiveWebsiteCommitment) {
      Text(
        text = stringResource(R.string.vpn_upstream_info_format, vpnSnapshot.upstreamDnsCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (vpnSnapshot.isTunnelEstablished) {
        Text(
          text =
            stringResource(
              R.string.vpn_session_stats_format,
              vpnSnapshot.forwardedQueryCount,
              vpnSnapshot.blockedQueryCount,
              vpnSnapshot.upstreamFailureCount,
            ),
          style = MaterialTheme.typography.bodySmall,
          color =
            if (vpnSnapshot.upstreamFailureCount > 0L) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("${testTagPrefix}_session_stats"),
        )
      }
    }

    if (vpnSnapshot.datasetWarningMessage != null) {
      MizanSurfaceCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.testTag("${testTagPrefix}_dataset_warning"),
      ) {
        Text(
          text = vpnSnapshot.datasetWarningMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    }

    if (vpnSnapshot.isPrivateDnsActive && vpnSnapshot.hasActiveWebsiteCommitment) {
      MizanSurfaceCard(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.testTag("${testTagPrefix}_private_dns_warning"),
      ) {
        Text(
          text = stringResource(R.string.vpn_private_dns_warning),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
      }
    }

    if (vpnSnapshot.hasActiveWebsiteCommitment) {
      MizanExpandableSection(
        title = stringResource(R.string.disclosure_dns_limits),
        testTag = "${testTagPrefix}_limitations_toggle",
      ) {
        Text(
          text = stringResource(R.string.vpn_disclosure_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (!vpnSnapshot.isTunnelEstablished) {
        Row(
          modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(
            checked = vpnSnapshot.hasConsented,
            onCheckedChange = onConsentChanged,
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .testTag("${testTagPrefix}_consent_checkbox"),
          )
          Text(
            text = stringResource(R.string.vpn_consent_checkbox_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp),
          )
        }
      }

      if (actionFeedback != null) {
        Text(
          text = actionFeedback,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("${testTagPrefix}_feedback_text"),
        )
      }

      if (
        vpnSnapshot.status == VpnServiceLifecycleStatus.ACTIVE ||
          vpnSnapshot.status == VpnServiceLifecycleStatus.STARTING
      ) {
        OutlinedButton(
          onClick = onStopVpn,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("${testTagPrefix}_stop_button"),
        ) {
          Text(text = stringResource(R.string.vpn_stop_button))
        }
      } else if (usePrimaryActionButton) {
        Button(
          onClick = onEnableAndStartVpn,
          enabled = vpnSnapshot.hasConsented,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("${testTagPrefix}_start_button"),
        ) {
          Text(text = stringResource(R.string.vpn_enable_start_button))
        }
      } else {
        OutlinedButton(
          onClick = onEnableAndStartVpn,
          enabled = vpnSnapshot.hasConsented,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("${testTagPrefix}_start_button"),
        ) {
          Text(text = stringResource(R.string.vpn_enable_start_button))
        }
      }
    }
  }
}

private data class VpnPresentation(
  val icon: ImageVector,
  val badgeLabel: String,
  val containerColor: Color,
  val contentColor: Color,
  val description: String,
)
