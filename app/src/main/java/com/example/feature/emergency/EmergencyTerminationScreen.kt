package com.example.feature.emergency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.designsystem.component.MizanFlowTopBar
import com.example.core.designsystem.component.MizanSurfaceCard
import kotlinx.coroutines.flow.collectLatest

@Composable
fun EmergencyTerminationRoute(
  onNavigateBack: () -> Unit,
  onTerminated: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: EmergencyTerminationViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel) {
    viewModel.effects.collectLatest { effect ->
      when (effect) {
        EmergencyTerminationEffect.NavigateToDashboard -> onTerminated()
      }
    }
  }

  EmergencyTerminationScreen(
    uiState = uiState,
    onConfirmChanged = viewModel::setWarningConfirmed,
    onConfirmTermination = viewModel::confirmTermination,
    onNavigateBack = onNavigateBack,
    modifier = modifier,
  )
}

@Composable
fun EmergencyTerminationScreen(
  uiState: EmergencyTerminationUiState,
  onConfirmChanged: (Boolean) -> Unit,
  onConfirmTermination: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("emergency_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        MizanFlowTopBar(
          title = stringResource(R.string.emergency_headline),
          onNavigateBack = onNavigateBack,
          backTestTag = "emergency_back_button",
        )
      }

      item {
        Text(
          text = stringResource(R.string.emergency_subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      when (uiState) {
        EmergencyTerminationUiState.Loading -> {
          item {
            MizanSurfaceCard {
              Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.testTag("emergency_loading_indicator"))
              }
            }
          }
        }

        is EmergencyTerminationUiState.Error -> {
          item {
            MizanSurfaceCard(
              modifier = Modifier.testTag("emergency_error_card"),
              containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
              Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }

        is EmergencyTerminationUiState.BlockedByUnavailablePinVerification -> {
          item {
            MizanSurfaceCard(
              modifier = Modifier.testTag("emergency_pin_blocked_card"),
              containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Lock,
                  contentDescription = stringResource(R.string.cd_warning_icon),
                  tint = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.emergency_pin_blocked_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
              Text(
                text = stringResource(R.string.emergency_pin_blocked_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              OutlinedButton(
                onClick = onNavigateBack,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("emergency_blocked_back_button"),
              ) {
                Text(stringResource(R.string.setup_go_dashboard))
              }
            }
          }
        }

        is EmergencyTerminationUiState.Ready -> {
          item {
            MizanSurfaceCard(modifier = Modifier.testTag("emergency_consequence_card")) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Warning,
                  contentDescription = stringResource(R.string.cd_warning_icon),
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(22.dp),
                )
                Text(
                  text = stringResource(R.string.emergency_consequence_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              Text(
                text = stringResource(R.string.emergency_consequence_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          if (uiState.terminationError != null) {
            item {
              MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                  text = uiState.terminationError,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("emergency_confirm_card")) {
              Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                  checked = uiState.confirmedWarning,
                  onCheckedChange = onConfirmChanged,
                  modifier =
                    Modifier.minimumInteractiveComponentSize()
                      .testTag("emergency_confirm_checkbox"),
                )
                Text(
                  text = stringResource(R.string.emergency_confirm_checkbox_label),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.padding(start = 6.dp),
                )
              }

              Button(
                onClick = onConfirmTermination,
                enabled = uiState.confirmedWarning && !uiState.isSubmitting,
                colors =
                  ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                  ),
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("emergency_confirm_button"),
              ) {
                if (uiState.isSubmitting) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onError,
                  )
                } else {
                  Text(stringResource(R.string.emergency_confirm_button))
                }
              }
            }
          }
        }
      }
    }
  }
}
