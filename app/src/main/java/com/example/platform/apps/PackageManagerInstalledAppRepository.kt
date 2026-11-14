package com.example.platform.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import com.example.core.common.DispatcherProvider
import com.example.domain.model.AppId
import com.example.domain.model.InstalledAppInfo
import com.example.domain.repository.InstalledAppRepository
import com.example.domain.repository.InstalledAppsResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

@Singleton
class PackageManagerInstalledAppRepository
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val dispatcherProvider: DispatcherProvider,
) : InstalledAppRepository {

  override suspend fun getLaunchableApps(): InstalledAppsResult =
    withContext(dispatcherProvider.io) {
      try {
        val pm = context.packageManager
        val launcherIntent =
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolvedLaunchers = queryIntentActivitiesCompat(pm, launcherIntent)

        val homePackages = resolveHomeLauncherPackages(pm)
        val excludedSystemAdminPackages =
          homePackages +
            resolveSettingsPackages(pm) +
            setOf(
              context.packageName,
              "com.android.systemui",
              "com.android.packageinstaller",
              "com.google.android.packageinstaller",
            )

        val essentialPackages = resolveEssentialPackages(pm)

        val apps =
          resolvedLaunchers
            .mapNotNull { info ->
              val pkg = info.activityInfo?.packageName?.trim().orEmpty()
              if (pkg.isBlank() || pkg in excludedSystemAdminPackages) {
                return@mapNotNull null
              }
              val label =
                runCatching { info.loadLabel(pm).toString().trim() }
                  .getOrNull()
                  .takeUnless { it.isNullOrBlank() } ?: pkg
              InstalledAppInfo(
                appId = AppId(pkg),
                displayLabel = label,
                isSystemEssential = pkg in essentialPackages,
              )
            }
            .distinctBy { it.appId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayLabel })

        InstalledAppsResult.Success(apps)
      } catch (_: RuntimeException) {
        InstalledAppsResult.Failure("Unable to load installed launchable applications.")
      }
    }

  private fun resolveHomeLauncherPackages(pm: PackageManager): Set<String> {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return queryIntentActivitiesCompat(pm, homeIntent)
      .mapNotNull { it.activityInfo?.packageName?.takeIf { pkg -> pkg.isNotBlank() } }
      .toSet()
  }

  private fun resolveSettingsPackages(pm: PackageManager): Set<String> {
    val settingsIntent = Intent(Settings.ACTION_SETTINGS)
    return queryIntentActivitiesCompat(pm, settingsIntent)
      .mapNotNull { it.activityInfo?.packageName?.takeIf { pkg -> pkg.isNotBlank() } }
      .toSet()
  }

  private fun resolveEssentialPackages(pm: PackageManager): Set<String> {
    val essential = mutableSetOf<String>()

    runCatching {
      val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
      telecom?.defaultDialerPackage?.takeIf { it.isNotBlank() }?.let { essential.add(it) }
    }
    queryIntentActivitiesCompat(pm, Intent(Intent.ACTION_DIAL))
      .mapNotNull { it.activityInfo?.packageName?.takeIf { pkg -> pkg.isNotBlank() } }
      .forEach { essential.add(it) }

    runCatching {
      Telephony.Sms.getDefaultSmsPackage(context)?.takeIf { it.isNotBlank() }?.let {
        essential.add(it)
      }
    }

    return essential
  }

  private fun queryIntentActivitiesCompat(pm: PackageManager, intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
      @Suppress("DEPRECATION")
      pm.queryIntentActivities(intent, 0)
    }
}
