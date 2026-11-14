package com.example.feature.guide

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnLock
import com.example.core.designsystem.component.MizanExplanationSlide

object GuideTopicsCatalog {
  val topics: List<GuideTopic> =
    listOf(
      GuideTopic(
        id = "onboarding_setup",
        stepNumber = 1,
        category = GuideCategory.GETTING_STARTED,
        title = "Initial Onboarding & Setup",
        summary = "Understand MIZAN's calm local-first foundation before configuring anything.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "onboard_1",
              heading = "Start at Your Own Pace",
              explanation =
                "MIZAN keeps every draft inactive until you review all rules and explicitly agree. There are zero accounts, ads, feeds, or cloud uploads.",
              exampleBody = "First-launch onboarding introduces the 7-day cycle and brings you to Balance.",
              icon = Icons.Outlined.Flag,
              visualContent = { active, reduced -> OnboardingSetupDemoVisual(active, reduced) },
            ),
            MizanExplanationSlide(
              id = "onboard_2",
              heading = "Offline Privacy Guarantee",
              explanation =
                "Commitments, app selections, daily usage, and PIN credentials live exclusively in local Room and Keystore-backed storage.",
              icon = Icons.Outlined.Lock,
              visualContent = { _, _ -> LocalSecurityBadgeVisual() },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Continue to Mizan",
            whatItDoes = "Marks onboarding complete locally and opens the Balance dashboard.",
            consequence = "Does not activate any blocking rules or request OS permissions yet.",
            whatToExpect = "You arrive at Balance where you can explore or start configuring a 7-day draft.",
          ),
      ),
      GuideTopic(
        id = "purpose_modes",
        stepNumber = 2,
        category = GuideCategory.GETTING_STARTED,
        title = "Purpose & Operating Modes",
        summary = "Compare Strict, Scheduled, and Hybrid discipline styles side by side.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "purpose_1",
              heading = "Intentional Technology Use",
              explanation =
                "MIZAN is not a guilt-driven screen-time counter. You choose clear 7-day boundaries and inspect honest system behavior.",
              exampleBody = "Tap any mode chip above to compare coverage before editing a draft.",
              icon = Icons.Outlined.Tune,
              visualContent = { active, reduced -> ModeComparisonDemoVisual(active, reduced) },
            ),
          ),
      ),
      GuideTopic(
        id = "permission_accessibility",
        stepNumber = 3,
        category = GuideCategory.PERMISSIONS_VPN,
        title = "Granting Accessibility Permission",
        summary = "Required for real-time foreground app restriction and live active-window timing.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "a11y_1",
              heading = "Observe Foreground Package Transitions Only",
              explanation =
                "Android requires manual opt-in in Accessibility Settings so MIZAN can detect when a chosen restricted app enters the foreground.",
              exampleBody = "MIZAN never reads window text, messages, passwords, or screenshots.",
              icon = Icons.Outlined.AccessibilityNew,
              visualContent = { active, reduced -> AccessibilityActionDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Open Accessibility Settings",
            whatItDoes = "Opens Android's system Accessibility screen where you can toggle Mizan App Restriction Monitor.",
            consequence = "While off, MIZAN cannot show the calm pause screen over restricted apps.",
            whatToExpect = "Returning to MIZAN updates the status badge to Enabled & Connected.",
          ),
      ),
      GuideTopic(
        id = "permission_usage_access",
        stepNumber = 4,
        category = GuideCategory.PERMISSIONS_VPN,
        title = "Granting Usage Access Permission",
        summary = "Optional permission to reconcile closed foreground intervals locally after restarts.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "usage_perm_1",
              heading = "Local Interval Reconciliation",
              explanation =
                "If Accessibility or the app process restarts, Android UsageStats timestamps help backfill missed intervals for selected restricted apps only.",
              exampleBody = "Completely optional — MIZAN leaves this off until you choose to enable it.",
              icon = Icons.Outlined.QueryStats,
              visualContent = { active, reduced -> UsageAccessActionDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Open Usage Access Settings",
            whatItDoes = "Opens Android Usage Access settings so you can optionally authorize MIZAN.",
            consequence = "When disabled, historical gaps cannot be backfilled and coverage is labeled partial.",
            whatToExpect = "Once enabled, Balance & Settings mark Usage Access Enabled and reconcile selected apps locally.",
          ),
      ),
      GuideTopic(
        id = "permission_vpn",
        stepNumber = 5,
        category = GuideCategory.PERMISSIONS_VPN,
        title = "VPN-Based Website Filtering",
        summary = "Understand local DNS filtering, user consent, and realistic Android limits.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "vpn_1",
              heading = "Local On-Device DNS Tunnel Only",
              explanation =
                "MIZAN inspects only UDP port-53 DNS domain queries on a local 10.120.0.1/32 interface. No traffic routes through any remote MIZAN server.",
              exampleBody = "Allowed domains are forwarded to your active network's DNS resolvers.",
              icon = Icons.Outlined.VpnLock,
              visualContent = { active, reduced -> VpnFilterActionDemoVisual(active, reduced) },
            ),
            MizanExplanationSlide(
              id = "vpn_2",
              heading = "Important Android Limitations",
              explanation =
                "Android allows only one active VPN at a time. Domain filtering cannot block HTTPS URL paths, Private DNS (DoT), browser DoH, IPv6-only DNS, proxies, or hard-coded IPs.",
              icon = Icons.Outlined.Shield,
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Consent Checkbox + Enable & Start Local DNS Filter",
            whatItDoes = "Confirms disclosure understanding and requests Android's VPN prompt.",
            consequence = "Replaces any other active VPN while running; stops cleanly when stopped or revoked.",
            whatToExpect = "Status badge changes to Active and displays the detected IPv4 upstream DNS count.",
          ),
      ),
      GuideTopic(
        id = "setup_restricted_apps",
        stepNumber = 6,
        category = GuideCategory.SETUP_MODES,
        title = "Selecting Restricted Apps & Allowlist",
        summary = "Pick launchable apps to pause and guarantee essential tools stay reachable.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "apps_1",
              heading = "Allowlist Always Takes Precedence",
              explanation =
                "Search installed apps or add a package ID manually. Essential tools like Phone, SMS, Banking, and Maps in your Allowlist are never blocked even if rules overlap.",
              exampleBody = "Phone stays reachable even during a Strict 7-day commitment.",
              icon = Icons.Outlined.Apps,
              visualContent = { active, reduced -> AppsAndAllowlistDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Restricted & Allowlist Checkboxes / Add Custom App",
            whatItDoes = "Updates your local Draft with selected package IDs before activation.",
            consequence = "Allowlist rules always win over Restricted rules for safety.",
            whatToExpect = "Selected packages appear in your rule summary prior to review.",
          ),
      ),
      GuideTopic(
        id = "setup_domains",
        stepNumber = 7,
        category = GuideCategory.SETUP_MODES,
        title = "Blocked & Allowlisted Domains",
        summary = "Configure exact domain rules, subdomain inheritance, and category toggles.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "domains_1",
              heading = "Boundary-Safe Subdomain Matching",
              explanation =
                "Blocking example.com blocks example.com and sub.example.com while leaving unrelated domains like notexample.com untouched. Allow exceptions override block rules.",
              exampleBody = "Add example.com to Block and docs.example.com to Allow Exception.",
              icon = Icons.Outlined.Language,
              visualContent = { active, reduced -> VpnFilterActionDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Add Blocked Domain / Add Allowed Exception",
            whatItDoes = "Validates and saves normalized domain rules or exception overrides to your draft.",
            consequence = "Applies when website filtering is turned on and local VPN is started.",
            whatToExpect = "Each domain shows clearly tagged as [Block] or [Allow Exception].",
          ),
      ),
      GuideTopic(
        id = "setup_choose_mode",
        stepNumber = 8,
        category = GuideCategory.SETUP_MODES,
        title = "Choosing Strict, Scheduled, or Hybrid",
        summary = "Select the structure that matches your week before locking rules.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "mode_pick_1",
              heading = "Strict vs Scheduled vs Hybrid",
              explanation =
                "Strict blocks throughout all 7 days (overriding allowance). Scheduled blocks inside quiet windows. Hybrid combines quiet windows with one shared daily allowance.",
              icon = Icons.Outlined.Schedule,
              visualContent = { active, reduced -> ModeComparisonDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Mode Radio Selection + Add Schedule Window",
            whatItDoes = "Configures your commitment evaluation model and overnight-capable [start, end) quiet windows.",
            consequence = "Strict ignores allowances; Scheduled and Hybrid require valid schedule windows or allowance configuration.",
            whatToExpect = "Relevant Schedule and Allowance cards dynamically appear below when selected.",
          ),
      ),
      GuideTopic(
        id = "setup_daily_allowance",
        stepNumber = 9,
        category = GuideCategory.SETUP_MODES,
        title = "Setting the Shared Daily Allowance",
        summary = "Configure one shared foreground pool for Hybrid mode (30m default).",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "allowance_1",
              heading = "One Pool Across All Restricted Apps",
              explanation =
                "In Hybrid mode, every minute spent in the foreground across any restricted app draws from the same daily pool, resetting automatically at local midnight.",
              exampleBody = "12m in App A + 18m in App B uses all 30m for the day.",
              icon = Icons.Outlined.HourglassBottom,
              visualContent = { active, reduced -> SharedAllowanceDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Allowance Presets (15m–90m) / -5m / +5m",
            whatItDoes = "Sets the shared daily foreground limit before activation.",
            consequence = "Once a commitment is activated, the daily allowance locks for the full 7 days.",
            whatToExpect = "Reaching 0m remaining pauses restricted apps for the rest of the calendar day.",
          ),
      ),
      GuideTopic(
        id = "review_config",
        stepNumber = 10,
        category = GuideCategory.SETUP_MODES,
        title = "Reviewing Commitment Configuration",
        summary = "Inspect calculated timestamps and exact rule summaries before committing.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "review_cfg_1",
              heading = "Nothing Activates Silently",
              explanation =
                "Continue to Review validates your draft and opens a full summary of restricted apps, allowlist, schedule windows, domains, PIN requirement, and exact end timestamp.",
              icon = Icons.AutoMirrored.Outlined.FactCheck,
              visualContent = { active, reduced -> ReviewAndActivateDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Continue to Review",
            whatItDoes = "Validates draft completeness and opens Review & Explicit Agreement.",
            consequence = "Flags missing apps or invalid windows clearly before letting you proceed.",
            whatToExpect = "You can still go Back to tweak anything while rules remain inactive.",
          ),
      ),
      GuideTopic(
        id = "review_agreement",
        stepNumber = 11,
        category = GuideCategory.SETUP_MODES,
        title = "The 7-Day Commitment Agreement",
        summary = "Explicit informed consent for fixed 168-hour duration and realistic platform limits.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "agree_1",
              heading = "Clear Consent Checklist",
              explanation =
                "Before activating, swipe through Android's exact permissions and local DNS limitations, then check the agreement box deliberately.",
              icon = Icons.Outlined.Gavel,
              visualContent = { active, reduced -> ReviewAndActivateDemoVisual(active, reduced) },
            ),
          ),
      ),
      GuideTopic(
        id = "review_activate",
        stepNumber = 12,
        category = GuideCategory.SETUP_MODES,
        title = "Starting a 7-Day Commitment",
        summary = "Lock your commitment and transition to the active Balance dashboard.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "activate_1",
              heading = "Activate & Lock Rules",
              explanation =
                "Tapping Activate 7-Day Commitment starts your 168-hour cycle immediately. Rules remain locked for integrity until completion or emergency termination.",
              icon = Icons.Outlined.Lock,
              visualContent = { active, reduced -> ReviewAndActivateDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Activate 7-Day Commitment",
            whatItDoes = "Transitions the Draft into Active for 7 days and locks editing in Setup.",
            consequence = "Setup tab displays Active Commitment Rules Locked; no auto-renewal is scheduled.",
            whatToExpect = "Returns directly to Balance showing live 7-day progress and status badges.",
          ),
      ),
      GuideTopic(
        id = "dashboard_status",
        stepNumber = 13,
        category = GuideCategory.ACTIVE_EMERGENCY,
        title = "Dashboard & Restriction Status",
        summary = "Read active progress, health cards, and calm pause screens accurately.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "dash_1",
              heading = "Your Central Balance View",
              explanation =
                "Balance displays your commitment stage (Draft, Active progress bar, Completed, or Emergency Terminated), alongside Accessibility, Usage Access, Battery, and VPN health.",
              icon = Icons.Outlined.CheckCircle,
              visualContent = { active, reduced -> DashboardStatusDemoVisual(active, reduced) },
            ),
          ),
      ),
      GuideTopic(
        id = "dashboard_insights",
        stepNumber = 14,
        category = GuideCategory.ACTIVE_EMERGENCY,
        title = "Usage Insights & Allowance",
        summary = "Interpret factual trends, streaks, coverage badges, and baseline comparisons.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "insights_1",
              heading = "Factual Local Trends Without Shame",
              explanation =
                "MIZAN tracks blocked attempts, shared allowance consumption, consecutive days within allowance, a 7-day chart, and personal baseline comparisons once enough days exist.",
              exampleBody = "Coverage explicitly labels partial intervals if permissions were disabled.",
              icon = Icons.Outlined.Insights,
              visualContent = { active, reduced -> UsageInsightsDemoVisual(active, reduced) },
            ),
          ),
      ),
      GuideTopic(
        id = "emergency_pin",
        stepNumber = 15,
        category = GuideCategory.ACTIVE_EMERGENCY,
        title = "Emergency Termination & PIN",
        summary = "Exit immediately when truly needed, or protect early exits with a local PIN.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "pin_1",
              heading = "Immediate Exit vs 24-Hour Cooling-Off",
              explanation =
                "Emergency Termination ends an active commitment immediately after warning confirmation (plus your trusted-person PBKDF2 PIN if required). Settings also offers a cancelable 24-hour voluntary cooling-off.",
              exampleBody = "Local PIN protection is voluntary accountability friction — not OS tamper-proofing.",
              icon = Icons.Outlined.Pin,
              visualContent = { active, reduced -> EmergencyAndPinDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Confirm Emergency Termination",
            whatItDoes = "Verifies confirmation (and PIN if enabled) and ends active restrictions immediately.",
            consequence = "Recorded locally as Emergency Terminated, kept distinct from a 7-day completion.",
            whatToExpect = "Returns to Balance with rules lifted cleanly.",
          ),
      ),
      GuideTopic(
        id = "completion_next",
        stepNumber = 16,
        category = GuideCategory.ACTIVE_EMERGENCY,
        title = "Completion Screen & Next Actions",
        summary = "Deliberately choose Finish, Another 7-Day Detox, or Discipline Mode after 7 days.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "complete_1",
              heading = "Zero Auto-Renewal at Day 7",
              explanation =
                "When 7 days finish, tap Complete Commitment to review your milestone and pick Finish, Begin Another 7-Day Detox, or record Discipline Mode intention.",
              icon = Icons.Outlined.Flag,
              visualContent = { active, reduced -> CompletionChoicesDemoVisual(active, reduced) },
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Complete Commitment -> Choose Next Step",
            whatItDoes = "Records 7-day completion locally and asks which deliberate path you want next.",
            consequence = "No new commitment activates silently; starting another detox creates a fresh Draft.",
            whatToExpect = "Either returns calmly to Balance or opens a new customizable draft.",
          ),
      ),
      GuideTopic(
        id = "dataset_updates",
        stepNumber = 17,
        category = GuideCategory.DATASETS_PRIVACY,
        title = "Adult-Content & Social-Media Datasets",
        summary = "Category-specific cellular/Wi-Fi update policies, ECDSA trust, and rollback.",
        slides =
          listOf(
            MizanExplanationSlide(
              id = "dataset_1",
              heading = "Category-Specific Network Policy",
              explanation =
                "Adult Content updates are permitted on both Wi-Fi and cellular networks automatically and on Check for Updates. Social Media respects your Wi-Fi-only toggle.",
              exampleBody = "Turning Wi-Fi-only ON defers Social Media on cellular while still permitting Adult Content updates.",
              icon = Icons.Outlined.CloudSync,
              visualContent = { active, reduced -> DatasetPolicyDemoVisual(active, reduced) },
            ),
            MizanExplanationSlide(
              id = "dataset_2",
              heading = "ECDSA Trust, Offline Cache & Rollback",
              explanation =
                "Every package requires pinned ECDSA verification and SHA-256 integrity checks. Failed updates never overwrite your working offline cache, and previous versions stay one tap away via Roll Back.",
              icon = Icons.Outlined.Shield,
            ),
          ),
        actionDetail =
          GuideActionDetail(
            actionUiLabel = "Check for Updates / Import Signed Package / Roll Back",
            whatItDoes = "Verifies signed feeds or local .json packages atomically with rollback preservation.",
            consequence = "Remote feeds remain cleanly disabled unless a pinned public key and vetted endpoint are built in.",
            whatToExpect = "Displays clear status badges (Available, Updating, Outdated, Unavailable, Failed) without breaking offline filtering.",
          ),
      ),
    )

  fun findById(id: String): GuideTopic? = topics.firstOrNull { it.id == id }
}
