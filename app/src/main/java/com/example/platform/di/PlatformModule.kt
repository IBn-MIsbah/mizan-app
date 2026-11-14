package com.example.platform.di

import android.content.Context
import com.example.domain.repository.InstalledAppRepository
import com.example.platform.apps.PackageManagerInstalledAppRepository
import com.example.platform.enforcement.AndroidBlockingUiLauncher
import com.example.platform.enforcement.AppPackageName
import com.example.platform.enforcement.BlockingUiLauncher
import com.example.platform.health.AndroidEnforcementHealthMonitor
import com.example.platform.health.AndroidUsageAccessMonitor
import com.example.platform.health.EnforcementHealthMonitor
import com.example.platform.health.UsageAccessMonitor
import com.example.platform.usage.AndroidBootSessionProvider
import com.example.platform.usage.AndroidDeviceInteractiveStateProvider
import com.example.platform.usage.AndroidUsageStatsHistorySource
import com.example.platform.usage.BootSessionProvider
import com.example.platform.usage.DeviceInteractiveStateProvider
import com.example.platform.usage.UsageStatsHistorySource
import com.example.platform.vpn.AndroidVpnFilterMonitor
import com.example.platform.vpn.VpnFilterMonitor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformBindingsModule {

  @Binds
  @Singleton
  abstract fun bindEnforcementHealthMonitor(
    impl: AndroidEnforcementHealthMonitor
  ): EnforcementHealthMonitor

  @Binds
  @Singleton
  abstract fun bindUsageAccessMonitor(
    impl: AndroidUsageAccessMonitor
  ): UsageAccessMonitor

  @Binds
  @Singleton
  abstract fun bindVpnFilterMonitor(
    impl: AndroidVpnFilterMonitor
  ): VpnFilterMonitor

  @Binds
  @Singleton
  abstract fun bindBlockingUiLauncher(impl: AndroidBlockingUiLauncher): BlockingUiLauncher

  @Binds
  @Singleton
  abstract fun bindInstalledAppRepository(
    impl: PackageManagerInstalledAppRepository
  ): InstalledAppRepository

  @Binds
  @Singleton
  abstract fun bindBootSessionProvider(
    impl: AndroidBootSessionProvider
  ): BootSessionProvider

  @Binds
  @Singleton
  abstract fun bindDeviceInteractiveStateProvider(
    impl: AndroidDeviceInteractiveStateProvider
  ): DeviceInteractiveStateProvider

  @Binds
  @Singleton
  abstract fun bindUsageStatsHistorySource(
    impl: AndroidUsageStatsHistorySource
  ): UsageStatsHistorySource
}

@Module
@InstallIn(SingletonComponent::class)
object PlatformProvidesModule {

  @Provides
  @Singleton
  @AppPackageName
  fun provideAppPackageName(@ApplicationContext context: Context): String = context.packageName
}
