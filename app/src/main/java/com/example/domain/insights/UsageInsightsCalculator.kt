package com.example.domain.insights

import com.example.domain.model.Commitment
import com.example.domain.model.DailyUsage
import com.example.domain.model.RestrictionMode
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.time.DomainDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class DailyTrendPoint(
  val date: DomainDate,
  val consumed: Duration,
  val allowance: Duration?,
  val blockedAttempts: Int,
  val coverageStatus: UsageCoverageStatus?,
  val withinAllowance: Boolean?,
)

sealed interface BaselineComparison {
  data class Available(
    val recentAverageDaily: Duration,
    val priorBaselineAverageDaily: Duration,
    val deltaMinutes: Long,
    val recentDaysCount: Int,
    val baselineDaysCount: Int,
  ) : BaselineComparison

  data class InsufficientData(
    val recordedDaysCount: Int,
    val requiredDaysCount: Int,
  ) : BaselineComparison
}

data class DashboardInsights(
  val todayUsage: Duration,
  val todayBlockedAttempts: Int,
  val activeCommitmentBlockedAttempts: Int,
  val totalBlockedAttemptsRecorded: Int,
  val completedCommitmentsCount: Int,
  val emergencyTerminatedCount: Int,
  val totalCommitmentsRecorded: Int,
  val consecutiveDaysWithinAllowance: Int?,
  val weeklyTrend: List<DailyTrendPoint>,
  val weeklyTotalUsage: Duration,
  val weeklyAverageDailyUsage: Duration?,
  val baselineComparison: BaselineComparison,
)

/**
 * Pure domain calculator producing local, factual insights from Room commitments and daily usage records.
 * Never fabricates missing days or baseline data when history is insufficient.
 */
object UsageInsightsCalculator {

  const val MIN_BASELINE_DAYS = 6
  const val TREND_WINDOW_DAYS = 7

  fun compute(
    commitments: List<Commitment>,
    recentUsages: List<DailyUsage>,
    todayDate: DomainDate,
  ): DashboardInsights {
    val active = commitments.firstOrNull { it is Commitment.Active } as? Commitment.Active
    val completedCount = commitments.count { it is Commitment.Completed }
    val terminatedCount = commitments.count { it is Commitment.EmergencyTerminated }
    val nonDraftCount = commitments.count { it !is Commitment.Draft }

    val commitmentsById = commitments.associateBy { it.id }

    val usagesByDate: Map<DomainDate, List<DailyUsage>> = recentUsages.groupBy { it.date }

    val todayRecords =
      if (active != null) {
        usagesByDate[todayDate].orEmpty().filter { it.commitmentId == active.id }
      } else {
        usagesByDate[todayDate].orEmpty()
      }
    val todayUsage = todayRecords.fold(Duration.ZERO) { acc, item -> acc + item.consumed }
    val todayBlocked = todayRecords.sumOf { it.blockedAttempts }

    val activeTotalBlocked =
      if (active != null) {
        recentUsages.filter { it.commitmentId == active.id }.sumOf { it.blockedAttempts }
      } else {
        0
      }
    val totalBlockedAll = recentUsages.sumOf { it.blockedAttempts }

    val trendDates =
      (TREND_WINDOW_DAYS - 1 downTo 0).map { daysAgo -> todayDate.minusDays(daysAgo) }

    val weeklyTrend =
      trendDates.map { date ->
        val dayRecords = usagesByDate[date].orEmpty()
        val prioritized =
          if (active != null && dayRecords.any { it.commitmentId == active.id }) {
            dayRecords.filter { it.commitmentId == active.id }
          } else {
            dayRecords
          }
        val totalDayDuration = prioritized.fold(Duration.ZERO) { acc, r -> acc + r.consumed }
        val dayBlocked = prioritized.sumOf { it.blockedAttempts }
        val matchedCommitment =
          prioritized.firstOrNull()?.let { commitmentsById[it.commitmentId] }
            ?: if (active != null && date >= active.activatedAt.date && date <= active.endsAt.date) active else null
        val dayAllowance =
          matchedCommitment?.rules?.takeIf { it.mode == RestrictionMode.HYBRID }?.dailyAllowance
        val worstCoverage =
          when {
            prioritized.isEmpty() -> null
            prioritized.any { it.coverageStatus == UsageCoverageStatus.INCOMPLETE } ->
              UsageCoverageStatus.INCOMPLETE
            prioritized.any { it.coverageStatus == UsageCoverageStatus.RECONCILED_ONLY } ->
              UsageCoverageStatus.RECONCILED_ONLY
            prioritized.any { it.coverageStatus == UsageCoverageStatus.MEASURED_AND_RECONCILED } ->
              UsageCoverageStatus.MEASURED_AND_RECONCILED
            else -> UsageCoverageStatus.MEASURED
          }
        val within =
          if (dayAllowance != null && prioritized.isNotEmpty()) {
            totalDayDuration <= dayAllowance
          } else {
            null
          }
        DailyTrendPoint(
          date = date,
          consumed = totalDayDuration,
          allowance = dayAllowance,
          blockedAttempts = dayBlocked,
          coverageStatus = worstCoverage,
          withinAllowance = within,
        )
      }

    val daysWithUsage = weeklyTrend.filter { it.coverageStatus != null }
    val weeklyTotal = weeklyTrend.fold(Duration.ZERO) { acc, point -> acc + point.consumed }
    val weeklyAverage =
      if (daysWithUsage.isNotEmpty()) {
        (weeklyTotal.inWholeMilliseconds / daysWithUsage.size).milliseconds
      } else {
        null
      }

    val consecutiveDaysWithinAllowance =
      computeConsecutiveDaysWithinAllowance(
        active = active,
        recentUsages = recentUsages,
        commitmentsById = commitmentsById,
        todayDate = todayDate,
      )

    val baselineComparison = computeBaselineComparison(recentUsages, todayDate)

    return DashboardInsights(
      todayUsage = todayUsage,
      todayBlockedAttempts = todayBlocked,
      activeCommitmentBlockedAttempts = activeTotalBlocked,
      totalBlockedAttemptsRecorded = totalBlockedAll,
      completedCommitmentsCount = completedCount,
      emergencyTerminatedCount = terminatedCount,
      totalCommitmentsRecorded = nonDraftCount,
      consecutiveDaysWithinAllowance = consecutiveDaysWithinAllowance,
      weeklyTrend = weeklyTrend,
      weeklyTotalUsage = weeklyTotal,
      weeklyAverageDailyUsage = weeklyAverage,
      baselineComparison = baselineComparison,
    )
  }

  private fun computeConsecutiveDaysWithinAllowance(
    active: Commitment.Active?,
    recentUsages: List<DailyUsage>,
    commitmentsById: Map<com.example.domain.model.CommitmentId, Commitment>,
    todayDate: DomainDate,
  ): Int? {
    val relevantHybrid =
      if (active != null) {
        if (active.rules.mode != RestrictionMode.HYBRID) return null
        recentUsages.filter { it.commitmentId == active.id }
      } else {
        recentUsages.filter {
          commitmentsById[it.commitmentId]?.rules?.mode == RestrictionMode.HYBRID
        }
      }

    if (relevantHybrid.isEmpty()) return null

    val byDate =
      relevantHybrid
        .groupBy { it.date }
        .toSortedMap(compareByDescending { it })

    var streak = 0
    var cursorDate: DomainDate? = null
    for ((date, records) in byDate) {
      if (date > todayDate) continue
      if (cursorDate != null && date != cursorDate) {
        break
      }
      val total = records.fold(Duration.ZERO) { acc, item -> acc + item.consumed }
      val allowance =
        active?.rules?.dailyAllowance
          ?: commitmentsById[records.first().commitmentId]?.rules?.dailyAllowance
          ?: break
      val hasIncomplete = records.any { it.coverageStatus == UsageCoverageStatus.INCOMPLETE }
      if (hasIncomplete || total > allowance) {
        break
      }
      streak += 1
      cursorDate = date.minusDays(1)
    }
    return streak
  }

  private fun computeBaselineComparison(
    recentUsages: List<DailyUsage>,
    todayDate: DomainDate,
  ): BaselineComparison {
    // Aggregate total measured/recorded usage per distinct calendar day up to todayDate.
    val dailyTotals =
      recentUsages
        .filter { it.date <= todayDate && it.coverageStatus != UsageCoverageStatus.INCOMPLETE }
        .groupBy { it.date }
        .entries
        .sortedBy { it.key }
        .map { (_, items) -> items.fold(Duration.ZERO) { acc, item -> acc + item.consumed } }

    if (dailyTotals.size < MIN_BASELINE_DAYS) {
      return BaselineComparison.InsufficientData(
        recordedDaysCount = dailyTotals.size,
        requiredDaysCount = MIN_BASELINE_DAYS,
      )
    }

    val recentSlice = dailyTotals.takeLast(3)
    val priorSlice = dailyTotals.dropLast(3)
    val recentAvgMs = recentSlice.sumOf { it.inWholeMilliseconds } / recentSlice.size
    val baselineAvgMs = priorSlice.sumOf { it.inWholeMilliseconds } / priorSlice.size
    val deltaMinutes = (recentAvgMs - baselineAvgMs) / 60_000L

    return BaselineComparison.Available(
      recentAverageDaily = recentAvgMs.milliseconds,
      priorBaselineAverageDaily = baselineAvgMs.milliseconds,
      deltaMinutes = deltaMinutes,
      recentDaysCount = recentSlice.size,
      baselineDaysCount = priorSlice.size,
    )
  }
}
