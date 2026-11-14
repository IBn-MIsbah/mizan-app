package com.example.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * Compact contextual help trigger button adhering to >= 48dp minimum touch targets.
 */
@Composable
fun ContextualHelpButton(
  onClick: () -> Unit,
  contentDescription: String,
  modifier: Modifier = Modifier,
  label: String? = null,
  testTag: String = "contextual_help_button",
) {
  if (label != null) {
    TextButton(
      onClick = onClick,
      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
      modifier = modifier.minimumInteractiveComponentSize().testTag(testTag),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
        contentDescription = contentDescription,
        modifier = Modifier.size(18.dp).padding(end = 4.dp),
      )
      Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
  } else {
    IconButton(
      onClick = onClick,
      modifier = modifier.minimumInteractiveComponentSize().testTag(testTag),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

/**
 * Reusable ModalBottomSheet displaying contextual swipeable guide slides + optional jump
 * into the full Interactive User Guide screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextualGuideSheet(
  title: String,
  slides: List<MizanExplanationSlide>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  testTagPrefix: String = "contextual_guide_sheet",
  onOpenFullGuide: (() -> Unit)? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = modifier.testTag(testTagPrefix),
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("${testTagPrefix}_close"),
        ) {
          Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.close_label),
          )
        }
      }

      MizanSlideDeck(
        slides = slides,
        testTagPrefix = "${testTagPrefix}_deck",
        skipLabel = stringResource(R.string.close_label),
        onSkip = onDismiss,
        finalActionLabel = stringResource(R.string.close_label),
        onFinalAction = onDismiss,
      )

      if (onOpenFullGuide != null) {
        OutlinedButton(
          onClick = {
            onDismiss()
            onOpenFullGuide()
          },
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("${testTagPrefix}_open_full_guide"),
        ) {
          Text(text = stringResource(R.string.guide_open_full_button))
        }
      }
    }
  }
}
