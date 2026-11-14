package com.example.domain.filter

import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentRulesValidator
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.lifecycle.RulesValidationError
import com.example.domain.lifecycle.RulesValidationResult
import com.example.domain.model.BlockedDomain
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DomainCategory
import com.example.domain.model.DomainName
import com.example.domain.model.DomainRuleType
import com.example.domain.model.RestrictionMode
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.WallClockPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainNormalizerAndMatcherTest {

  private val baseTime =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(10, 0, 0),
      epochMillis = 1_794_564_000_000L,
    )

  private fun activeCommitment(
    websiteEnabled: Boolean = true,
    domains: Set<BlockedDomain> = emptySet(),
    categories: Set<DomainCategory> = emptySet(),
  ) =
    (CommitmentStateMachine.activate(
        CommitmentStateMachine.createDraft(
          id = CommitmentId("c-dom-1"),
          rules =
            CommitmentRules(
              mode = RestrictionMode.STRICT,
              restrictedApps = emptySet(),
              websiteFilteringEnabled = websiteEnabled,
              blockedDomains = domains,
              blockedDomainCategories = categories,
            ),
          now = baseTime,
        ),
        agreedToExactRules = true,
        now = baseTime,
      ) as CommitmentResult.Success)
      .commitment

  @Test
  fun `normalizes case trailing dots and internationalized domain names safely`() {
    assertEquals(
      DomainNormalizationResult.Valid("example.com"),
      DomainNormalizer.normalize("  Example.COM.  "),
    )
    assertEquals(
      DomainNormalizationResult.Valid("sub.domain.example"),
      DomainNormalizer.normalize("SUB.domain.EXAMPLE"),
    )
    assertEquals(
      DomainNormalizationResult.Valid("xn--bcher-kva.example"),
      DomainNormalizer.normalize("bücher.example."),
    )
  }

  @Test
  fun `rejects empty malformed URLs wildcards IP literals and single-label hosts`() {
    val invalidInputs =
      listOf(
        "",
        "   ",
        "localhost",
        "https://example.com",
        "example.com/path",
        "example.com:443",
        "*.example.com",
        "user@example.com",
        "192.168.1.1",
        "[2001:db8::1]",
        "2001:db8::1",
        "-bad.example.com",
        "bad-.example.com",
        ".example.com",
        "example..com",
        "bad host.com",
        "a".repeat(64) + ".com",
        ("label.".repeat(43) + "com"),
      )

    invalidInputs.forEach { raw ->
      assertTrue(
        "Expected '$raw' to be rejected",
        DomainNormalizer.normalize(raw) is DomainNormalizationResult.Invalid,
      )
    }
  }

  @Test
  fun `exact and subdomain label-boundary matching blocks subdomains without false suffix matches`() {
    val commitment =
      activeCommitment(
        domains = setOf(BlockedDomain(DomainName("example.com"), ruleType = DomainRuleType.BLOCK))
      )

    val exact = DomainRuleMatcher.evaluate(commitment, "EXAMPLE.COM.", baseTime)
    assertTrue(exact.isBlocked)
    assertEquals("example.com", exact.matchedRuleDomain)

    val sub = DomainRuleMatcher.evaluate(commitment, "deep.sub.example.com", baseTime)
    assertTrue(sub.isBlocked)
    assertEquals("example.com", sub.matchedRuleDomain)

    val prefixLookalike = DomainRuleMatcher.evaluate(commitment, "notexample.com", baseTime)
    assertFalse(prefixLookalike.isBlocked)
    assertEquals(DomainMatchReason.NO_MATCHING_RULE, prefixLookalike.reason)

    val suffixLookalike = DomainRuleMatcher.evaluate(commitment, "example.com.attacker.test", baseTime)
    assertFalse(suffixLookalike.isBlocked)
    assertEquals(DomainMatchReason.NO_MATCHING_RULE, suffixLookalike.reason)
  }

  @Test
  fun `explicit allow exception overrides blocked parent and dataset domains`() {
    val commitment =
      activeCommitment(
        domains =
          setOf(
            BlockedDomain(DomainName("social.example"), ruleType = DomainRuleType.BLOCK),
            BlockedDomain(DomainName("docs.social.example"), ruleType = DomainRuleType.ALLOW),
            BlockedDomain(DomainName("status.feed.example"), ruleType = DomainRuleType.ALLOW),
          ),
        categories = setOf(DomainCategory.SOCIAL_MEDIA),
      )
    val datasetMap = mapOf(DomainCategory.SOCIAL_MEDIA to setOf("feed.example"))

    assertTrue(DomainRuleMatcher.evaluate(commitment, "m.social.example", baseTime, datasetMap).isBlocked)
    assertTrue(DomainRuleMatcher.evaluate(commitment, "app.feed.example", baseTime, datasetMap).isBlocked)

    val allowedSub = DomainRuleMatcher.evaluate(commitment, "api.docs.social.example", baseTime, datasetMap)
    assertFalse(allowedSub.isBlocked)
    assertEquals(DomainMatchReason.EXPLICIT_ALLOW_RULE, allowedSub.reason)

    val allowedDatasetSub =
      DomainRuleMatcher.evaluate(commitment, "status.feed.example", baseTime, datasetMap)
    assertFalse(allowedDatasetSub.isBlocked)
    assertEquals(DomainMatchReason.EXPLICIT_ALLOW_RULE, allowedDatasetSub.reason)
  }

  @Test
  fun `inactive completed terminated expired and disabled commitments do not block domains`() {
    val rules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = emptySet(),
        websiteFilteringEnabled = true,
        blockedDomains = setOf(BlockedDomain(DomainName("example.com"), ruleType = DomainRuleType.BLOCK)),
      )
    val draft = CommitmentStateMachine.createDraft(CommitmentId("c-draft"), rules, baseTime)
    assertFalse(DomainRuleMatcher.evaluate(null, "example.com", baseTime).isBlocked)
    assertFalse(DomainRuleMatcher.evaluate(draft, "example.com", baseTime).isBlocked)

    val active = activeCommitment(domains = rules.blockedDomains)
    // At active.endsAt, commitment period has elapsed
    val atEnd = DomainRuleMatcher.evaluate(active, "example.com", active.endsAt)
    assertFalse(atEnd.isBlocked)
    assertEquals(DomainMatchReason.COMMITMENT_PERIOD_ELAPSED, atEnd.reason)

    val completed =
      (CommitmentStateMachine.completeIfReachedEnd(active, active.endsAt) as CommitmentResult.Success)
        .commitment
    assertFalse(DomainRuleMatcher.evaluate(completed, "example.com", active.endsAt).isBlocked)

    val disabled = activeCommitment(websiteEnabled = false, domains = rules.blockedDomains)
    val disabledDecision = DomainRuleMatcher.evaluate(disabled, "example.com", baseTime)
    assertFalse(disabledDecision.isBlocked)
    assertEquals(DomainMatchReason.FILTERING_NOT_ENABLED, disabledDecision.reason)
  }

  @Test
  fun `duplicate rules and validator domain checks behave deterministically`() {
    val validWebsiteOnlyRules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = emptySet(),
        websiteFilteringEnabled = true,
        blockedDomains =
          setOf(
            BlockedDomain(DomainName("example.com"), ruleType = DomainRuleType.BLOCK),
            BlockedDomain(DomainName("EXAMPLE.COM."), ruleType = DomainRuleType.BLOCK),
          ),
      )
    assertEquals(RulesValidationResult.Valid, CommitmentRulesValidator.validateForActivation(validWebsiteOnlyRules))

    val active =
      activeCommitment(domains = validWebsiteOnlyRules.blockedDomains)
    assertTrue(DomainRuleMatcher.evaluate(active, "sub.example.com", baseTime).isBlocked)

    val enabledWithoutTargets =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = emptySet(),
        websiteFilteringEnabled = true,
        blockedDomains = emptySet(),
      )
    val noTargetsResult =
      CommitmentRulesValidator.validateForActivation(enabledWithoutTargets) as RulesValidationResult.Invalid
    assertTrue(RulesValidationError.WebsiteFilteringTargetsRequired in noTargetsResult.errors)

    val conflictingRules =
      CommitmentRules(
        mode = RestrictionMode.STRICT,
        restrictedApps = emptySet(),
        websiteFilteringEnabled = true,
        blockedDomains =
          setOf(
            BlockedDomain(DomainName("example.com"), ruleType = DomainRuleType.BLOCK),
            BlockedDomain(DomainName("EXAMPLE.COM."), ruleType = DomainRuleType.ALLOW),
          ),
      )
    val conflictResult =
      CommitmentRulesValidator.validateForActivation(conflictingRules) as RulesValidationResult.Invalid
    assertTrue(conflictResult.errors.any { it is RulesValidationError.ConflictingDomainRules })
  }
}
