package com.example.feature.commitments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.designsystem.component.MizanStatusBadge
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.domain.model.RestrictionMode
import com.example.feature.home.RulesSummaryBlock
import kotlinx.coroutines.flow.collectLatest

@Composable
fun CommitmentsRoute(
  onNavigateToReview: (String) -> Unit,
  onNavigateToDashboard: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CommitmentsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel) {
    viewModel.effects.collectLatest { effect ->
      when (effect) {
        is CommitmentSetupEffect.NavigateToReview -> onNavigateToReview(effect.commitmentId)
      }
    }
  }

  CommitmentsScreen(
    uiState = uiState,
    viewModel = viewModel,
    onNavigateToDashboard = onNavigateToDashboard,
    modifier = modifier,
  )
}

@Composable
fun CommitmentsScreen(
  uiState: CommitmentSetupUiState,
  viewModel: CommitmentsViewModel,
  onNavigateToDashboard: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("commitments_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = stringResource(R.string.setup_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
          )
          Text(
            text = stringResource(R.string.setup_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      when (uiState) {
        CommitmentSetupUiState.Loading -> {
          item {
            MizanSurfaceCard {
              Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.testTag("setup_loading_indicator"))
              }
            }
          }
        }

        is CommitmentSetupUiState.Error -> {
          item {
            MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
              Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }

        is CommitmentSetupUiState.ActiveLocked -> {
          item {
            MizanSurfaceCard(modifier = Modifier.testTag("setup_locked_card")) {
              MizanStatusBadge(
                icon = Icons.Filled.Lock,
                label = stringResource(R.string.setup_locked_title),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Text(
                text = stringResource(R.string.setup_locked_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              RulesSummaryBlock(rules = uiState.activeCommitment.rules)
              Button(
                onClick = onNavigateToDashboard,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("setup_locked_back_dashboard_button"),
              ) {
                Text(stringResource(R.string.setup_go_dashboard))
              }
            }
          }
        }

        is CommitmentSetupUiState.Editing -> {
          val editor = uiState.editor

          item {
            ModeSelectionCard(
              selectedMode = editor.mode,
              onSelectMode = viewModel::selectMode,
            )
          }

          item {
            RestrictedAppsSelectionCard(
              editor = editor,
              onSearchChange = viewModel::onRestrictedSearchChange,
              onToggleInstalledApp = viewModel::toggleInstalledRestrictedApp,
              onCustomLabelChange = viewModel::onCustomRestrictedLabelChange,
              onCustomPackageChange = viewModel::onCustomRestrictedPackageChange,
              onAddCustom = viewModel::addCustomRestrictedApp,
              onRemoveApp = viewModel::removeRestrictedApp,
            )
          }

          item {
            AllowlistSelectionCard(
              editor = editor,
              onSearchChange = viewModel::onAllowlistSearchChange,
              onToggleInstalledApp = viewModel::toggleInstalledAllowedApp,
              onCustomLabelChange = viewModel::onCustomAllowedLabelChange,
              onCustomPackageChange = viewModel::onCustomAllowedPackageChange,
              onCategoryChange = viewModel::onCustomAllowedCategoryChange,
              onAddCustom = viewModel::addCustomAllowedApp,
              onRemoveApp = viewModel::removeAllowedApp,
            )
          }

          if (editor.mode != RestrictionMode.STRICT) {
            item {
              ScheduleBuilderCard(
                editor = editor,
                onToggleDay = viewModel::toggleScheduleDay,
                onAdjustStart = viewModel::adjustWindowStart,
                onAdjustEnd = viewModel::adjustWindowEnd,
                onAddWindow = viewModel::addScheduleWindow,
                onRemoveWindow = viewModel::removeScheduleWindow,
              )
            }
          }

          if (editor.mode == RestrictionMode.HYBRID) {
            item {
              AllowanceSelectionCard(
                allowanceMinutes = editor.dailyAllowanceMinutes,
                onSetAllowance = viewModel::setDailyAllowanceMinutes,
                onAdjustAllowance = viewModel::adjustDailyAllowance,
              )
            }
          }

          item {
            WebsiteFilteringSetupCard(
              editor = editor,
              onToggleEnabled = viewModel::setWebsiteFilteringEnabled,
              onDomainInputChange = viewModel::onCustomDomainInputChange,
              onAddBlockedDomain = {
                viewModel.addCustomDomainRule(com.example.domain.model.DomainRuleType.BLOCK)
              },
              onAddAllowedDomain = {
                viewModel.addCustomDomainRule(com.example.domain.model.DomainRuleType.ALLOW)
              },
              onRemoveDomain = viewModel::removeDomainRule,
              onToggleCategory = viewModel::toggleDomainCategory,
            )
          }

          item {
            AccountabilityPinSetupCard(
              editor = editor,
              onToggleRequirePin = viewModel::setRequirePinForEmergencyTermination,
            )
          }

          if (editor.validationMessages.isNotEmpty()) {
            item {
              MizanSurfaceCard(
                modifier = Modifier.testTag("setup_validation_error_card"),
                containerColor = MaterialTheme.colorScheme.errorContainer,
              ) {
                editor.validationMessages.forEach { message ->
                  Text(
                    text = "• $message",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                  )
                }
              }
            }
          }

          if (editor.statusMessage != null) {
            item {
              MizanSurfaceCard(
                modifier = Modifier.testTag("setup_status_message_card"),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Text(
                  text = editor.statusMessage,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }

          item {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              OutlinedButton(
                onClick = viewModel::saveDraft,
                enabled = !editor.isSaving,
                modifier =
                  Modifier.weight(1f)
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("setup_save_draft_button"),
              ) {
                Text(stringResource(R.string.setup_save_draft_button))
              }
              Button(
                onClick = viewModel::continueToReview,
                enabled = !editor.isSaving,
                modifier =
                  Modifier.weight(1f)
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("setup_continue_review_button"),
              ) {
                Text(stringResource(R.string.setup_continue_review_button))
              }
            }
          }
        }
      }
    }
  }
}
