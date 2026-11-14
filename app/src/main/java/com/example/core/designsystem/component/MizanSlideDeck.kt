package com.example.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import kotlinx.coroutines.launch

@Immutable
data class MizanExplanationSlide(
  val id: String,
  val heading: String,
  val explanation: String,
  val exampleTitle: String? = null,
  val exampleBody: String? = null,
  val icon: ImageVector? = null,
  val visualContent: (@Composable (isActive: Boolean, reducedMotion: Boolean) -> Unit)? = null,
)

/**
 * Reusable swipeable slide deck for multi-step explanations, onboarding walkthroughs,
 * and lengthy operational disclosures.
 */
@Composable
fun MizanSlideDeck(
  slides: List<MizanExplanationSlide>,
  modifier: Modifier = Modifier,
  testTagPrefix: String = "slide_deck",
  initialPage: Int = 0,
  skipLabel: String? = null,
  onSkip: (() -> Unit)? = null,
  finalActionLabel: String? = null,
  finalActionEnabled: Boolean = true,
  onFinalAction: (() -> Unit)? = null,
  compactVisualPadding: Boolean = false,
) {
  if (slides.isEmpty()) return

  val reducedMotion = LocalMizanReducedMotion.current
  val safeInitial = initialPage.coerceIn(0, slides.lastIndex)
  val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { slides.size })
  val coroutineScope = rememberCoroutineScope()
  val isFirst = pagerState.currentPage == 0
  val isLast = pagerState.currentPage == slides.lastIndex

  fun goToPage(target: Int) {
    val bounded = target.coerceIn(0, slides.lastIndex)
    coroutineScope.launch {
      if (reducedMotion) {
        pagerState.scrollToPage(bounded)
      } else {
        pagerState.animateScrollToPage(bounded)
      }
    }
  }

  MizanSurfaceCard(modifier = modifier.testTag(testTagPrefix)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = stringResource(R.string.guide_slide_counter_format, pagerState.currentPage + 1, slides.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("${testTagPrefix}_counter"),
      )
      if (onSkip != null && skipLabel != null) {
        TextButton(
          onClick = onSkip,
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("${testTagPrefix}_skip_button"),
        ) {
          Text(text = skipLabel)
        }
      }
    }

    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxWidth().testTag("${testTagPrefix}_pager"),
      beyondViewportPageCount = 1,
    ) { pageIndex ->
      val slide = slides[pageIndex]
      val isActive = pagerState.currentPage == pageIndex

      Column(
        modifier = Modifier.fillMaxWidth().testTag("${testTagPrefix}_slide_${slide.id}"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (slide.icon != null) {
            Surface(
              modifier = Modifier.size(36.dp),
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = slide.icon,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          }
          Text(
            text = slide.heading,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("${testTagPrefix}_heading_${slide.id}"),
          )
        }

        if (slide.visualContent != null) {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border =
              BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
          ) {
            Box(
              modifier =
                Modifier.fillMaxWidth()
                  .padding(if (compactVisualPadding) 10.dp else 14.dp)
            ) {
              slide.visualContent.invoke(isActive, reducedMotion)
            }
          }
        }

        Text(
          text = slide.explanation,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("${testTagPrefix}_body_${slide.id}"),
        )

        if (!slide.exampleBody.isNullOrBlank()) {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
              )
              Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                  text = slide.exampleTitle ?: stringResource(R.string.guide_example_label),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = slide.exampleBody,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }

    if (slides.size > 1) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .semantics {
              contentDescription =
                "Slide ${pagerState.currentPage + 1} of ${slides.size}"
            }
            .testTag("${testTagPrefix}_dots"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        repeat(slides.size) { index ->
          val active = index == pagerState.currentPage
          val width by
            animateDpAsState(
              targetValue = if (active) 22.dp else 8.dp,
              animationSpec = tween(durationMillis = if (reducedMotion) 0 else 220),
              label = "slide_dot_width",
            )
          val color by
            animateColorAsState(
              targetValue =
                if (active) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.outlineVariant
                },
              animationSpec = tween(durationMillis = if (reducedMotion) 0 else 220),
              label = "slide_dot_color",
            )
          Box(
            modifier =
              Modifier.padding(horizontal = 3.dp)
                .height(8.dp)
                .width(width)
                .clip(CircleShape)
                .background(color)
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedButton(
        onClick = { goToPage(pagerState.currentPage - 1) },
        enabled = !isFirst,
        modifier =
          Modifier.weight(1f)
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .testTag("${testTagPrefix}_back_button"),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = stringResource(R.string.back_label))
      }

      if (!isLast) {
        Button(
          onClick = { goToPage(pagerState.currentPage + 1) },
          modifier =
            Modifier.weight(1f)
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("${testTagPrefix}_next_button"),
        ) {
          Text(text = stringResource(R.string.guide_next_label))
          Spacer(modifier = Modifier.width(6.dp))
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        }
      } else if (onFinalAction != null && finalActionLabel != null) {
        Button(
          onClick = onFinalAction,
          enabled = finalActionEnabled,
          modifier =
            Modifier.weight(1f)
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("${testTagPrefix}_done_button"),
        ) {
          Text(text = finalActionLabel)
        }
      } else {
        OutlinedButton(
          onClick = { goToPage(0) },
          modifier =
            Modifier.weight(1f)
              .minimumInteractiveComponentSize()
              .heightIn(min = 48.dp)
              .testTag("${testTagPrefix}_replay_button"),
        ) {
          Text(text = stringResource(R.string.guide_replay_slides_label))
        }
      }
    }
  }
}
