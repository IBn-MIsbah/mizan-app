package com.example.platform.accessibility

import java.util.Locale

enum class ShortsDetectionClassification {
  NOT_YOUTUBE,
  NON_SHORTS_SCREEN,
  UNCERTAIN,
  CONFIRMED_SHORTS_PLAYER,
}

enum class DetectionConfidence {
  NONE,
  LOW,
  MEDIUM,
  HIGH,
}

enum class ShortsSignalCategory {
  REEL_PLAYER_CONTAINER,
  SHORTS_PLAYER_CONTROLS,
  SELECTED_SHORTS_TAB,
  SEMANTIC_PLAYER_OVERLAY,
  NORMAL_WATCH_PLAYER,
  BROWSE_OR_SEARCH_SURFACE,
}

data class ShortsDetectionResult(
  val classification: ShortsDetectionClassification,
  val confidence: DetectionConfidence,
  val matchedSignals: Set<ShortsSignalCategory> = emptySet(),
) {
  val isConfirmedShorts: Boolean
    get() = classification == ShortsDetectionClassification.CONFIRMED_SHORTS_PLAYER
}

/**
 * Pure Kotlin multi-signal classifier that distinguishes an active **YouTube Shorts player**
 * screen from normal YouTube screens (Home, Search, Subscriptions, Channel pages, standard
 * long-form video playback, and comments).
 *
 * Fails open (`NON_SHORTS_SCREEN` or `UNCERTAIN`) whenever signals are missing, ambiguous, or
 * belong to normal YouTube playback/browsing.
 */
object YouTubeShortsDetector {

  const val YOUTUBE_PACKAGE_NAME: String = "com.google.android.youtube"

  private val REEL_CONTAINER_IDS =
    setOf(
      "reel_watch_fragment_root",
      "reel_recycler",
      "reel_player_page_container",
      "reel_watch_player",
      "reel_player_underlay",
      "reel_progress_bar",
      "reel_scrubber",
      "shorts_player",
      "shorts_player_container",
      "shorts_video_player",
      "reel_player_container",
    )

  private val SHORTS_CONTROL_IDS =
    setOf(
      "reel_dyn_like",
      "reel_dyn_dislike",
      "reel_dyn_comment",
      "reel_dyn_share",
      "reel_dyn_remix",
      "reel_channel_bar",
      "reel_subscribe_button",
      "reel_pivot_button",
      "shorts_pivot_button",
      "reel_player_info_panel",
    )

  private val SELECTED_SHORTS_TAB_IDS =
    setOf(
      "tab_shorts",
      "shorts_tab",
      "reel_tab",
    )

  private val NORMAL_WATCH_PLAYER_IDS =
    setOf(
      "watch_player",
      "player_fragment_container",
      "watch_while_time_bar_view",
      "time_bar_current_time",
      "time_bar_duration",
      "player_control_play_pause_replay_button",
      "watch_metadata_cards",
      "next_gen_watch_container",
      "video_metadata_layout",
    )

  private val BROWSE_OR_SEARCH_IDS =
    setOf(
      "search_edit_text",
      "search_results_list",
      "search_box",
    )

  private val PIVOT_OR_BOTTOM_BAR_IDS =
    setOf(
      "pivot_bar",
      "bottom_bar",
      "bottom_navigation",
      "tab_bar",
    )

  fun detect(
    packageName: String?,
    root: AccessibilityNodeSnapshot?,
  ): ShortsDetectionResult {
    if (packageName != YOUTUBE_PACKAGE_NAME) {
      return ShortsDetectionResult(
        classification = ShortsDetectionClassification.NOT_YOUTUBE,
        confidence = DetectionConfidence.NONE,
      )
    }

    if (root == null) {
      return ShortsDetectionResult(
        classification = ShortsDetectionClassification.UNCERTAIN,
        confidence = DetectionConfidence.NONE,
      )
    }

    val visibleNodes = collectVisibleNodes(root)
    if (visibleNodes.isEmpty()) {
      return ShortsDetectionResult(
        classification = ShortsDetectionClassification.UNCERTAIN,
        confidence = DetectionConfidence.NONE,
      )
    }

    val matchedContainerIds = mutableSetOf<String>()
    val matchedControlIds = mutableSetOf<String>()
    var hasSelectedShortsTab = false
    var hasNormalWatchSignal = false
    var hasBrowseOrSearchSignal = false
    var hasSemanticRemix = false
    var hasSemanticLike = false
    var hasSemanticComment = false
    var hasSemanticShare = false

    for (entry in visibleNodes) {
      val node = entry.node
      if (node.packageName != null && node.packageName != YOUTUBE_PACKAGE_NAME) {
        continue
      }

      val rawId = extractLocalResourceId(node.viewIdResourceName)
      if (rawId != null) {
        if (rawId in REEL_CONTAINER_IDS) {
          matchedContainerIds += rawId
        }
        if (rawId in SHORTS_CONTROL_IDS) {
          matchedControlIds += rawId
        }
        if (rawId in SELECTED_SHORTS_TAB_IDS && node.isSelected) {
          hasSelectedShortsTab = true
        }
        if (rawId in NORMAL_WATCH_PLAYER_IDS) {
          hasNormalWatchSignal = true
        }
        if (rawId in BROWSE_OR_SEARCH_IDS) {
          hasBrowseOrSearchSignal = true
        }
      }

      // Only treat a "Shorts" tab label as positive when the tab itself is actively selected AND
      // clearly inside a navigation bar or tab class (never a generic shelf title or feed label).
      if (!hasSelectedShortsTab && isSelectedShortsNavigationTab(node, rawId, entry.inBottomNav)) {
        hasSelectedShortsTab = true
      }

      // Semantic player action controls: only inspect short interactive labels (contentDescription
      // or button text), never long titles/descriptions.
      val label = semanticControlLabel(node)
      if (label != null) {
        when {
          label == "remix" || label.startsWith("remix this") || label == "remix sound" ->
            hasSemanticRemix = true
          label == "like" || label.startsWith("like this video") ->
            hasSemanticLike = true
          label == "comment" || label == "comments" || label.startsWith("view comments") ->
            hasSemanticComment = true
          label == "share" || label.startsWith("share this video") ->
            hasSemanticShare = true
        }
      }
    }

    val hasSemanticOverlayCluster =
      hasSemanticRemix && hasSemanticLike && (hasSemanticComment || hasSemanticShare)

    val matchedSignals = buildSet {
      if (matchedContainerIds.isNotEmpty()) add(ShortsSignalCategory.REEL_PLAYER_CONTAINER)
      if (matchedControlIds.isNotEmpty()) add(ShortsSignalCategory.SHORTS_PLAYER_CONTROLS)
      if (hasSelectedShortsTab) add(ShortsSignalCategory.SELECTED_SHORTS_TAB)
      if (hasSemanticOverlayCluster) add(ShortsSignalCategory.SEMANTIC_PLAYER_OVERLAY)
      if (hasNormalWatchSignal) add(ShortsSignalCategory.NORMAL_WATCH_PLAYER)
      if (hasBrowseOrSearchSignal) add(ShortsSignalCategory.BROWSE_OR_SEARCH_SURFACE)
    }

    // Disqualifier: normal video playback without an active Reel/Shorts container overlay must
    // always remain allowed, even if titles/cards mention "Shorts" or show a Remix button.
    if (hasNormalWatchSignal && matchedContainerIds.isEmpty()) {
      return ShortsDetectionResult(
        classification = ShortsDetectionClassification.NON_SHORTS_SCREEN,
        confidence = DetectionConfidence.HIGH,
        matchedSignals = matchedSignals,
      )
    }

    // Multi-signal confirmation paths:
    // 1. Active Reel/Shorts container ID + explicit Shorts player control IDs (covers tab, feed,
    //    and shared-link Shorts where bottom nav is absent).
    if (matchedContainerIds.isNotEmpty() &&
      (matchedControlIds.isNotEmpty() || hasSemanticOverlayCluster)
    ) {
      return ShortsDetectionResult(
        classification = ShortsDetectionClassification.CONFIRMED_SHORTS_PLAYER,
        confidence = DetectionConfidence.HIGH,
        matchedSignals = matchedSignals,
      )
    }

    // 2. Selected Shorts bottom tab + either a Reel container ID or Shorts player controls.
    if (hasSelectedShortsTab &&
      (matchedContainerIds.isNotEmpty() || matchedControlIds.isNotEmpty() || hasSemanticOverlayCluster)
    ) {
      return ShortsDetectionResult(
        classification = ShortsDetectionClassification.CONFIRMED_SHORTS_PLAYER,
        confidence = DetectionConfidence.HIGH,
        matchedSignals = matchedSignals,
      )
    }

    // 3. Multiple independent visible Reel/Shorts player surface IDs (e.g. shared link landing
    //    before dynamic side buttons finish inflating, while normal watch/search IDs are absent).
    if (matchedContainerIds.size >= 2 && !hasNormalWatchSignal && !hasBrowseOrSearchSignal) {
      return ShortsDetectionResult(
        classification = ShortsDetectionClassification.CONFIRMED_SHORTS_PLAYER,
        confidence = DetectionConfidence.MEDIUM,
        matchedSignals = matchedSignals,
      )
    }

    // Any single isolated or ambiguous signal fails safe as UNCERTAIN (never blocks).
    if (matchedContainerIds.isNotEmpty() || matchedControlIds.isNotEmpty() || hasSelectedShortsTab) {
      return ShortsDetectionResult(
        classification = ShortsDetectionClassification.UNCERTAIN,
        confidence = DetectionConfidence.LOW,
        matchedSignals = matchedSignals,
      )
    }

    return ShortsDetectionResult(
      classification = ShortsDetectionClassification.NON_SHORTS_SCREEN,
      confidence = DetectionConfidence.HIGH,
      matchedSignals = matchedSignals,
    )
  }

  private fun collectVisibleNodes(root: AccessibilityNodeSnapshot): List<NodeEntry> {
    val result = mutableListOf<NodeEntry>()
    val stack = ArrayDeque<Pair<AccessibilityNodeSnapshot, Boolean>>()
    if (root.isVisibleToUser) {
      stack.addLast(root to isBottomNavContainer(root))
    }
    while (stack.isNotEmpty()) {
      val (node, parentInBottomNav) = stack.removeLast()
      val currentInBottomNav = parentInBottomNav || isBottomNavContainer(node)
      result += NodeEntry(node = node, inBottomNav = currentInBottomNav)
      for (i in node.children.indices.reversed()) {
        val child = node.children[i]
        if (child.isVisibleToUser) {
          stack.addLast(child to currentInBottomNav)
        }
      }
    }
    return result
  }

  private fun isBottomNavContainer(node: AccessibilityNodeSnapshot): Boolean {
    val localId = extractLocalResourceId(node.viewIdResourceName)
    if (localId != null && localId in PIVOT_OR_BOTTOM_BAR_IDS) return true
    val cls = node.className.orEmpty()
    return cls.endsWith("BottomNavigationView") || cls.endsWith("TabLayout")
  }

  private fun isSelectedShortsNavigationTab(
    node: AccessibilityNodeSnapshot,
    localId: String?,
    inBottomNav: Boolean,
  ): Boolean {
    if (!node.isSelected) return false
    val cls = node.className.orEmpty()
    val looksLikeTab =
      inBottomNav ||
        localId in SELECTED_SHORTS_TAB_IDS ||
        cls.endsWith("Tab") ||
        cls.contains("ActionBar\$Tab")
    if (!looksLikeTab) return false

    val desc = node.contentDescription?.trim()?.lowercase(Locale.ROOT)
    val text = node.text?.trim()?.lowercase(Locale.ROOT)
    return desc == "shorts" || desc == "shorts tab" || text == "shorts"
  }

  private fun semanticControlLabel(node: AccessibilityNodeSnapshot): String? {
    val candidate =
      node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
        ?: node.text?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    if (candidate.length > 48) return null
    return candidate.lowercase(Locale.ROOT)
  }

  private fun extractLocalResourceId(resourceName: String?): String? {
    if (resourceName.isNullOrBlank()) return null
    val trimmed = resourceName.trim()
    val slashIdIndex = trimmed.indexOf(":id/")
    return if (slashIdIndex >= 0) {
      trimmed.substring(slashIdIndex + 4)
    } else {
      trimmed.substringAfterLast('/')
    }
  }

  private data class NodeEntry(
    val node: AccessibilityNodeSnapshot,
    val inBottomNav: Boolean,
  )
}
