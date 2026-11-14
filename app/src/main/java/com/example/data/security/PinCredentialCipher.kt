package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class SealedPinBlob(
  val ivBase64: String,
  val ciphertextBase64: String,
)

sealed interface CipherResult<out T> {
  data class Success<out T>(val data: T) : CipherResult<T>

  data class Failure(val reason: String) : CipherResult<Nothing>
}

interface PinCredentialCipher {
  fun encrypt(plaintext: ByteArray): CipherResult<SealedPinBlob>

  fun decrypt(blob: SealedPinBlob): CipherResult<ByteArray>
}

@Singleton
class AndroidKeystorePinCredentialCipher @Inject constructor() : PinCredentialCipher {

  private val lock = Any()

  override fun encrypt(plaintext: ByteArray): CipherResult<SealedPinBlob> =
    synchronized(lock) {
      try {
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        CipherResult.Success(
          SealedPinBlob(
            ivBase64 = Base64.getEncoder().encodeToString(iv),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
          )
        )
      } catch (e: Exception) {
        CipherResult.Failure("Android Keystore encryption unavailable (${e::class.simpleName})")
      }
    }

  override fun decrypt(blob: SealedPinBlob): CipherResult<ByteArray> =
    synchronized(lock) {
      try {
        val secretKey = loadExistingKey()
          ?: return CipherResult.Failure("Android Keystore PIN key missing")
        val iv = Base64.getDecoder().decode(blob.ivBase64)
        val ciphertext = Base64.getDecoder().decode(blob.ciphertextBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        CipherResult.Success(cipher.doFinal(ciphertext))
      } catch (e: Exception) {
        CipherResult.Failure("Android Keystore decryption failed (${e::class.simpleName})")
      }
    }

  private fun getOrCreateKey(): SecretKey {
    loadExistingKey()?.let { return it }
    val keyGenerator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
    val spec =
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  private fun loadExistingKey(): SecretKey? {
    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
    keyStore.load(null)
    return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
  }

  companion object {
    const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val KEY_ALIAS = "mizan_accountability_pin_aes_v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_LENGTH_BITS = 128
  }
}
