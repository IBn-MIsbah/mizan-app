package com.example.data.security

import android.content.Context
import com.example.core.common.DispatcherProvider
import com.example.core.common.IdGenerator
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.model.SecurityEvent
import com.example.domain.model.SecurityEventId
import com.example.domain.model.SecurityEventType
import com.example.domain.pin.AccountabilityPinRepository
import com.example.domain.pin.PinConfigureResult
import com.example.domain.pin.PinFormatValidator
import com.example.domain.pin.PinHashRecord
import com.example.domain.pin.PinHasher
import com.example.domain.pin.PinVerificationStatus
import com.example.domain.repository.AccountabilityMetadataRepository
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import com.example.domain.time.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class VaultPayloadDto(
  val saltBase64: String,
  val hashBase64: String,
  val algorithm: String,
  val iterations: Int,
)

@Serializable
internal data class VaultEnvelopeDto(
  val version: Int = 1,
  val ivBase64: String,
  val ciphertextBase64: String,
  val failedAttempts: Int = 0,
  val lockoutRemainingAtLastFailMillis: Long = 0L,
  val lastFailedWallEpochMillis: Long = 0L,
  val lastFailedMonotonicMillis: Long = 0L,
)

@Singleton
class LocalAccountabilityPinRepository
@Inject
constructor(
  @param:ApplicationContext private val context: Context,
  private val pinHasher: PinHasher,
  private val cipher: PinCredentialCipher,
  private val accountabilityMetadataRepository: AccountabilityMetadataRepository,
  private val securityEventRepository: SecurityEventRepository,
  private val timeProvider: TimeProvider,
  private val idGenerator: IdGenerator,
  private val dispatcherProvider: DispatcherProvider,
) : AccountabilityPinRepository {

  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }

  private val vaultFile: File
    get() = File(context.noBackupFilesDir, VAULT_FILE_NAME)

  override suspend fun isCredentialAvailable(): Boolean =
    mutex.withLock {
      withContext(dispatcherProvider.io) {
        val envelope = readEnvelopeLocked() ?: return@withContext false
        decryptPayloadLocked(envelope) != null
      }
    }

  override suspend fun currentLockoutRemainingMillis(): Long =
    mutex.withLock {
      withContext(dispatcherProvider.io) {
        val envelope = readEnvelopeLocked() ?: return@withContext 0L
        computeRemainingLockoutMillis(envelope)
      }
    }

  override suspend fun configurePin(newPin: String): PinConfigureResult =
    mutex.withLock {
      val formatError = PinFormatValidator.validate(newPin)
      if (formatError != null) {
        return@withLock PinConfigureResult.InvalidFormat(formatError)
      }

      withContext(dispatcherProvider.io) {
        val existing = readEnvelopeLocked()
        if (existing != null) {
          val remainingLockout = computeRemainingLockoutMillis(existing)
          if (remainingLockout > 0L) {
            return@withContext PinConfigureResult.LockedOut(remainingLockout)
          }
        }

        writeNewCredentialLocked(newPin, SecurityEventType.PIN_CONFIGURED, "Local accountability PIN configured")
      }
    }

  override suspend fun changePin(currentPin: String, newPin: String): PinConfigureResult =
    mutex.withLock {
      val newFormatError = PinFormatValidator.validate(newPin)
      if (newFormatError != null) {
        return@withLock PinConfigureResult.InvalidFormat(newFormatError)
      }

      withContext(dispatcherProvider.io) {
        when (val verifyResult = verifyPinLocked(currentPin)) {
          PinVerificationStatus.NotConfigured -> PinConfigureResult.PinNotConfigured
          is PinVerificationStatus.LockedOut ->
            PinConfigureResult.LockedOut(verifyResult.remainingMillis)
          is PinVerificationStatus.Unavailable ->
            PinConfigureResult.StorageFailure(verifyResult.reason)
          is PinVerificationStatus.Failed ->
            if (verifyResult.lockoutRemainingMillis > 0L) {
              PinConfigureResult.LockedOut(verifyResult.lockoutRemainingMillis)
            } else {
              PinConfigureResult.CurrentPinMismatch
            }
          PinVerificationStatus.Verified ->
            writeNewCredentialLocked(newPin, SecurityEventType.PIN_CONFIGURED, "Local accountability PIN updated")
        }
      }
    }

  override suspend fun removePin(currentPin: String): PinConfigureResult =
    mutex.withLock {
      withContext(dispatcherProvider.io) {
        when (val verifyResult = verifyPinLocked(currentPin)) {
          PinVerificationStatus.NotConfigured -> PinConfigureResult.PinNotConfigured
          is PinVerificationStatus.LockedOut ->
            PinConfigureResult.LockedOut(verifyResult.remainingMillis)
          is PinVerificationStatus.Unavailable ->
            PinConfigureResult.StorageFailure(verifyResult.reason)
          is PinVerificationStatus.Failed ->
            if (verifyResult.lockoutRemainingMillis > 0L) {
              PinConfigureResult.LockedOut(verifyResult.lockoutRemainingMillis)
            } else {
              PinConfigureResult.CurrentPinMismatch
            }
          PinVerificationStatus.Verified -> {
            clearVaultAndMetadataLocked(
              eventType = SecurityEventType.PIN_REMOVED,
              summary = "Local accountability PIN removed after verification",
            )
          }
        }
      }
    }

  override suspend fun verifyPin(pin: String): PinVerificationStatus =
    mutex.withLock {
      withContext(dispatcherProvider.io) {
        verifyPinLocked(pin)
      }
    }

  override suspend fun clearPinOnVerifiedRemoval(): PersistenceResult<Unit> =
    mutex.withLock {
      withContext(dispatcherProvider.io) {
        vaultFile.delete()
        accountabilityMetadataRepository.updateMetadata(
          AccountabilityMetadata(
            isPinEnabled = false,
            updatedAtEpochMillis = timeProvider.currentWallClock().epochMillis,
          )
        )
      }
    }

  private suspend fun verifyPinLocked(pin: String): PinVerificationStatus {
    val envelope = readEnvelopeLocked() ?: return PinVerificationStatus.NotConfigured
    val remainingLockout = computeRemainingLockoutMillis(envelope)
    if (remainingLockout > 0L) {
      return PinVerificationStatus.LockedOut(remainingLockout)
    }

    val payload =
      decryptPayloadLocked(envelope)
        ?: return PinVerificationStatus.Unavailable(
          "Stored PIN credential could not be decrypted via Android Keystore."
        )

    val hashRecord =
      try {
        PinHashRecord(
          saltBase64 = payload.saltBase64,
          hashBase64 = payload.hashBase64,
          algorithm = payload.algorithm,
          iterations = payload.iterations,
        )
      } catch (_: IllegalArgumentException) {
        return PinVerificationStatus.Unavailable("Stored PIN credential metadata is invalid.")
      }

    val chars = pin.toCharArray()
    val matches =
      try {
        pinHasher.verify(chars, hashRecord)
      } finally {
        chars.fill('\u0000')
      }

    if (matches) {
      if (envelope.failedAttempts != 0 || envelope.lockoutRemainingAtLastFailMillis != 0L) {
        writeEnvelopeLocked(
          envelope.copy(
            failedAttempts = 0,
            lockoutRemainingAtLastFailMillis = 0L,
            lastFailedWallEpochMillis = 0L,
            lastFailedMonotonicMillis = 0L,
          )
        )
      }
      return PinVerificationStatus.Verified
    }

    val nextAttempts = envelope.failedAttempts + 1
    val lockoutMillis = cooldownForAttempts(nextAttempts)
    val nowWall = timeProvider.currentWallClock()
    val nowMono = timeProvider.currentMonotonic()

    writeEnvelopeLocked(
      envelope.copy(
        failedAttempts = nextAttempts,
        lockoutRemainingAtLastFailMillis = lockoutMillis,
        lastFailedWallEpochMillis = nowWall.epochMillis,
        lastFailedMonotonicMillis = nowMono.elapsedMillis,
      )
    )

    runCatching {
      securityEventRepository.recordEvent(
        SecurityEvent(
          id = SecurityEventId(idGenerator.nextId()),
          commitmentId = null,
          eventType = SecurityEventType.PIN_VERIFICATION_FAILED,
          occurredAt = nowWall,
          summary = "Accountability PIN verification attempt failed",
        )
      )
    }

    val attemptsRemaining = (MAX_ATTEMPTS_BEFORE_COOLDOWN - nextAttempts).coerceAtLeast(0)
    return PinVerificationStatus.Failed(
      attemptsRemainingBeforeCooldown = attemptsRemaining,
      lockoutRemainingMillis = lockoutMillis,
    )
  }

  private suspend fun writeNewCredentialLocked(
    newPin: String,
    eventType: SecurityEventType,
    eventSummary: String,
  ): PinConfigureResult {
    val chars = newPin.toCharArray()
    val record =
      try {
        pinHasher.hash(chars)
      } finally {
        chars.fill('\u0000')
      }

    val payloadJson =
      json.encodeToString(
        VaultPayloadDto(
          saltBase64 = record.saltBase64,
          hashBase64 = record.hashBase64,
          algorithm = record.algorithm,
          iterations = record.iterations,
        )
      )
    val plaintextBytes = payloadJson.encodeToByteArray()
    val sealed =
      try {
        cipher.encrypt(plaintextBytes)
      } finally {
        plaintextBytes.fill(0)
      }

    val blob =
      when (sealed) {
        is CipherResult.Failure -> return PinConfigureResult.StorageFailure(sealed.reason)
        is CipherResult.Success -> sealed.data
      }

    val envelope =
      VaultEnvelopeDto(
        version = 1,
        ivBase64 = blob.ivBase64,
        ciphertextBase64 = blob.ciphertextBase64,
        failedAttempts = 0,
        lockoutRemainingAtLastFailMillis = 0L,
        lastFailedWallEpochMillis = 0L,
        lastFailedMonotonicMillis = 0L,
      )

    if (!writeEnvelopeLocked(envelope)) {
      return PinConfigureResult.StorageFailure("Unable to write local PIN vault file.")
    }

    val now = timeProvider.currentWallClock()
    val metaRes =
      accountabilityMetadataRepository.updateMetadata(
        AccountabilityMetadata(
          isPinEnabled = true,
          updatedAtEpochMillis = now.epochMillis,
        )
      )
    if (metaRes is PersistenceResult.Failure) {
      return PinConfigureResult.StorageFailure("Unable to update accountability metadata.")
    }

    runCatching {
      securityEventRepository.recordEvent(
        SecurityEvent(
          id = SecurityEventId(idGenerator.nextId()),
          commitmentId = null,
          eventType = eventType,
          occurredAt = now,
          summary = eventSummary,
        )
      )
    }
    return PinConfigureResult.Success
  }

  private suspend fun clearVaultAndMetadataLocked(
    eventType: SecurityEventType,
    summary: String,
  ): PinConfigureResult {
    vaultFile.delete()
    val now = timeProvider.currentWallClock()
    val metaRes =
      accountabilityMetadataRepository.updateMetadata(
        AccountabilityMetadata(
          isPinEnabled = false,
          updatedAtEpochMillis = now.epochMillis,
        )
      )
    if (metaRes is PersistenceResult.Failure) {
      return PinConfigureResult.StorageFailure("Unable to update accountability metadata.")
    }
    runCatching {
      securityEventRepository.recordEvent(
        SecurityEvent(
          id = SecurityEventId(idGenerator.nextId()),
          commitmentId = null,
          eventType = eventType,
          occurredAt = now,
          summary = summary,
        )
      )
    }
    return PinConfigureResult.Success
  }

  private fun decryptPayloadLocked(envelope: VaultEnvelopeDto): VaultPayloadDto? {
    val decrypted =
      cipher.decrypt(
        SealedPinBlob(
          ivBase64 = envelope.ivBase64,
          ciphertextBase64 = envelope.ciphertextBase64,
        )
      )
    if (decrypted !is CipherResult.Success) return null
    val bytes = decrypted.data
    return try {
      json.decodeFromString<VaultPayloadDto>(bytes.decodeToString())
    } catch (_: Exception) {
      null
    } finally {
      bytes.fill(0)
    }
  }

  private fun computeRemainingLockoutMillis(envelope: VaultEnvelopeDto): Long {
    val duration = envelope.lockoutRemainingAtLastFailMillis
    if (duration <= 0L) return 0L
    val nowWall = timeProvider.currentWallClock().epochMillis
    val nowMono = timeProvider.currentMonotonic().elapsedMillis

    // Prefer positive monotonic delta when still inside the same boot session; fall back to
    // positive wall-clock delta across reboots, keeping full remaining duration if clock regressed.
    val monoDelta = nowMono - envelope.lastFailedMonotonicMillis
    val wallDelta = nowWall - envelope.lastFailedWallEpochMillis
    val elapsed =
      when {
        monoDelta >= 0L && wallDelta >= 0L -> minOf(monoDelta, wallDelta)
        monoDelta >= 0L -> monoDelta
        wallDelta >= 0L -> wallDelta
        else -> 0L
      }
    return (duration - elapsed).coerceAtLeast(0L)
  }

  private fun readEnvelopeLocked(): VaultEnvelopeDto? {
    val file = vaultFile
    if (!file.exists()) return null
    return try {
      json.decodeFromString<VaultEnvelopeDto>(file.readText())
    } catch (_: Exception) {
      null
    }
  }

  private fun writeEnvelopeLocked(envelope: VaultEnvelopeDto): Boolean =
    try {
      val parent = vaultFile.parentFile
      if (parent != null && !parent.exists()) {
        parent.mkdirs()
      }
      val tempFile = File(parent, "$VAULT_FILE_NAME.tmp")
      tempFile.writeText(json.encodeToString(envelope))
      if (!tempFile.renameTo(vaultFile)) {
        vaultFile.writeText(tempFile.readText())
        tempFile.delete()
      }
      true
    } catch (_: Exception) {
      false
    }

  companion object {
    const val VAULT_FILE_NAME = "mizan_accountability_pin_vault.json"
    const val MAX_ATTEMPTS_BEFORE_COOLDOWN = 3

    fun cooldownForAttempts(attempts: Int): Long =
      when {
        attempts < MAX_ATTEMPTS_BEFORE_COOLDOWN -> 0L
        attempts == MAX_ATTEMPTS_BEFORE_COOLDOWN -> 30_000L
        attempts == MAX_ATTEMPTS_BEFORE_COOLDOWN + 1 -> 60_000L
        else -> 300_000L
      }
  }
}
