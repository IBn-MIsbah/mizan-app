package com.example.feature.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.designsystem.component.MizanBrandHeader
import com.example.core.designsystem.component.MizanStatusBadge
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.time.WallClockFormatting
import com.example.domain.evaluator.RestrictionReason
import com.example.domain.model.RestrictionMode

@Composable
fun BlockingScreen(
  uiState: BlockingUiState,
  onReturnHome: () -> Unit,
  onOpenMizan: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier.fillMaxSize().testTag("blocking_screen_root"),
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.Center,
    ) {
      when (uiState) {
        is BlockingUiState.Loading,
        is BlockingUiState.ShouldDismiss -> {
          CircularProgressIndicator(modifier = Modifier.testTag("blocking_loading_indicator"))
        }

        is BlockingUiState.Blocked -> {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            item { MizanBrandHeader() }

            item {
              MizanSurfaceCard(modifier = Modifier.testTag("blocking_main_card")) {
                MizanStatusBadge(
                  icon = Icons.Filled.Lock,
                  label = stringResource(R.string.blocking_active_badge),
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.testTag("blocking_status_badge"),
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text(
                    text = uiState.appLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("blocking_app_title"),
                  )
                  Text(
                    text = stringResource(R.string.blocking_restricted_explanation, uiState.appLabel),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                val reasonText =
                  when (uiState.reason) {
                    RestrictionReason.STRICT_MODE ->
                      stringResource(R.string.blocking_reason_strict)
                    RestrictionReason.SCHEDULED_WINDOW ->
                      stringResource(R.string.blocking_reason_scheduled)
                    RestrictionReason.DAILY_ALLOWANCE_EXHAUSTED ->
                      stringResource(R.string.blocking_reason_allowance_exhausted)
                    else -> stringResource(R.string.blocking_reason_strict)
                  }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(
                    text = reasonText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("blocking_reason_text"),
                  )
                  Text(
                    text =
                      stringResource(
                        R.string.blocking_commitment_ends_format,
                        WallClockFormatting.formatDateTime(uiState.commitmentEndsAt),
                      ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }

                Text(
                  text = stringResource(R.string.blocking_neutral_reminder),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )

                Button(
                  onClick = onReturnHome,
                  modifier =
                    Modifier.fillMaxWidth()
                      .minimumInteractiveComponentSize()
                      .heightIn(min = 52.dp)
                      .testTag("blocking_return_home_button"),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                  )
                  Text(text = stringResource(R.string.blocking_return_home_button))
                }

                OutlinedButton(
                  onClick = onOpenMizan,
                  modifier =
                    Modifier.fillMaxWidth()
                      .minimumInteractiveComponentSize()
                      .heightIn(min = 48.dp)
                      .testTag("blocking_open_mizan_button"),
                ) {
                  Text(text = stringResource(R.string.blocking_open_mizan_button))
                }
              }
            }
          }
        }
      }
    }
  }
}
