package com.example.feature.completion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.designsystem.component.ContextualGuideSheet
import com.example.core.designsystem.component.ContextualHelpButton
import com.example.core.designsystem.component.MizanExpandableSection
import com.example.core.designsystem.component.MizanFlowTopBar
import com.example.core.designsystem.component.MizanStatusBadge
import com.example.core.designsystem.component.MizanSurfaceCard
import com.example.core.time.WallClockFormatting
import com.example.domain.model.CompletionNextStep
import com.example.feature.guide.ContextualGuideCatalog
import com.example.feature.guide.ContextualGuideContext
import com.example.feature.home.RulesSummaryBlock
import kotlinx.coroutines.flow.collectLatest

@Composable
fun CommitmentCompletionRoute(
  onNavigateBack: () -> Unit,
  onNavigateToDashboard: () -> Unit,
  onNavigateToSetup: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenUserGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit = { _, _ -> },
  viewModel: CommitmentCompletionViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(viewModel) {
    viewModel.effects.collectLatest { effect ->
      when (effect) {
        CommitmentCompletionEffect.NavigateToDashboard -> onNavigateToDashboard()
        CommitmentCompletionEffect.NavigateToSetup -> onNavigateToSetup()
      }
    }
  }

  CommitmentCompletionScreen(
    uiState = uiState,
    onSelectNextStep = viewModel::selectNextStep,
    onNavigateBack = onNavigateBack,
    onNavigateToDashboard = onNavigateToDashboard,
    onOpenUserGuide = onOpenUserGuide,
    modifier = modifier,
  )
}

@Composable
fun CommitmentCompletionScreen(
  uiState: CommitmentCompletionUiState,
  onSelectNextStep: (CompletionNextStep) -> Unit,
  onNavigateBack: () -> Unit,
  onNavigateToDashboard: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenUserGuide: (sectionId: String?, walkthroughMode: Boolean) -> Unit = { _, _ -> },
) {
  var showCompletionGuide by remember { mutableStateOf(false) }

  if (showCompletionGuide) {
    ContextualGuideSheet(
      title = ContextualGuideCatalog.titleFor(ContextualGuideContext.COMPLETION),
      slides = ContextualGuideCatalog.slidesFor(ContextualGuideContext.COMPLETION),
      onDismiss = { showCompletionGuide = false },
      testTagPrefix = "completion_contextual_guide_sheet",
      onOpenFullGuide = {
        onOpenUserGuide(ContextualGuideCatalog.topicIdFor(ContextualGuideContext.COMPLETION), false)
      },
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("completion_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          MizanFlowTopBar(
            title = stringResource(R.string.completion_headline),
            onNavigateBack = onNavigateBack,
            backTestTag = "completion_back_button",
            modifier = Modifier.weight(1f),
          )
          ContextualHelpButton(
            onClick = { showCompletionGuide = true },
            contentDescription = stringResource(R.string.guide_contextual_help_cd),
            testTag = "completion_contextual_help_button",
          )
        }
      }

      when (uiState) {
        CommitmentCompletionUiState.Loading -> {
          item {
            MizanSurfaceCard {
              Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.testTag("completion_loading_indicator"))
              }
            }
          }
        }

        is CommitmentCompletionUiState.Error -> {
          item {
            MizanSurfaceCard(
              modifier = Modifier.testTag("completion_error_card"),
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

        is CommitmentCompletionUiState.PeriodNotYetElapsed -> {
          item {
            MizanSurfaceCard(modifier = Modifier.testTag("completion_not_elapsed_card")) {
              Text(
                text = "7-Day Period Still In Progress",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text =
                  "This commitment reaches completion at ${WallClockFormatting.formatDateTime(uiState.endsAt)}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().heightIn(min = 48.dp),
              ) {
                Text(stringResource(R.string.setup_go_dashboard))
              }
            }
          }
        }

        is CommitmentCompletionUiState.Ready -> {
          val completed = uiState.completed
          item {
            Text(
              text = stringResource(R.string.completion_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("completion_summary_card")) {
              MizanStatusBadge(
                icon = Icons.Filled.CheckCircle,
                label = stringResource(R.string.dashboard_completed_badge),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Text(
                text =
                  "Started: ${WallClockFormatting.formatDateTime(completed.activatedAt)}\n" +
                    "Completed: ${WallClockFormatting.formatDateTime(completed.completedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              MizanExpandableSection(
                title = stringResource(R.string.disclosure_active_rules),
                testTag = "completion_rules_toggle",
              ) {
                RulesSummaryBlock(rules = completed.rules)
              }
            }
          }

          if (uiState.actionError != null) {
            item {
              MizanSurfaceCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                  text = uiState.actionError,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }

          if (uiState.disciplineModeRecordedNote) {
            item {
              MizanSurfaceCard(
                modifier = Modifier.testTag("completion_discipline_recorded_card"),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Text(
                  text = stringResource(R.string.completion_discipline_recorded_note),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(
                  onClick = onNavigateToDashboard,
                  modifier =
                    Modifier.fillMaxWidth()
                      .minimumInteractiveComponentSize()
                      .heightIn(min = 48.dp)
                      .testTag("completion_return_dashboard_button"),
                ) {
                  Text(stringResource(R.string.setup_go_dashboard))
                }
              }
            }
          }

          item {
            MizanSurfaceCard(modifier = Modifier.testTag("completion_choices_card")) {
              Text(
                text = stringResource(R.string.completion_choice_finish_title),
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = stringResource(R.string.completion_choice_finish_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Button(
                onClick = { onSelectNextStep(CompletionNextStep.FINISH) },
                enabled = !uiState.isSubmitting,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("completion_finish_button"),
              ) {
                Text(stringResource(R.string.completion_choice_finish_title))
              }

              Text(
                text = stringResource(R.string.completion_choice_another_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
              )
              Text(
                text = stringResource(R.string.completion_choice_another_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              OutlinedButton(
                onClick = { onSelectNextStep(CompletionNextStep.BEGIN_ANOTHER_DETOX) },
                enabled = !uiState.isSubmitting,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("completion_begin_another_button"),
              ) {
                Text(stringResource(R.string.completion_choice_another_title))
              }

              Text(
                text = stringResource(R.string.completion_choice_discipline_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
              )
              Text(
                text = stringResource(R.string.completion_choice_discipline_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              OutlinedButton(
                onClick = { onSelectNextStep(CompletionNextStep.ENTER_DISCIPLINE_MODE) },
                enabled = !uiState.isSubmitting,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag("completion_discipline_mode_button"),
              ) {
                Text(stringResource(R.string.completion_choice_discipline_title))
              }
            }
          }
        }
      }
    }
  }
}
