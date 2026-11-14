package com.example.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.time.WallClockFormatting
import com.example.domain.insights.BaselineComparison
import com.example.domain.insights.DailyTrendPoint
import com.example.domain.insights.DashboardInsights
import kotlin.math.abs

@Composable
fun DashboardInsightsCard(
  insights: DashboardInsights,
  hasActiveCommitment: Boolean,
  modifier: Modifier = Modifier,
) {
  MizanSurfaceCard(modifier = modifier.testTag("dashboard_insights_card")) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.Insights,
        contentDescription = stringResource(R.string.cd_insights_icon),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
      Text(
        text = stringResource(R.string.insights_card_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    Text(
      text = stringResource(R.string.insights_card_subtitle),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      InsightStatTile(
        label = stringResource(R.string.insights_blocked_today_label),
        value = insights.todayBlockedAttempts.toString(),
        testTag = "insights_today_blocked_value",
        modifier = Modifier.weight(1f),
      )
      InsightStatTile(
        label =
          if (hasActiveCommitment) {
            stringResource(R.string.insights_blocked_active_label)
          } else {
            stringResource(R.string.insights_blocked_total_label)
          },
        value =
          if (hasActiveCommitment) {
            insights.activeCommitmentBlockedAttempts.toString()
          } else {
            insights.totalBlockedAttemptsRecorded.toString()
          },
        testTag = "insights_scope_blocked_value",
        modifier = Modifier.weight(1f),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      InsightStatTile(
        label = stringResource(R.string.insights_weekly_total_label),
        value = WallClockFormatting.formatDuration(insights.weeklyTotalUsage),
        testTag = "insights_weekly_total_value",
        modifier = Modifier.weight(1f),
      )
      InsightStatTile(
        label = stringResource(R.string.insights_streak_label),
        value = insights.consecutiveDaysWithinAllowance?.let { "${it}d" } ?: "—",
        testTag = "insights_streak_value",
        modifier = Modifier.weight(1f),
      )
    }

    val historySummaryText =
      "Commitments recorded: ${insights.totalCommitmentsRecorded} · " +
        "Completed: ${insights.completedCommitmentsCount} · " +
        "Emergency ended: ${insights.emergencyTerminatedCount}"
    Text(
      text = historySummaryText,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.testTag("insights_commitments_summary"),
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Text(
      text = stringResource(R.string.insights_weekly_trend_title),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )

    WeeklyTrendBarChart(
      points = insights.weeklyTrend,
      modifier = Modifier.fillMaxWidth().testTag("insights_weekly_trend_chart"),
    )

    val averageText =
      insights.weeklyAverageDailyUsage?.let { avg ->
        "Average across recorded days this week: ${WallClockFormatting.formatDuration(avg)}/day."
      } ?: stringResource(R.string.insights_weekly_no_usage)
    Text(
      text = averageText,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.testTag("insights_weekly_average_text"),
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Text(
      text = stringResource(R.string.insights_baseline_title),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )

    val baselineText =
      when (val comparison = insights.baselineComparison) {
        is BaselineComparison.InsufficientData ->
          stringResource(
            R.string.insights_baseline_insufficient_format,
            comparison.recordedDaysCount,
            comparison.requiredDaysCount,
          )
        is BaselineComparison.Available -> {
          val recentText = WallClockFormatting.formatDuration(comparison.recentAverageDaily)
          val baselineTextValue =
            WallClockFormatting.formatDuration(comparison.priorBaselineAverageDaily)
          val deltaText =
            when {
              comparison.deltaMinutes < 0 -> "${abs(comparison.deltaMinutes)}m/day lower"
              comparison.deltaMinutes > 0 -> "${comparison.deltaMinutes}m/day higher"
              else -> "same daily average"
            }
          "Recent ${comparison.recentDaysCount}d average ($recentText/day) vs prior " +
            "${comparison.baselineDaysCount}d baseline ($baselineTextValue/day): $deltaText."
        }
      }

    Text(
      text = baselineText,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.testTag("insights_baseline_text"),
    )
  }
}

@Composable
private fun InsightStatTile(
  label: String,
  value: String,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(testTag),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun WeeklyTrendBarChart(
  points: List<DailyTrendPoint>,
  modifier: Modifier = Modifier,
) {
  val maxMinutes =
    points
      .maxOfOrNull { maxOf(it.consumed.inWholeMinutes, it.allowance?.inWholeMinutes ?: 0L) }
      ?.coerceAtLeast(15L) ?: 30L

  Row(
    modifier = modifier.height(104.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    points.forEachIndexed { index, point ->
      val minutes = point.consumed.inWholeMinutes
      val ratio = (minutes.toFloat() / maxMinutes.toFloat()).coerceIn(0f, 1f)
      val barColor =
        when {
          point.withinAllowance == false -> MaterialTheme.colorScheme.error
          point.coverageStatus != null -> MaterialTheme.colorScheme.primary
          else -> MaterialTheme.colorScheme.outlineVariant
        }
      val dayShort = WallClockFormatting.formatShortDay(point.date.dayOfWeek())
      val a11yDesc =
        "$dayShort: ${WallClockFormatting.formatDuration(point.consumed)}, ${point.blockedAttempts} blocked attempts"

      Column(
        modifier =
          Modifier.weight(1f)
            .semantics(mergeDescendants = true) { contentDescription = a11yDesc }
            .testTag("insights_trend_day_$index"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
      ) {
        Text(
          text = if (minutes > 0L) "${minutes}m" else "0m",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .weight(1f)
              .padding(vertical = 4.dp),
          contentAlignment = Alignment.BottomCenter,
        ) {
          val reducedMotion = LocalMizanReducedMotion.current
          val targetFraction = if (minutes == 0L) 0.08f else ratio.coerceAtLeast(0.14f)
          val heightFraction by
            animateFloatAsState(
              targetValue = targetFraction,
              animationSpec = tween(durationMillis = if (reducedMotion) 0 else 360),
              label = "trend_bar_height_$index",
            )
          Box(
            modifier =
              Modifier.fillMaxWidth(0.65f)
                .height((64f * heightFraction).dp)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(barColor)
          )
        }
        Text(
          text = dayShort.take(2),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }
    }
  }
}
