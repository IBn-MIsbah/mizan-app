package com.example.platform.enforcement

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface YouTubeShortsFeedbackDisplayer {
  fun showShortsRestrictedFeedback()
}

object NoOpYouTubeShortsFeedbackDisplayer : YouTubeShortsFeedbackDisplayer {
  override fun showShortsRestrictedFeedback() = Unit
}

/**
 * Displays a brief, non-disruptive toast notification on the main thread when MIZAN redirects
 * the user out of YouTube Shorts. Uses a system Toast rather than a persistent notification so
 * normal YouTube viewing continues smoothly without requiring extra notification permissions.
 */
@Singleton
class AndroidYouTubeShortsFeedbackDisplayer
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
) : YouTubeShortsFeedbackDisplayer {

  private val mainHandler = Handler(Looper.getMainLooper())

  override fun showShortsRestrictedFeedback() {
    val message = context.getString(R.string.youtube_shorts_restricted_feedback)
    if (Looper.myLooper() == Looper.getMainLooper()) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    } else {
      mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
  }
}
