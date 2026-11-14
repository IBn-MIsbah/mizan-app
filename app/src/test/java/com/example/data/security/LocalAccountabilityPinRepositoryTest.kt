package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.SecurityEventType
import com.example.domain.pin.Pbkdf2PinHasher
import com.example.domain.pin.PinConfigureResult
import com.example.domain.pin.PinValidationError
import com.example.domain.pin.PinVerificationStatus
import com.example.domain.time.DomainDate
import com.example.domain.time.DomainTime
import com.example.domain.time.MonotonicTimestamp
import com.example.domain.time.WallClockPoint
import com.example.testing.FakeAccountabilityMetadataRepository
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeIdGenerator
import com.example.testing.FakePinCredentialCipher
import com.example.testing.FakeSecurityEventRepository
import com.example.testing.FakeTimeProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalAccountabilityPinRepositoryTest {

  private lateinit var context: Context
  private lateinit var cipher: FakePinCredentialCipher
  private lateinit var metadataRepo: FakeAccountabilityMetadataRepository
  private lateinit var securityEventRepo: FakeSecurityEventRepository
  private lateinit var timeProvider: FakeTimeProvider
  private lateinit var repo: LocalAccountabilityPinRepository

  private val baseWall =
    WallClockPoint(
      date = DomainDate(2026, 11, 13),
      time = DomainTime(10, 0, 0),
      epochMillis = 1_794_564_000_000L,
    )

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    File(context.noBackupFilesDir, LocalAccountabilityPinRepository.VAULT_FILE_NAME).delete()
    cipher = FakePinCredentialCipher()
    metadataRepo = FakeAccountabilityMetadataRepository()
    securityEventRepo = FakeSecurityEventRepository()
    timeProvider =
      FakeTimeProvider(initialWallClock = baseWall, initialMonotonic = MonotonicTimestamp(10_000L))
    repo =
      LocalAccountabilityPinRepository(
        context = context,
        pinHasher = Pbkdf2PinHasher(),
        cipher = cipher,
        accountabilityMetadataRepository = metadataRepo,
        securityEventRepository = securityEventRepo,
        timeProvider = timeProvider,
        idGenerator = FakeIdGenerator(),
        dispatcherProvider = FakeDispatcherProvider(),
      )
  }

  @After
  fun tearDown() {
    File(context.noBackupFilesDir, LocalAccountabilityPinRepository.VAULT_FILE_NAME).delete()
  }

  @Test
  fun `configure verify change and remove PIN store vault in noBackupFilesDir without plaintext PIN`() =
    runTest {
      assertFalse(repo.isCredentialAvailable())
      assertEquals(PinVerificationStatus.NotConfigured, repo.verifyPin("4829"))

      val bad = repo.configurePin("12")
      assertEquals(PinConfigureResult.InvalidFormat(PinValidationError.TOO_SHORT), bad)

      assertEquals(PinConfigureResult.Success, repo.configurePin("4829"))
      assertTrue(repo.isCredentialAvailable())
      assertTrue(metadataRepo.observeMetadata().first().let { (it as com.example.domain.repository.PersistenceResult.Success).data.isPinEnabled })

      val vaultFile = File(context.noBackupFilesDir, LocalAccountabilityPinRepository.VAULT_FILE_NAME)
      assertTrue(vaultFile.exists())
      val rawVault = vaultFile.readText()
      assertFalse(rawVault.contains("4829"))

      assertEquals(PinVerificationStatus.Verified, repo.verifyPin("4829"))

      // Change PIN verifies current PIN first
      assertEquals(PinConfigureResult.CurrentPinMismatch, repo.changePin("9999", "7351"))
      assertEquals(PinConfigureResult.Success, repo.changePin("4829", "7351"))
      assertEquals(PinVerificationStatus.Verified, repo.verifyPin("7351"))

      // Remove PIN clears vault and metadata
      assertEquals(PinConfigureResult.Success, repo.removePin("7351"))
      assertFalse(repo.isCredentialAvailable())
      assertFalse(vaultFile.exists())

      // Zero plaintext PIN leaked in security events
      securityEventRepo.recordedEvents.forEach { evt ->
        val s = evt.summary.orEmpty()
        assertFalse(s.contains("4829"))
        assertFalse(s.contains("7351"))
      }
      assertTrue(
        securityEventRepo.recordedEvents.any { it.eventType == SecurityEventType.PIN_CONFIGURED }
      )
      assertTrue(
        securityEventRepo.recordedEvents.any { it.eventType == SecurityEventType.PIN_REMOVED }
      )
    }

  @Test
  fun `progressive cooldown escalates after 3 failures and does not clear on clock regression`() =
    runTest {
      assertEquals(PinConfigureResult.Success, repo.configurePin("2468"))

      val f1 = repo.verifyPin("0000") as PinVerificationStatus.Failed
      assertEquals(2, f1.attemptsRemainingBeforeCooldown)
      assertEquals(0L, f1.lockoutRemainingMillis)

      val f2 = repo.verifyPin("0000") as PinVerificationStatus.Failed
      assertEquals(1, f2.attemptsRemainingBeforeCooldown)
      assertEquals(0L, f2.lockoutRemainingMillis)

      val f3 = repo.verifyPin("0000") as PinVerificationStatus.Failed
      assertEquals(0, f3.attemptsRemainingBeforeCooldown)
      assertEquals(30_000L, f3.lockoutRemainingMillis)

      // Backward clock manipulation must not bypass remaining lockout
      timeProvider.wallClock = baseWall.copy(epochMillis = baseWall.epochMillis - 600_000L)
      timeProvider.monotonic = MonotonicTimestamp(5_000L)
      val lockedStatus = repo.verifyPin("2468")
      assertEquals(PinVerificationStatus.LockedOut(30_000L), lockedStatus)

      // Advance both monotonic and wall clock past 30s cooldown -> unlocks and verifies
      timeProvider.wallClock = baseWall.copy(epochMillis = baseWall.epochMillis + 31_000L)
      timeProvider.monotonic = MonotonicTimestamp(41_000L)
      assertEquals(PinVerificationStatus.Verified, repo.verifyPin("2468"))
    }

  @Test
  fun `cipher failure fails closed with Unavailable status`() = runTest {
    assertEquals(PinConfigureResult.Success, repo.configurePin("5555"))
    cipher.failDecryptReason = "Simulated Keystore key invalidation"

    assertFalse(repo.isCredentialAvailable())
    val status = repo.verifyPin("5555")
    assertTrue(status is PinVerificationStatus.Unavailable)
  }
}
