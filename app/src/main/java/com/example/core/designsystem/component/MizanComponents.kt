package com.example.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun MizanBrandHeader(modifier: Modifier = Modifier) {
  val gradient =
    Brush.linearGradient(
      colors =
        listOf(
          MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
          MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
        )
    )

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
  ) {
    Box(modifier = Modifier.background(gradient).padding(20.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Surface(
          modifier = Modifier.size(52.dp).clip(CircleShape),
          color = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.Balance,
              contentDescription = stringResource(R.string.cd_brand_emblem),
              modifier = Modifier.size(26.dp),
            )
          }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = stringResource(R.string.brand_title),
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = stringResource(R.string.brand_arabic),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Text(
            text = stringResource(R.string.brand_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
fun MizanSurfaceCard(
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = containerColor),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = content,
    )
  }
}

@Composable
fun MizanEmptyStateCard(
  icon: ImageVector,
  iconContentDescription: String,
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
) {
  MizanSurfaceCard(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Surface(
        modifier = Modifier.size(44.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = iconContentDescription,
            modifier = Modifier.size(22.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Text(
      text = body,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (action != null) {
      Spacer(modifier = Modifier.height(4.dp))
      action()
    }
  }
}

@Composable
fun MizanStatusBadge(
  icon: ImageVector,
  label: String,
  containerColor: Color,
  contentColor: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.semantics(mergeDescendants = true) {},
    shape = MaterialTheme.shapes.small,
    color = containerColor,
    contentColor = contentColor,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = stringResource(R.string.cd_status_indicator),
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
      )
    }
  }
}

@Composable
fun MizanScopeNoticeCard(
  title: String = stringResource(R.string.status_not_active_notice_title),
  body: String = stringResource(R.string.status_not_active_notice_body),
  modifier: Modifier = Modifier,
) {
  MizanSurfaceCard(
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.size(20.dp),
      )
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Text(
      text = body,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
fun MizanFlowTopBar(
  title: String,
  onNavigateBack: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  backTestTag: String = "flow_back_button",
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (onNavigateBack != null) {
      IconButton(
        onClick = onNavigateBack,
        modifier = Modifier.minimumInteractiveComponentSize().testTag(backTestTag),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(R.string.back_label),
        )
      }
    }
    Text(
      text = title,
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onBackground,
    )
  }
}
