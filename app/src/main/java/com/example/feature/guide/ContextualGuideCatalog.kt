package com.example.feature.guide

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnLock
import com.example.core.designsystem.component.MizanExplanationSlide

object ContextualGuideCatalog {
  fun titleFor(context: ContextualGuideContext): String =
    when (context) {
      ContextualGuideContext.ONBOARDING -> "Getting Started with MIZAN"
      ContextualGuideContext.DASHBOARD -> "Understanding Balance & Insights"
      ContextualGuideContext.SETUP_MODES -> "Strict, Scheduled & Hybrid Modes"
      ContextualGuideContext.SETUP_APPS -> "Restricted Apps & Allowlist Priority"
      ContextualGuideContext.SETUP_SCHEDULE_ALLOWANCE -> "Quiet Schedules & Shared Allowance"
      ContextualGuideContext.SETUP_WEBSITE -> "Domain Rules & Local DNS Scope"
      ContextualGuideContext.SETUP_PIN -> "Accountability PIN Protection"
      ContextualGuideContext.REVIEW -> "Review, Agreement & 7-Day Lock"
      ContextualGuideContext.COMPLETION -> "Completion Choices (No Auto-Renewal)"
      ContextualGuideContext.EMERGENCY -> "Emergency Termination & PIN"
      ContextualGuideContext.PERMISSIONS -> "Android Permissions & Honest Limits"
      ContextualGuideContext.SETTINGS_SECURITY -> "PIN & 24-Hour Cooling-Off Guide"
      ContextualGuideContext.SETTINGS_DATASETS -> "Dataset Updates & Network Policy"
    }

  fun topicIdFor(context: ContextualGuideContext): String =
    when (context) {
      ContextualGuideContext.ONBOARDING -> "onboarding_setup"
      ContextualGuideContext.DASHBOARD -> "dashboard_status"
      ContextualGuideContext.SETUP_MODES -> "setup_choose_mode"
      ContextualGuideContext.SETUP_APPS -> "setup_restricted_apps"
      ContextualGuideContext.SETUP_SCHEDULE_ALLOWANCE -> "setup_daily_allowance"
      ContextualGuideContext.SETUP_WEBSITE -> "setup_domains"
      ContextualGuideContext.SETUP_PIN -> "emergency_pin"
      ContextualGuideContext.REVIEW -> "review_agreement"
      ContextualGuideContext.COMPLETION -> "completion_next"
      ContextualGuideContext.EMERGENCY -> "emergency_pin"
      ContextualGuideContext.PERMISSIONS -> "permission_accessibility"
      ContextualGuideContext.SETTINGS_SECURITY -> "emergency_pin"
      ContextualGuideContext.SETTINGS_DATASETS -> "dataset_updates"
    }

  fun slidesFor(context: ContextualGuideContext): List<MizanExplanationSlide> =
    when (context) {
      ContextualGuideContext.ONBOARDING ->
        GuideTopicsCatalog.findById("onboarding_setup")!!.slides +
          GuideTopicsCatalog.findById("purpose_modes")!!.slides
      ContextualGuideContext.DASHBOARD ->
        GuideTopicsCatalog.findById("dashboard_status")!!.slides +
          GuideTopicsCatalog.findById("dashboard_insights")!!.slides
      ContextualGuideContext.SETUP_MODES ->
        GuideTopicsCatalog.findById("setup_choose_mode")!!.slides
      ContextualGuideContext.SETUP_APPS ->
        GuideTopicsCatalog.findById("setup_restricted_apps")!!.slides
      ContextualGuideContext.SETUP_SCHEDULE_ALLOWANCE ->
        GuideTopicsCatalog.findById("setup_daily_allowance")!!.slides
      ContextualGuideContext.SETUP_WEBSITE ->
        GuideTopicsCatalog.findById("setup_domains")!!.slides +
          GuideTopicsCatalog.findById("permission_vpn")!!.slides
      ContextualGuideContext.SETUP_PIN,
      ContextualGuideContext.EMERGENCY,
      ContextualGuideContext.SETTINGS_SECURITY ->
        GuideTopicsCatalog.findById("emergency_pin")!!.slides
      ContextualGuideContext.REVIEW ->
        GuideTopicsCatalog.findById("review_config")!!.slides +
          GuideTopicsCatalog.findById("review_activate")!!.slides
      ContextualGuideContext.COMPLETION ->
        GuideTopicsCatalog.findById("completion_next")!!.slides
      ContextualGuideContext.PERMISSIONS ->
        GuideTopicsCatalog.findById("permission_accessibility")!!.slides +
          GuideTopicsCatalog.findById("permission_usage_access")!!.slides +
          GuideTopicsCatalog.findById("permission_vpn")!!.slides
      ContextualGuideContext.SETTINGS_DATASETS ->
        GuideTopicsCatalog.findById("dataset_updates")!!.slides
    }

  /**
   * Swipeable 3-slide disclosure used inside VpnFilterHealthCard instead of a dense bullet wall.
   */
  val vpnDisclosureSlides: List<MizanExplanationSlide> =
    listOf(
      MizanExplanationSlide(
        id = "vpn_scope_tunnel",
        heading = "Local-Only DNS Tunnel",
        explanation =
          "MIZAN uses Android VPN permission strictly on this device for local DNS filtering. Ordinary traffic never routes through a remote MIZAN server, and Android permits only one active VPN at a time.",
        icon = Icons.Outlined.VpnLock,
      ),
      MizanExplanationSlide(
        id = "vpn_scope_domain_only",
        heading = "Domain Matching, Not URL Paths",
        explanation =
          "Filtering operates on domain names only — never HTTPS URL paths or page content. Shared CDN domains can also affect unrelated services that share the same domain.",
        icon = Icons.Outlined.Lock,
      ),
      MizanExplanationSlide(
        id = "vpn_scope_bypass_limits",
        heading = "Encrypted DNS & IPv6 Limits",
        explanation =
          "Android Private DNS (DoT), browser DNS-over-HTTPS (DoH), IPv6 DNS servers (MIZAN tunnels IPv4 DNS only), proxies, and hard-coded IPs can bypass local UDP port-53 filtering.",
        icon = Icons.Outlined.Shield,
      ),
    )

  /**
   * Swipeable 3-slide explanation used on CommitmentReviewScreen replacing dense limitation prose.
   */
  val reviewLimitationsSlides: List<MizanExplanationSlide> =
    listOf(
      MizanExplanationSlide(
        id = "rev_limit_apps",
        heading = "App Blocking & Permissions",
        explanation =
          "Real-time foreground app blocking relies on Accessibility Settings and optional Usage Access reconciliation, plus your device's background battery settings.",
        icon = Icons.Outlined.Shield,
      ),
      MizanExplanationSlide(
        id = "rev_limit_dns",
        heading = "Optional Local DNS Scope",
        explanation =
          "When website filtering is enabled, you explicitly authorize Android's local VPN. It filters IPv4 DNS domains only — not URL paths, Private DNS/DoT, browser DoH, IPv6 DNS, or proxies.",
        icon = Icons.Outlined.VpnLock,
      ),
      MizanExplanationSlide(
        id = "rev_limit_lifecycle",
        heading = "7-Day Voluntary Commitment",
        explanation =
          "Your rules and allowance lock for 168 hours when activated. Nothing renews automatically at day 7, and emergency termination remains available.",
        icon = Icons.Outlined.Lock,
      ),
    )
}
