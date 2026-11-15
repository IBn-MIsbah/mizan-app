package com.example.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeShortsDetectorTest {

  private fun ytNode(
    id: String? = null,
    className: String? = "android.view.ViewGroup",
    desc: String? = null,
    text: String? = null,
    selected: Boolean = false,
    clickable: Boolean = false,
    visible: Boolean = true,
    children: List<AccessibilityNodeSnapshot> = emptyList(),
  ): AccessibilityNodeSnapshot =
    AccessibilityNodeSnapshot(
      packageName = YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME,
      viewIdResourceName = id?.let { "${YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME}:id/$it" },
      className = className,
      contentDescription = desc,
      text = text,
      isSelected = selected,
      isClickable = clickable,
      isVisibleToUser = visible,
      children = children,
    )

  private fun bottomNav(shortsSelected: Boolean): AccessibilityNodeSnapshot =
    ytNode(
      id = "pivot_bar",
      className = "com.google.android.material.bottomnavigation.BottomNavigationView",
      children =
        listOf(
          ytNode(id = "tab_home", className = "android.app.ActionBar\$Tab", desc = "Home", selected = !shortsSelected),
          ytNode(id = "tab_shorts", className = "android.app.ActionBar\$Tab", desc = "Shorts", selected = shortsSelected),
          ytNode(id = "tab_subs", className = "android.app.ActionBar\$Tab", desc = "Subscriptions", selected = false),
        ),
    )

  @Test
  fun `youtube home screen with shorts shelf and unselected shorts tab is allowed`() {
    val homeTree =
      ytNode(
        id = "coordinator",
        children =
          listOf(
            ytNode(
              id = "results",
              children =
                listOf(
                  ytNode(id = "shelf_header", text = "Shorts"),
                  ytNode(id = "video_card", desc = "Kotlin Tip Shorts preview", clickable = true),
                ),
            ),
            bottomNav(shortsSelected = false),
          ),
      )

    val result = YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, homeTree)
    assertEquals(ShortsDetectionClassification.NON_SHORTS_SCREEN, result.classification)
    assertFalse(result.isConfirmedShorts)
  }

  @Test
  fun `youtube search screen with shorts query and results is allowed`() {
    val searchTree =
      ytNode(
        id = "coordinator",
        children =
          listOf(
            ytNode(id = "search_edit_text", text = "productivity shorts"),
            ytNode(
              id = "search_results_list",
              children =
                listOf(
                  ytNode(id = "video_title", text = "Best Shorts Compilation"),
                  ytNode(id = "shelf_header", text = "Shorts"),
                ),
            ),
            bottomNav(shortsSelected = false),
          ),
      )

    val result = YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, searchTree)
    assertEquals(ShortsDetectionClassification.NON_SHORTS_SCREEN, result.classification)
    assertFalse(result.isConfirmedShorts)
  }

  @Test
  fun `normal long form video playback with comments open is allowed`() {
    val watchTree =
      ytNode(
        id = "next_gen_watch_container",
        children =
          listOf(
            ytNode(
              id = "watch_player",
              children =
                listOf(
                  ytNode(id = "time_bar_current_time", text = "04:12"),
                  ytNode(id = "time_bar_duration", text = "24:00"),
                  ytNode(id = "player_control_play_pause_replay_button", desc = "Pause video"),
                ),
            ),
            ytNode(
              id = "watch_metadata_cards",
              children =
                listOf(
                  ytNode(text = "Deep Work Documentary - Why Shorts Hurt Focus"),
                  ytNode(desc = "Like", clickable = true),
                  ytNode(desc = "Remix", clickable = true),
                  ytNode(desc = "Share", clickable = true),
                  ytNode(desc = "Comments", clickable = true),
                ),
            ),
          ),
      )

    val result = YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, watchTree)
    assertEquals(ShortsDetectionClassification.NON_SHORTS_SCREEN, result.classification)
    assertFalse(result.isConfirmedShorts)
  }

  @Test
  fun `youtube channel page with shorts shelf is allowed`() {
    val channelTree =
      ytNode(
        id = "channel_container",
        children =
          listOf(
            ytNode(text = "Channel Name"),
            ytNode(className = "android.app.ActionBar\$Tab", text = "Videos", selected = true),
            ytNode(className = "android.app.ActionBar\$Tab", text = "Shorts", selected = false),
            ytNode(text = "Latest Shorts"),
            bottomNav(shortsSelected = false),
          ),
      )

    val result = YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, channelTree)
    assertEquals(ShortsDetectionClassification.NON_SHORTS_SCREEN, result.classification)
    assertFalse(result.isConfirmedShorts)
  }

  @Test
  fun `active youtube shorts player opened from shorts tab or feed is confirmed`() {
    val shortsFeedTree =
      ytNode(
        id = "reel_watch_fragment_root",
        children =
          listOf(
            ytNode(
              id = "reel_recycler",
              children =
                listOf(
                  ytNode(id = "reel_player_page_container"),
                  ytNode(id = "reel_dyn_like", desc = "Like this video", clickable = true),
                  ytNode(id = "reel_dyn_comment", desc = "View comments", clickable = true),
                  ytNode(id = "reel_dyn_share", desc = "Share this video", clickable = true),
                  ytNode(id = "reel_dyn_remix", desc = "Remix", clickable = true),
                ),
            ),
            bottomNav(shortsSelected = true),
          ),
      )

    val result = YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, shortsFeedTree)
    assertEquals(ShortsDetectionClassification.CONFIRMED_SHORTS_PLAYER, result.classification)
    assertEquals(DetectionConfidence.HIGH, result.confidence)
    assertTrue(result.isConfirmedShorts)
    assertTrue(ShortsSignalCategory.REEL_PLAYER_CONTAINER in result.matchedSignals)
    assertTrue(ShortsSignalCategory.SHORTS_PLAYER_CONTROLS in result.matchedSignals)
    assertTrue(ShortsSignalCategory.SELECTED_SHORTS_TAB in result.matchedSignals)
  }

  @Test
  fun `youtube shorts opened via shared link without bottom nav tab is confirmed`() {
    val sharedLinkShortsTree =
      ytNode(
        id = "reel_watch_fragment_root",
        children =
          listOf(
            ytNode(id = "reel_player_page_container"),
            ytNode(id = "reel_progress_bar"),
            ytNode(id = "reel_channel_bar", desc = "@creator"),
            ytNode(desc = "Like", clickable = true),
            ytNode(desc = "Comments", clickable = true),
            ytNode(desc = "Remix", clickable = true),
            ytNode(desc = "Share", clickable = true),
          ),
      )

    val result =
      YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, sharedLinkShortsTree)
    assertEquals(ShortsDetectionClassification.CONFIRMED_SHORTS_PLAYER, result.classification)
    assertTrue(result.isConfirmedShorts)
  }

  @Test
  fun `bottom nav shorts tab visible while unselected never triggers detection`() {
    val navOnlyTree =
      ytNode(
        id = "root",
        children = listOf(bottomNav(shortsSelected = false)),
      )

    val result = YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, navOnlyTree)
    assertEquals(ShortsDetectionClassification.NON_SHORTS_SCREEN, result.classification)
    assertFalse(result.isConfirmedShorts)
  }

  @Test
  fun `unrelated screen text containing Shorts does not trigger false positive`() {
    val textOnlyTree =
      ytNode(
        id = "feed",
        children =
          listOf(
            ytNode(text = "How to wear linen Shorts in summer"),
            ytNode(desc = "Shorts shopping guide"),
          ),
      )

    val result = YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, textOnlyTree)
    assertEquals(ShortsDetectionClassification.NON_SHORTS_SCREEN, result.classification)
    assertFalse(result.isConfirmedShorts)
  }

  @Test
  fun `missing null or completely invisible node trees fail safe as UNCERTAIN`() {
    assertEquals(
      ShortsDetectionClassification.UNCERTAIN,
      YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, null).classification,
    )
    assertEquals(
      ShortsDetectionClassification.UNCERTAIN,
      YouTubeShortsDetector.detect(
        YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME,
        ytNode(id = "reel_watch_fragment_root", visible = false),
      ).classification,
    )
  }

  @Test
  fun `single partial or changed signal after hypothetical YouTube update fails open as UNCERTAIN`() {
    val singleSignalTree =
      ytNode(
        id = "unknown_root",
        children =
          listOf(
            ytNode(id = "reel_dyn_like", desc = "Like", clickable = true),
            ytNode(id = "new_unrecognized_player_v99"),
          ),
      )

    val result =
      YouTubeShortsDetector.detect(YouTubeShortsDetector.YOUTUBE_PACKAGE_NAME, singleSignalTree)
    assertEquals(ShortsDetectionClassification.UNCERTAIN, result.classification)
    assertFalse(result.isConfirmedShorts)
  }

  @Test
  fun `non youtube packages return NOT_YOUTUBE even if ids overlap`() {
    val spoofedPackageTree =
      AccessibilityNodeSnapshot(
        packageName = "com.android.chrome",
        viewIdResourceName = "com.android.chrome:id/reel_watch_fragment_root",
        isVisibleToUser = true,
      )

    val result = YouTubeShortsDetector.detect("com.android.chrome", spoofedPackageTree)
    assertEquals(ShortsDetectionClassification.NOT_YOUTUBE, result.classification)
    assertFalse(result.isConfirmedShorts)
  }
}
