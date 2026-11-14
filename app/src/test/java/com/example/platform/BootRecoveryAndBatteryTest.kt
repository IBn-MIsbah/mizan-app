package com.example.platform

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.AppId
import com.example.domain.model.AppPreferences
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DomainCategory
import com.example.domain.model.PersistedActiveUsageSession
import com.example.domain.model.RestrictedApp
import com.example.domain.model.RestrictionMode
import com.example.domain.model.SecurityEventType
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import com.example.platform.health.AndroidBatteryOptimizationMonitor
import com.example.platform.health.BatteryOptimizationStatus
import com.example.platform.recovery.BootRecoveryCoordinator
import com.example.testing.FakeBatteryOptimizationMonitor
import com.example.testing.FakeCommitmentRepository
import com.example.testing.FakeDailyUsageRepository
import com.example.testing.FakeEnforcementHealthMonitor
import com.example.testing.FakeIdGenerator
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUsageAccessMonitor
import com.example.testing.FakeUserPreferencesRepository
import com.example.testing.FakeVpnFilterMonitor
import com.example.testing.createTestUsageTracker
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BootRecoveryAndBatteryTest {

  private val baseWall =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(10, 0, 0),
      epochMillis = 1_794_564_000_000L,
    )

  @Test
  fun `AndroidBatteryOptimizationMonitor reports OPTIMIZED or UNRESTRICTED and creates Play-compliant intents`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val shadowPower = Shadows.shadowOf(powerManager)
    val timeProvider = FakeTimeProvider(initialWallClock = baseWall)

    shadowPower.setIgnoringBatteryOptimizations(context.packageName, false)
    val monitor = AndroidBatteryOptimizationMonitor(context, timeProvider)
    assertEquals(BatteryOptimizationStatus.OPTIMIZED, monitor.batteryState.value.status)

    shadowPower.setIgnoringBatteryOptimizations(context.packageName, true)
    monitor.refreshSystemStatus()
    assertEquals(BatteryOptimizationStatus.UNRESTRICTED, monitor.batteryState.value.status)

    val batteryIntent = monitor.createBatteryOptimizationSettingsIntent()
    assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, batteryIntent.action)
    // Must NEVER request exemption permission dialog programmatically
    assertTrue(batteryIntent.action != Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)

    val detailsIntent = monitor.createAppDetailsSettingsIntent()
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, detailsIntent.action)
    assertEquals(context.packageName, detailsIntent.data?.schemeSpecificPart)
  }

  @Test
  fun `BootRecoveryCoordinator recovers interrupted session refreshes monitors and records non-sensitive event`() =
    runTest {
      val draft =
        CommitmentStateMachine.createDraft(
          id = CommitmentId("c-boot-1"),
          rules =
            CommitmentRules(
              mode = RestrictionMode.HYBRID,
              restrictedApps = setOf(RestrictedApp(AppId("com.example.social"), "Social")),
              dailyAllowance = 30.minutes,
              websiteFilteringEnabled = true,
              blockedDomainCategories = setOf(DomainCategory.SOCIAL_MEDIA),
            ),
          now = baseWall,
        )
      val active =
        (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = baseWall)
            as CommitmentResult.Success)
          .commitment

      val commitmentRepo = FakeCommitmentRepository(listOf(active))
      val prefsRepo =
        FakeUserPreferencesRepository(
          AppPreferences(
            onboardingCompleted = true,
            websiteFilteringConsented = true,
            vpnStoppedByUser = false,
          )
        )
      val securityRepo = FakeSecurityEventRepository()
      val usageRepo = FakeDailyUsageRepository()
      usageRepo.saveActiveUsageSession(
        PersistedActiveUsageSession(
          commitmentId = active.id,
          appId = AppId("com.example.social"),
          startedAtWallClock = baseWall,
          startedAtMonotonic = MonotonicTimestamp(1_000L),
          bootSessionToken = "boot:old",
        )
      )
      val timeProvider =
        FakeTimeProvider(
          initialWallClock =
            WallClockPoint(
              DomainDate(2026, 11, 13),
              DomainTime(10, 5, 0),
              baseWall.epochMillis + 300_000L,
            ),
          initialMonotonic = MonotonicTimestamp(2_000L),
        )
      val usageTracker =
        createTestUsageTracker(
          dailyUsageRepository = usageRepo,
          securityEventRepository = securityRepo,
          timeProvider = timeProvider,
        )
      val enforcementMonitor = FakeEnforcementHealthMonitor()
      val usageAccessMonitor = FakeUsageAccessMonitor()
      val batteryMonitor = FakeBatteryOptimizationMonitor()
      val vpnMonitor = FakeVpnFilterMonitor()

      val coordinator =
        BootRecoveryCoordinator(
          commitmentRepository = commitmentRepo,
          userPreferencesRepository = prefsRepo,
          securityEventRepository = securityRepo,
          usageTracker = usageTracker,
          enforcementHealthMonitor = enforcementMonitor,
          usageAccessMonitor = usageAccessMonitor,
          batteryOptimizationMonitor = batteryMonitor,
          vpnFilterMonitor = vpnMonitor,
          timeProvider = timeProvider,
          idGenerator = FakeIdGenerator(),
        )

      coordinator.handleRecovery(Intent.ACTION_BOOT_COMPLETED)

      assertEquals(1, enforcementMonitor.refreshCount)
      assertEquals(1, usageAccessMonitor.refreshCount)
      assertEquals(1, batteryMonitor.refreshCount)
      assertEquals(1, vpnMonitor.refreshCalls)
      assertEquals(1, vpnMonitor.startRequests)
      assertNull((usageRepo.getActiveUsageSession() as PersistenceResult.Success).data)
      assertTrue(
        securityRepo.recordedEvents.any {
          it.eventType == SecurityEventType.DEVICE_REBOOT_RECOVERED
        }
      )

      // Update action also logs APP_UPDATED_RECOVERED
      coordinator.handleRecovery(Intent.ACTION_MY_PACKAGE_REPLACED)
      assertTrue(
        securityRepo.recordedEvents.any {
          it.eventType == SecurityEventType.APP_UPDATED_RECOVERED
        }
      )
    }
}
