package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = HiltTestApplication::class)
class ExampleRobolectricTest {

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  @Test
  fun `app metadata and package id match mizan`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("Mizan", context.getString(R.string.app_name))
    assertEquals("com.ibnmisbah.mizan", context.packageName)
  }

  @Test
  fun `first launch onboarding completes into dashboard and top level navigation works`() {
    composeTestRule.waitUntilTag("onboarding_screen_list")
    composeTestRule.onNodeWithTag("onboarding_intro_card").assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("onboarding_screen_list")
      .performScrollToNode(hasTestTag("onboarding_skip_button"))
    composeTestRule.onNodeWithTag("onboarding_skip_button").performClick()

    composeTestRule.waitUntilTag("dashboard_empty_card")
    composeTestRule.onNodeWithTag("dashboard_empty_card").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_commitments_item").performClick()
    composeTestRule.waitUntilTag("setup_mode_card")
    composeTestRule.onNodeWithTag("setup_mode_card").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_settings_item").performClick()
    composeTestRule.waitUntilTag("settings_screen_list")
    composeTestRule
      .onNodeWithTag("settings_screen_list")
      .performScrollToNode(hasTestTag("settings_build_card"))
    composeTestRule
      .onNodeWithTag("settings_application_id_text")
      .assertIsDisplayed()
      .assertTextEquals("com.ibnmisbah.mizan")
  }
}

internal fun ComposeTestRule.waitUntilTag(tag: String, timeoutMillis: Long = 5_000L) {
  waitForIdle()
  waitUntil(timeoutMillis = timeoutMillis) {
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
  }
  waitForIdle()
}
