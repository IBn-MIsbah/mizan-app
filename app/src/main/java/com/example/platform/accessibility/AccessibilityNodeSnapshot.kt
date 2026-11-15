package com.example.platform.accessibility

/**
 * Lightweight, immutable, UI-agnostic snapshot of visible accessibility node metadata used for
 * in-memory structural detection (for example, distinguishing the active YouTube Shorts player
 * from normal YouTube screens).
 *
 * Security & privacy guarantee:
 * - Snapshots are transient in-memory values only; they are never serialized, persisted, or logged.
 */
data class AccessibilityNodeSnapshot(
  val packageName: String? = null,
  val viewIdResourceName: String? = null,
  val className: String? = null,
  val contentDescription: String? = null,
  val text: String? = null,
  val isSelected: Boolean = false,
  val isClickable: Boolean = false,
  val isVisibleToUser: Boolean = true,
  val children: List<AccessibilityNodeSnapshot> = emptyList(),
)
