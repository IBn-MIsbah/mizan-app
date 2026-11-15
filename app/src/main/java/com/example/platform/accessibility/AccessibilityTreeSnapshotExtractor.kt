package com.example.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Safely extracts a strictly bounded [AccessibilityNodeSnapshot] from Android's active window
 * root without logging or retaining any window content.
 */
object AccessibilityTreeSnapshotExtractor {

  const val DEFAULT_MAX_DEPTH: Int = 18
  const val DEFAULT_MAX_NODES: Int = 250

  fun capture(
    root: AccessibilityNodeInfo?,
    expectedPackageName: String,
    maxDepth: Int = DEFAULT_MAX_DEPTH,
    maxNodes: Int = DEFAULT_MAX_NODES,
  ): AccessibilityNodeSnapshot? {
    if (root == null) return null
    val budget = NodeBudget(maxNodes)
    return runCatching {
      captureNode(
        node = root,
        expectedPackageName = expectedPackageName,
        depth = 0,
        maxDepth = maxDepth,
        budget = budget,
      )
    }.getOrNull()
  }

  private fun captureNode(
    node: AccessibilityNodeInfo,
    expectedPackageName: String,
    depth: Int,
    maxDepth: Int,
    budget: NodeBudget,
  ): AccessibilityNodeSnapshot? {
    if (!budget.tryConsume()) return null

    val visible = runCatching { node.isVisibleToUser }.getOrDefault(true)
    val childCount =
      if (depth < maxDepth && visible) {
        runCatching { node.childCount }.getOrDefault(0).coerceAtLeast(0)
      } else {
        0
      }

    val children =
      if (childCount > 0) {
        buildList {
          for (i in 0 until childCount) {
            if (!budget.hasRemaining()) break
            val childNode = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val childSnapshot =
              captureNode(
                node = childNode,
                expectedPackageName = expectedPackageName,
                depth = depth + 1,
                maxDepth = maxDepth,
                budget = budget,
              )
            if (childSnapshot != null) {
              add(childSnapshot)
            }
          }
        }
      } else {
        emptyList()
      }

    return AccessibilityNodeSnapshot(
      packageName = runCatching { node.packageName?.toString() }.getOrNull() ?: expectedPackageName,
      viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
      className = runCatching { node.className?.toString() }.getOrNull(),
      contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
      text = runCatching { node.text?.toString() }.getOrNull(),
      isSelected = runCatching { node.isSelected }.getOrDefault(false),
      isClickable = runCatching { node.isClickable }.getOrDefault(false),
      isVisibleToUser = visible,
      children = children,
    )
  }

  private class NodeBudget(private var remaining: Int) {
    fun tryConsume(): Boolean {
      if (remaining <= 0) return false
      remaining -= 1
      return true
    }

    fun hasRemaining(): Boolean = remaining > 0
  }
}
