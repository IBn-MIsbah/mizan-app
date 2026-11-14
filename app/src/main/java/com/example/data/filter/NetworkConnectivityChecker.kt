package com.example.data.filter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateNetworkEligibility {
  data object Eligible : UpdateNetworkEligibility

  data class Ineligible(
    val reason: String,
    val isTransientOffline: Boolean,
  ) : UpdateNetworkEligibility
}

fun interface UpdateNetworkPolicyChecker {
  fun checkEligibility(wifiOnly: Boolean): UpdateNetworkEligibility
}

@Singleton
class AndroidUpdateNetworkPolicyChecker
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
) : UpdateNetworkPolicyChecker {

  override fun checkEligibility(wifiOnly: Boolean): UpdateNetworkEligibility {
    val cm =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return UpdateNetworkEligibility.Ineligible(
          reason = "Network connectivity service is unavailable.",
          isTransientOffline = true,
        )

    val activeNetwork =
      cm.activeNetwork
        ?: return UpdateNetworkEligibility.Ineligible(
          reason = "Device is offline; locally cached datasets and custom rules remain active.",
          isTransientOffline = true,
        )

    val caps =
      runCatching { cm.getNetworkCapabilities(activeNetwork) }.getOrNull()
        ?: return UpdateNetworkEligibility.Ineligible(
          reason = "Unable to inspect active network capabilities; offline cache preserved.",
          isTransientOffline = true,
        )

    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
      return UpdateNetworkEligibility.Ineligible(
        reason = "No internet connection available; locally cached datasets remain active.",
        isTransientOffline = true,
      )
    }

    if (wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
      return UpdateNetworkEligibility.Ineligible(
        reason =
          "Wi-Fi-only update policy active and current network is metered; update deferred.",
        isTransientOffline = true,
      )
    }

    return UpdateNetworkEligibility.Eligible
  }
}
