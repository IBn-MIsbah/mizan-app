package com.example.feature.review

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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.designsystem.component.MizanFlowTopBar
import com.example.core.designsystem.component.MizanScopeNoticeCard
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.time.WallClockFormatting
import com.example.feature.home.RulesSummaryBlock
import kotlinx.coroutines.flow.collectLatest

@Composable
fun CommitmentReviewRoute(
  onNavigateBack: () -> Unit,
  onActivated: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CommitmentReviewViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel) {
    viewModel.effects.collectLatest { effect ->
      when (effect) {
        CommitmentReviewEffect.NavigateToDashboard -> onActivated()
      }
    }
  }

  CommitmentReviewScreen(
    uiState = uiState,
    onAgreementChanged = viewModel::setAgreementChecked,
    onActivate = viewModel::activateCommitment,
    onRetry = viewModel::loadReview,
    onNavigateBack = onNavigateBack,
    modifier = modifier,
  )
}

@Composable
fun CommitmentReviewScreen(
  uiState: CommitmentReviewUiState,
  onAgreementChanged: (Boolean) -> Unit,
  onActivate: () -> Unit,
  onRetry: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("review_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        MizanFlowTopBar(
          title = stringResource(R.string.review_headline),
          onNavigateBack = onNavigateBack,
          backTestTag = "review_back_button",
        )
      }

      item {
        Text(
          text = stringResource(R.string.review_subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      when (uiState) {
        CommitmentReviewUiState.Loading -> {
          item {
            MizanSurfaceCard {
              Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.testTag("review_loading_indicator"))
              }
            }
          }
        }

        is CommitmentReviewUiState.Error -> {
          item {
            MizanSurfaceCard(
              modifier = Modifier.testTag("review_error_card"),
              containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
              Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.minimumInteractiveComponentSize().heightIn(min = 48.dp),
              ) {
                Text(stringResource(R.string.retry_label))
              }
            }
          }
        }

        is CommitmentReviewUiState.Ready -> {
          item {
            MizanSurfaceCard(modifier = Modifier.testTag("review_duration_card")) {
              Text(
                text = stringResource(R.string.review_duration_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = stringResource(R.string.review_duration_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = "If activated now: ${WallClockFormatting.formatDateTime(uiState.previewStart)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Ends after 7 days: ${WallClockFormatting.formatDateTime(uiState.previewEnd)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("review_rules_summary_card")) {
              Text(
                text = "Configured Rules",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              RulesSummaryBlock(rules = uiState.draft.rules)
            }
          }

          item {
            MizanScopeNoticeCard(
              title = stringResource(R.string.review_limitations_title),
              body = stringResource(R.string.review_limitations_body),
            )
          }

          if (uiState.hasActiveCommitmentConflict) {
            item {
              MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                  text =
                    "An active commitment already exists. Complete or terminate it before activating another.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }

          if (uiState.activationError != null) {
            item {
              MizanSurfaceCard(
                modifier = Modifier.testTag("review_activation_error_card"),
                containerColor = MaterialTheme.colorScheme.errorContainer,
              ) {
                Text(
                  text = uiState.activationError,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("review_agreement_card")) {
              Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                  checked = uiState.agreedToExactRules,
                  onCheckedChange = onAgreementChanged,
                  modifier =
                    Modifier.minimumInteractiveComponentSize()
                      .testTag("review_agreement_checkbox"),
                )
                Text(
                  text = stringResource(R.string.review_agreement_label),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.padding(start = 6.dp),
                )
              }

              Button(
                onClick = onActivate,
                enabled =
                  uiState.agreedToExactRules &&
                    !uiState.isSubmitting &&
                    !uiState.hasActiveCommitmentConflict,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("review_activate_button"),
              ) {
                if (uiState.isSubmitting) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                  )
                } else {
                  Text(stringResource(R.string.review_activate_button))
                }
              }
            }
          }
        }
      }
    }
  }
}
