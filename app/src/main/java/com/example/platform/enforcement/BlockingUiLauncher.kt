package com.example.platform.enforcement

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.example.domain.evaluator.RestrictionReason
import com.example.feature.blocking.BlockingActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class BlockingLaunchRequest(
  val packageName: String,
  val appLabel: String?,
  val reason: RestrictionReason,
  val commitmentId: String,
)

interface BlockingUiLauncher {
  fun launchBlockingScreen(request: BlockingLaunchRequest): Boolean
}

@Singleton
class AndroidBlockingUiLauncher
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
) : BlockingUiLauncher {

  override fun launchBlockingScreen(request: BlockingLaunchRequest): Boolean {
    val intent =
      Intent(context, BlockingActivity::class.java).apply {
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        putExtra(BlockingActivity.EXTRA_BLOCKED_PACKAGE, request.packageName)
        putExtra(BlockingActivity.EXTRA_BLOCKED_APP_LABEL, request.appLabel)
        putExtra(BlockingActivity.EXTRA_RESTRICTION_REASON, request.reason.name)
        putExtra(BlockingActivity.EXTRA_COMMITMENT_ID, request.commitmentId)
      }
    return try {
      context.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      false
    } catch (_: SecurityException) {
      false
    } catch (_: RuntimeException) {
      false
    }
  }
}
