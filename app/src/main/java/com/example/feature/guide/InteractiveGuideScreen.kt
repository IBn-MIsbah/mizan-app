package com.example.feature.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.designsystem.component.MizanFlowTopBar
import com.example.core.designsystem.component.MizanSlideDeck
import com.example.core.designsystem.component.MizanStatusBadge
import com.example.core.designsystem.component.MizanSurfaceCard

@Composable
fun InteractiveGuideRoute(
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: GuideViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  InteractiveGuideScreen(
    uiState = uiState,
    onNavigateBack = onNavigateBack,
    onSelectCategory = viewModel::selectCategory,
    onSelectTopic = viewModel::selectTopic,
    onNextTopic = viewModel::nextTopic,
    onPreviousTopic = viewModel::previousTopic,
    onCompleteWalkthrough = {
      viewModel.completeWalkthrough()
      onNavigateBack()
    },
    onSkipWalkthrough = {
      viewModel.skipWalkthrough()
      onNavigateBack()
    },
    onReplayWalkthrough = viewModel::replayWalkthrough,
    onToggleReduceMotion = viewModel::setReduceMotion,
    modifier = modifier,
  )
}

@Composable
fun InteractiveGuideScreen(
  uiState: GuideUiState,
  onNavigateBack: () -> Unit,
  onSelectCategory: (GuideCategory?) -> Unit,
  onSelectTopic: (String) -> Unit,
  onNextTopic: () -> Unit,
  onPreviousTopic: () -> Unit,
  onCompleteWalkthrough: () -> Unit,
  onSkipWalkthrough: () -> Unit,
  onReplayWalkthrough: () -> Unit,
  onToggleReduceMotion: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val visibleTopics = uiState.visibleTopics
  val activeTopic = uiState.activeTopic
  val activeIndex = visibleTopics.indexOfFirst { it.id == activeTopic.id }.coerceAtLeast(0)
  val isFirstTopic = activeIndex == 0
  val isLastTopic = activeIndex == visibleTopics.lastIndex

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag("guide_screen_list"),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        MizanFlowTopBar(
          title = stringResource(R.string.guide_title),
          onNavigateBack = onNavigateBack,
          backTestTag = "guide_back_button",
        )
      }

      item {
        Text(
          text = stringResource(R.string.guide_subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      item {
        MizanSurfaceCard(
          modifier = Modifier.testTag("guide_progress_card"),
          containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            MizanStatusBadge(
              icon = Icons.AutoMirrored.Outlined.MenuBook,
              label =
                stringResource(
                  R.string.guide_topic_counter_format,
                  activeIndex + 1,
                  visibleTopics.size,
                ),
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.testTag("guide_topic_counter"),
            )
            if (uiState.walkthroughMode) {
              TextButton(
                onClick = onSkipWalkthrough,
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("guide_skip_walkthrough_button"),
              ) {
                Text(stringResource(R.string.guide_walkthrough_skip_button))
              }
            } else {
              TextButton(
                onClick = onReplayWalkthrough,
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("guide_replay_walkthrough_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Replay,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.guide_replay_walkthrough_button))
              }
            }
          }

          val progressFraction = (activeIndex + 1).toFloat() / visibleTopics.size.toFloat()
          LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
          )

          Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = stringResource(R.string.settings_reduce_motion_label),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(
              checked = uiState.reduceGuideAnimations,
              onCheckedChange = onToggleReduceMotion,
              modifier =
                Modifier.minimumInteractiveComponentSize().testTag("guide_reduce_motion_switch"),
            )
          }
        }
      }

      item {
        LazyRow(
          modifier = Modifier.fillMaxWidth().testTag("guide_category_row"),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item {
            FilterChip(
              selected = uiState.selectedCategory == null && !uiState.walkthroughMode,
              onClick = { onSelectCategory(null) },
              label = { Text("All Topics (17)") },
              modifier =
                Modifier.minimumInteractiveComponentSize().testTag("guide_category_all"),
            )
          }
          items(GuideCategory.entries, key = { it.name }) { category ->
            FilterChip(
              selected = uiState.selectedCategory == category && !uiState.walkthroughMode,
              onClick = { onSelectCategory(category) },
              label = { Text(category.title) },
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .testTag("guide_category_${category.name.lowercase()}"),
            )
          }
        }
      }

      item {
        LazyRow(
          modifier = Modifier.fillMaxWidth().testTag("guide_topic_selector_row"),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(visibleTopics, key = { it.id }) { topic ->
            FilterChip(
              selected = topic.id == activeTopic.id,
              onClick = { onSelectTopic(topic.id) },
              label = { Text("${topic.stepNumber}. ${topic.title}") },
              modifier =
                Modifier.minimumInteractiveComponentSize().testTag("guide_topic_chip_${topic.id}"),
            )
          }
        }
      }

      item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "${activeTopic.stepNumber}. ${activeTopic.title}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag("guide_active_topic_title"),
          )
          Text(
            text = activeTopic.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      item(key = activeTopic.id) {
        MizanSlideDeck(
          slides = activeTopic.slides,
          testTagPrefix = "guide_topic_deck_${activeTopic.id}",
        )
      }

      activeTopic.actionDetail?.let { detail ->
        item {
          ActionBreakdownCard(detail = detail)
        }
      }

      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          OutlinedButton(
            onClick = onPreviousTopic,
            enabled = !isFirstTopic,
            modifier =
              Modifier.weight(1f)
                .minimumInteractiveComponentSize()
                .heightIn(min = 48.dp)
                .testTag("guide_prev_topic_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.guide_prev_topic_label))
          }

          if (!isLastTopic) {
            Button(
              onClick = onNextTopic,
              modifier =
                Modifier.weight(1f)
                  .minimumInteractiveComponentSize()
                  .heightIn(min = 48.dp)
                  .testTag("guide_next_topic_button"),
            ) {
              Text(stringResource(R.string.guide_next_topic_label))
              Spacer(modifier = Modifier.width(6.dp))
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            }
          } else {
            Button(
              onClick = onCompleteWalkthrough,
              modifier =
                Modifier.weight(1f)
                  .minimumInteractiveComponentSize()
                  .heightIn(min = 48.dp)
                  .testTag("guide_finish_walkthrough_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.guide_walkthrough_complete_button))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ActionBreakdownCard(detail: GuideActionDetail) {
  MizanSurfaceCard(modifier = Modifier.testTag("guide_action_breakdown_card")) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.TouchApp,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
      Text(
        text = stringResource(R.string.guide_action_card_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    ActionBreakdownRow(
      label = stringResource(R.string.guide_action_ui_label),
      value = detail.actionUiLabel,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ActionBreakdownRow(
      label = stringResource(R.string.guide_action_does_label),
      value = detail.whatItDoes,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ActionBreakdownRow(
      label = stringResource(R.string.guide_action_consequence_label),
      value = detail.consequence,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ActionBreakdownRow(
      label = stringResource(R.string.guide_action_expect_label),
      value = detail.whatToExpect,
    )
  }
}

@Composable
private fun ActionBreakdownRow(label: String, value: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
