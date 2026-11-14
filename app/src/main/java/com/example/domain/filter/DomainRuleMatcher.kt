package com.example.domain.filter

import com.example.domain.model.Commitment
import com.example.domain.model.DomainCategory
import com.example.domain.model.DomainRuleType
import com.example.domain.time.WallClockPoint

enum class DomainMatchReason {
  NO_ACTIVE_COMMITMENT,
  COMMITMENT_PERIOD_ELAPSED,
  FILTERING_NOT_ENABLED,
  INVALID_QUERY_DOMAIN,
  EXPLICIT_ALLOW_RULE,
  MATCHED_BLOCK_RULE,
  NO_MATCHING_RULE,
}

data class DomainMatchDecision(
  val isBlocked: Boolean,
  val reason: DomainMatchReason,
  val normalizedQuery: String? = null,
  val matchedRuleDomain: String? = null,
  val matchedCategory: DomainCategory? = null,
) {
  companion object {
    fun allow(
      reason: DomainMatchReason,
      normalizedQuery: String? = null,
      matchedRuleDomain: String? = null,
      matchedCategory: DomainCategory? = null,
    ): DomainMatchDecision =
      DomainMatchDecision(
        isBlocked = false,
        reason = reason,
        normalizedQuery = normalizedQuery,
        matchedRuleDomain = matchedRuleDomain,
        matchedCategory = matchedCategory,
      )

    fun block(
      normalizedQuery: String,
      matchedRuleDomain: String,
      matchedCategory: DomainCategory,
    ): DomainMatchDecision =
      DomainMatchDecision(
        isBlocked = true,
        reason = DomainMatchReason.MATCHED_BLOCK_RULE,
        normalizedQuery = normalizedQuery,
        matchedRuleDomain = matchedRuleDomain,
        matchedCategory = matchedCategory,
      )
  }
}

/**
 * Precompiled, deterministic lookup structure for label-boundary exact & subdomain matching.
 */
data class CompiledDomainRuleSet(
  val allowRules: Map<String, DomainCategory>,
  val blockRules: Map<String, DomainCategory>,
) {
  val isEmpty: Boolean
    get() = allowRules.isEmpty() && blockRules.isEmpty()

  fun matchQuery(normalizedQuery: String): DomainMatchDecision {
    val allowMatch = findDeepestLabelBoundaryMatch(normalizedQuery, allowRules)
    if (allowMatch != null) {
      return DomainMatchDecision.allow(
        reason = DomainMatchReason.EXPLICIT_ALLOW_RULE,
        normalizedQuery = normalizedQuery,
        matchedRuleDomain = allowMatch.first,
        matchedCategory = allowMatch.second,
      )
    }

    val blockMatch = findDeepestLabelBoundaryMatch(normalizedQuery, blockRules)
    if (blockMatch != null) {
      return DomainMatchDecision.block(
        normalizedQuery = normalizedQuery,
        matchedRuleDomain = blockMatch.first,
        matchedCategory = blockMatch.second,
      )
    }

    return DomainMatchDecision.allow(
      reason = DomainMatchReason.NO_MATCHING_RULE,
      normalizedQuery = normalizedQuery,
    )
  }

  private fun findDeepestLabelBoundaryMatch(
    normalizedQuery: String,
    rules: Map<String, DomainCategory>,
  ): Pair<String, DomainCategory>? {
    if (rules.isEmpty()) return null
    var candidate = normalizedQuery
    while (true) {
      val category = rules[candidate]
      if (category != null) {
        return candidate to category
      }
      val dotIndex = candidate.indexOf('.')
      if (dotIndex < 0 || dotIndex + 1 >= candidate.length) {
        return null
      }
      candidate = candidate.substring(dotIndex + 1)
    }
  }

  companion object {
    fun compile(
      commitment: Commitment.Active,
      datasetDomainsByCategory: Map<DomainCategory, Set<String>> = emptyMap(),
    ): CompiledDomainRuleSet {
      val allowMap = LinkedHashMap<String, DomainCategory>()
      val blockMap = LinkedHashMap<String, DomainCategory>()

      // 1. Add active verified dataset domains for selected categories.
      for (category in commitment.rules.blockedDomainCategories) {
        val datasetDomains = datasetDomainsByCategory[category].orEmpty()
        for (rawDomain in datasetDomains) {
          val normalized =
            (DomainNormalizer.normalize(rawDomain) as? DomainNormalizationResult.Valid)
              ?.normalizedHost ?: continue
          blockMap.putIfAbsent(normalized, category)
        }
      }

      // 2. Add explicit commitment-level rules deterministically.
      val sortedEntries =
        commitment.rules.blockedDomains.sortedWith(
          compareBy<com.example.domain.model.BlockedDomain> { it.domain.value }
            .thenBy { it.ruleType.name }
        )
      for (entry in sortedEntries) {
        val normalized =
          (DomainNormalizer.normalize(entry.domain.value) as? DomainNormalizationResult.Valid)
            ?.normalizedHost ?: continue
        when (entry.ruleType) {
          DomainRuleType.ALLOW -> allowMap[normalized] = entry.category
          DomainRuleType.BLOCK -> blockMap[normalized] = entry.category
        }
      }

      return CompiledDomainRuleSet(allowRules = allowMap, blockRules = blockMap)
    }
  }
}

/**
 * Pure domain evaluator for DNS/hostname filtering decisions.
 *
 * Precedence:
 * 1. Non-active commitments (null, Draft, Completed, EmergencyTerminated) -> Allow.
 * 2. Active commitments past 7-day end boundary -> Allow.
 * 3. Website filtering disabled -> Allow.
 * 4. Malformed/invalid query domain -> Allow (safe DNS passthrough / upstream validation).
 * 5. Explicit ALLOW rules override BLOCK rules via label-boundary suffix matching.
 * 6. Active BLOCK rules (custom + active datasets) match exact domain and subdomains only.
 */
object DomainRuleMatcher {

  fun evaluate(
    commitment: Commitment?,
    rawQueryDomain: String,
    wallClock: WallClockPoint,
    datasetDomainsByCategory: Map<DomainCategory, Set<String>> = emptyMap(),
  ): DomainMatchDecision {
    if (commitment !is Commitment.Active) {
      return DomainMatchDecision.allow(DomainMatchReason.NO_ACTIVE_COMMITMENT)
    }
    if (commitment.hasReachedEnd(wallClock)) {
      return DomainMatchDecision.allow(DomainMatchReason.COMMITMENT_PERIOD_ELAPSED)
    }
    if (!commitment.rules.websiteFilteringEnabled) {
      return DomainMatchDecision.allow(DomainMatchReason.FILTERING_NOT_ENABLED)
    }

    val compiled = CompiledDomainRuleSet.compile(commitment, datasetDomainsByCategory)
    return evaluateCompiled(
      commitment = commitment,
      compiled = compiled,
      rawQueryDomain = rawQueryDomain,
      wallClock = wallClock,
    )
  }

  fun evaluateCompiled(
    commitment: Commitment?,
    compiled: CompiledDomainRuleSet,
    rawQueryDomain: String,
    wallClock: WallClockPoint,
  ): DomainMatchDecision {
    if (commitment !is Commitment.Active) {
      return DomainMatchDecision.allow(DomainMatchReason.NO_ACTIVE_COMMITMENT)
    }
    if (commitment.hasReachedEnd(wallClock)) {
      return DomainMatchDecision.allow(DomainMatchReason.COMMITMENT_PERIOD_ELAPSED)
    }
    if (!commitment.rules.websiteFilteringEnabled || compiled.isEmpty) {
      return DomainMatchDecision.allow(DomainMatchReason.FILTERING_NOT_ENABLED)
    }
    val normalizedQuery =
      when (val result = DomainNormalizer.normalize(rawQueryDomain)) {
        is DomainNormalizationResult.Valid -> result.normalizedHost
        is DomainNormalizationResult.Invalid ->
          return DomainMatchDecision.allow(DomainMatchReason.INVALID_QUERY_DOMAIN)
      }
    return compiled.matchQuery(normalizedQuery)
  }
}
