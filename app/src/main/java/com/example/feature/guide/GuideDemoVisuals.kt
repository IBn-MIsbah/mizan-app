package com.example.feature.guide

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.RestrictionMode
import kotlinx.coroutines.delay

/**
 * Reusable frame for instructional animations: shows reduced-motion note when static,
 * and provides Play / Pause + Replay controls so users can inspect every step at their own pace.
 */
@Composable
private fun InstructionalDemoFrame(
  stepLabel: String,
  isActive: Boolean,
  reducedMotion: Boolean,
  stepCount: Int,
  finalStepIndex: Int = stepCount - 1,
  testTagPrefix: String,
  content: @Composable (step: Int) -> Unit,
) {
  var isPlaying by remember(isActive, reducedMotion) { mutableStateOf(isActive && !reducedMotion) }
  var currentStep by remember(reducedMotion) {
    mutableIntStateOf(if (reducedMotion) finalStepIndex else 0)
  }

  LaunchedEffect(isActive, isPlaying, reducedMotion, stepCount) {
    if (reducedMotion) {
      currentStep = finalStepIndex
      return@LaunchedEffect
    }
    if (!isActive || !isPlaying) return@LaunchedEffect
    while (true) {
      delay(1350L)
      currentStep = (currentStep + 1) % stepCount
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    content(if (reducedMotion) finalStepIndex else currentStep)

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text =
          if (reducedMotion) {
            stringResource(R.string.guide_reduced_motion_note)
          } else {
            stepLabel
          },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f).padding(end = 8.dp),
      )

      if (!reducedMotion) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(
            onClick = { isPlaying = !isPlaying },
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("${testTagPrefix}_play_pause"),
          ) {
            Icon(
              imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text =
                if (isPlaying) {
                  stringResource(R.string.guide_pause_demo_label)
                } else {
                  stringResource(R.string.guide_play_demo_label)
                }
            )
          }
          TextButton(
            onClick = {
              currentStep = 0
              isPlaying = true
            },
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("${testTagPrefix}_replay"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Replay,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = stringResource(R.string.guide_replay_demo_label))
          }
        }
      }
    }
  }
}

@Composable
private fun SimulatedActionPill(
  label: String,
  highlighted: Boolean,
  completed: Boolean,
  reducedMotion: Boolean,
) {
  val bgColor by
    animateColorAsState(
      targetValue =
        when {
          completed -> MaterialTheme.colorScheme.primaryContainer
          highlighted -> MaterialTheme.colorScheme.primary
          else -> MaterialTheme.colorScheme.surface
        },
      animationSpec = tween(if (reducedMotion) 0 else 280),
      label = "pill_bg",
    )
  val fgColor by
    animateColorAsState(
      targetValue =
        when {
          completed -> MaterialTheme.colorScheme.onPrimaryContainer
          highlighted -> MaterialTheme.colorScheme.onPrimary
          else -> MaterialTheme.colorScheme.onSurface
        },
      animationSpec = tween(if (reducedMotion) 0 else 280),
      label = "pill_fg",
    )
  val elevationPadding by
    animateDpAsState(
      targetValue = if (highlighted) 2.dp else 0.dp,
      animationSpec = tween(if (reducedMotion) 0 else 220),
      label = "pill_border",
    )

  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = elevationPadding),
    shape = MaterialTheme.shapes.small,
    color = bgColor,
    contentColor = fgColor,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(text = label, style = MaterialTheme.typography.labelLarge)
      if (highlighted) {
        Icon(
          imageVector = Icons.Filled.TouchApp,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
      } else if (completed) {
        Icon(
          imageVector = Icons.Filled.CheckCircle,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
fun OnboardingSetupDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  val stepDescriptions =
    listOf(
      "Step 1: Read local boundaries — zero accounts or tracking.",
      "Step 2: Tap \"Continue to Mizan\" to reach Balance.",
      "Step 3: Rules remain inactive until you deliberately agree.",
    )
  InstructionalDemoFrame(
    stepLabel = "First-launch onboarding -> Balance",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_onboarding",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      SimulatedActionPill(
        label = "Continue to Mizan",
        highlighted = step == 1,
        completed = step == 2,
        reducedMotion = reducedMotion,
      )
      Text(
        text = stepDescriptions[step],
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeComparisonDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  var selectedMode by remember { mutableStateOf(RestrictionMode.HYBRID) }
  var autoCycle by remember(isActive, reducedMotion) { mutableStateOf(isActive && !reducedMotion) }

  LaunchedEffect(isActive, autoCycle, reducedMotion) {
    if (reducedMotion || !isActive || !autoCycle) return@LaunchedEffect
    val modes = RestrictionMode.entries
    while (true) {
      delay(1600L)
      val nextIdx = (modes.indexOf(selectedMode) + 1) % modes.size
      selectedMode = modes[nextIdx]
    }
  }

  val blockFraction by
    animateFloatAsState(
      targetValue =
        when (selectedMode) {
          RestrictionMode.STRICT -> 1f
          RestrictionMode.SCHEDULED -> 0.52f
          RestrictionMode.HYBRID -> 0.72f
        },
      animationSpec = tween(if (reducedMotion) 0 else 320),
      label = "mode_fraction",
    )

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      RestrictionMode.entries.forEach { mode ->
        FilterChip(
          selected = selectedMode == mode,
          onClick = {
            autoCycle = false
            selectedMode = mode
          },
          label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
          modifier =
            Modifier.minimumInteractiveComponentSize()
              .testTag("guide_mode_chip_${mode.name.lowercase()}"),
        )
      }
    }

    Surface(
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text =
            when (selectedMode) {
              RestrictionMode.STRICT -> "Strict: Blocked 24/7 across all 7 days (allowance disabled)."
              RestrictionMode.SCHEDULED ->
                "Scheduled: Blocked only inside your configured quiet hours."
              RestrictionMode.HYBRID ->
                "Hybrid: Blocked during schedules + shared daily allowance outside them."
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(
          progress = { blockFraction },
          modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text(
          text =
            when (selectedMode) {
              RestrictionMode.STRICT -> "Protection coverage: Full 7-day cycle"
              RestrictionMode.SCHEDULED -> "Protection coverage: Quiet schedule windows only"
              RestrictionMode.HYBRID -> "Protection coverage: Schedule windows + shared 30m default"
            },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
fun AppsAndAllowlistDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Allowlist precedence demo",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_apps",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          modifier = Modifier.weight(1f),
          shape = MaterialTheme.shapes.small,
          color =
            if (step >= 1) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.surface,
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "Social App", style = MaterialTheme.typography.labelLarge)
            Text(
              text = if (step >= 1) "Restricted" else "Unselected",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Surface(
          modifier = Modifier.weight(1f),
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(14.dp))
              Text(text = "Phone / SMS", style = MaterialTheme.typography.labelLarge)
            }
            Text(text = "Allowlist wins", style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      Text(
        text =
          when (step) {
            0 -> "1. Select distracting apps installed on this device."
            1 -> "2. MIZAN pauses them when commitment rules trigger."
            else -> "3. Essential Allowlist tools always remain accessible, even in Strict mode."
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun SharedAllowanceDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Shared foreground pool (resets midnight)",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_allowance",
  ) { step ->
    val remainingRatio by
      animateFloatAsState(
        targetValue =
          when (step) {
            0 -> 1f
            1 -> 0.35f
            else -> 0f
          },
        animationSpec = tween(if (reducedMotion) 0 else 360),
        label = "allowance_ratio",
      )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text =
          when (step) {
            0 -> "Shared Pool: 30m remaining (0m used)"
            1 -> "App A (12m) + App B (8m) -> 10m left for ALL restricted apps"
            else -> "Shared Pool: 0m left -> Restricted apps pause until local midnight"
          },
        style = MaterialTheme.typography.labelLarge,
        color =
          if (step == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
      )
      LinearProgressIndicator(
        progress = { remainingRatio },
        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
      )
    }
  }
}

@Composable
fun AccessibilityActionDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Accessibility opt-in flow",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_accessibility",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      SimulatedActionPill(
        label =
          when (step) {
            0 -> "Open Accessibility Settings"
            1 -> "Android Settings -> Mizan App Restriction Monitor -> On"
            else -> "Enabled & Connected"
          },
        highlighted = step <= 1,
        completed = step == 2,
        reducedMotion = reducedMotion,
      )
      Text(
        text =
          when (step) {
            0 -> "Tap button in MIZAN Balance or Settings."
            1 -> "Enable MIZAN inside Android's system Accessibility menu."
            else -> "MIZAN observes package names only — never screen text or keystrokes."
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun UsageAccessActionDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Optional Usage Access reconciliation",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_usage_access",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      SimulatedActionPill(
        label =
          when (step) {
            0 -> "Open Usage Access Settings"
            1 -> "Deduplicate closed foreground intervals locally"
            else -> "Usage Access Enabled · Reconciled"
          },
        highlighted = step == 0,
        completed = step == 2,
        reducedMotion = reducedMotion,
      )
      Text(
        text =
          when (step) {
            0 -> "Optional permission when you want missed intervals recovered after restarts."
            1 -> "Only selected restricted package timestamps are checked."
            else -> "Historical usage stays on-device and improves Hybrid accuracy."
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun VpnFilterActionDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Consent -> Local DNS Tunnel (10.120.0.1/32)",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_vpn",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      SimulatedActionPill(
        label =
          when (step) {
            0 -> "[✓] Understand DNS limits -> Enable & Start Local DNS Filter"
            1 -> "Android VPN Dialog -> OK (Local device tunnel only)"
            else -> "Active · UDP Port 53 DNS-Only Filter"
          },
        highlighted = step <= 1,
        completed = step == 2,
        reducedMotion = reducedMotion,
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DomainBadge("m.social.example", "NXDOMAIN", true, Modifier.weight(1f))
        DomainBadge("docs.social.example", "Exception", false, Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun DomainBadge(domain: String, verdict: String, blocked: Boolean, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.small,
    color =
      if (blocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
      else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Text(text = domain, style = MaterialTheme.typography.labelSmall, maxLines = 1)
      Text(text = verdict, style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
fun ReviewAndActivateDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Draft -> Explicit Agreement -> 7-Day Lock",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_review",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      SimulatedActionPill(
        label =
          when (step) {
            0 -> "[ ] Confirm exact rules & Android limits"
            1 -> "[✓] Agreement checked -> Activate 7-Day Commitment"
            else -> "Locked Active for 168h · Zero Auto-Renewal"
          },
        highlighted = step == 1,
        completed = step == 2,
        reducedMotion = reducedMotion,
      )
      Text(
        text =
          when (step) {
            0 -> "Activation button stays disabled until you confirm."
            1 -> "Rules, allowance, and modes lock at activation."
            else -> "Nothing starts silently and nothing renews automatically after 7 days."
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun DashboardStatusDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Balance card -> Calm restriction screen",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_dashboard",
  ) { step ->
    val progress by
      animateFloatAsState(
        targetValue = (step + 1) / 3f,
        animationSpec = tween(if (reducedMotion) 0 else 300),
        label = "dash_progress",
      )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
          imageVector = if (step == 2) Icons.Filled.CheckCircle else Icons.Filled.Lock,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text =
            when (step) {
              0 -> "Day 1 of 7 · Active Commitment"
              1 -> "Restricted app paused -> Return to Home Screen"
              else -> "Day 7 of 7 · Period Reached"
            },
          style = MaterialTheme.typography.labelLarge,
        )
      }
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
      )
    }
  }
}

@Composable
fun UsageInsightsDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  val heights = listOf(0.85f, 0.65f, 0.7f, 0.5f, 0.4f, 0.35f, 0.25f)
  val mult by
    animateFloatAsState(
      targetValue = if (!isActive || reducedMotion) 1f else 1f,
      animationSpec = tween(if (reducedMotion) 0 else 300),
      label = "bars_mult",
    )
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().height(54.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      heights.forEachIndexed { idx, h ->
        Box(
          modifier =
            Modifier.weight(1f)
              .height((48f * h * mult).dp)
              .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
              .background(
                if (idx >= 5) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.secondaryContainer
              )
        )
      }
    }
    Text(
      text = "Factual local stats: blocked attempts, 7-day trend, streak & baseline comparison.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
fun EmergencyAndPinDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Immediate PIN exit vs 24h voluntary cooling-off",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_emergency",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      SimulatedActionPill(
        label =
          when (step) {
            0 -> "Emergency Termination -> Enter Trusted-Person PIN"
            1 -> "Confirm Emergency Termination (Immediate)"
            else -> "Or Request 24-Hour Cooling-Off in Settings (Cancelable)"
          },
        highlighted = step == 1,
        completed = step == 2,
        reducedMotion = reducedMotion,
      )
      Text(
        text =
          when (step) {
            0 -> "PIN is PBKDF2-hashed + Android Keystore sealed (never plaintext)."
            1 -> "Ends the commitment immediately and records it as Emergency Terminated."
            else -> "24h cooling-off lifts active locks & PIN locally once elapsed."
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun CompletionChoicesDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "3 deliberate post-commitment paths",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_completion",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val labels =
        listOf(
          "1. Finish (return calmly to Balance)",
          "2. Begin Another 7-Day Detox (fresh draft)",
          "3. Enter Discipline Mode (record intention)",
        )
      labels.forEachIndexed { idx, label ->
        SimulatedActionPill(
          label = label,
          highlighted = step == idx,
          completed = false,
          reducedMotion = reducedMotion,
        )
      }
    }
  }
}

@Composable
fun DatasetPolicyDemoVisual(isActive: Boolean, reducedMotion: Boolean) {
  InstructionalDemoFrame(
    stepLabel = "Category-specific network rules + ECDSA verification",
    isActive = isActive,
    reducedMotion = reducedMotion,
    stepCount = 3,
    testTagPrefix = "demo_dataset",
  ) { step ->
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
          modifier = Modifier.weight(1f),
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(14.dp))
              Text("Adult Content", style = MaterialTheme.typography.labelLarge)
            }
            Text(
              text = if (step >= 1) "Wi-Fi + Cellular Permitted" else "ECDSA + SHA-256 Required",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Surface(
          modifier = Modifier.weight(1f),
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        ) {
          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Icon(Icons.Filled.VpnLock, contentDescription = null, modifier = Modifier.size(14.dp))
              Text("Social Media", style = MaterialTheme.typography.labelLarge)
            }
            Text(
              text = if (step >= 1) "Respects Wi-Fi Only Switch" else "ECDSA + SHA-256 Required",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
      SimulatedActionPill(
        label =
          when (step) {
            0 -> "Signed package check -> Verified or Kept Last Known Good"
            1 -> "WorkManager checks per-category constraints automatically"
            else -> "Offline filtering stays uninterrupted even if sync fails"
          },
        highlighted = step == 1,
        completed = step == 2,
        reducedMotion = reducedMotion,
      )
    }
  }
}

@Composable
fun LocalSecurityBadgeVisual() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Filled.VerifiedUser,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(20.dp),
    )
    Text(
      text = "Zero accounts · Zero analytics · Zero browsing-history uploads",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
