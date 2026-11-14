package com.example.data.filter

import com.example.domain.repository.DomainDatasetSignatureVerifier
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration holding the pinned X.509 SubjectPublicKeyInfo (SPKI) Base64 ECDSA public key
 * used to verify dataset signatures.
 *
 * When [pinnedPublicKeySpkiBase64] is null or blank, verification fails closed and
 * [DomainDatasetSignatureVerifier.isTrustedKeyConfigured] returns false.
 */
data class TrustedDatasetKeyConfig(
  val pinnedPublicKeySpkiBase64: String? = null,
)

/**
 * Cryptographic dataset verifier using JCA SHA256withECDSA over raw dataset payload bytes against a
 * pinned X.509 SPKI EC public key. Always fails closed when no trusted key is configured or on any
 * malformed key/signature/algorithm input.
 */
@Singleton
class EcdsaDomainDatasetSignatureVerifier
@Inject
constructor(
  keyConfig: TrustedDatasetKeyConfig,
) : DomainDatasetSignatureVerifier {

  private val trustedPublicKey: PublicKey? = parsePublicKey(keyConfig.pinnedPublicKeySpkiBase64)

  override val isTrustedKeyConfigured: Boolean
    get() = trustedPublicKey != null

  override fun verify(
    payloadBytes: ByteArray,
    signatureAlgorithm: String,
    signatureValue: String,
  ): Boolean {
    val publicKey = trustedPublicKey ?: return false
    if (payloadBytes.isEmpty()) return false
    if (!signatureAlgorithm.trim().equals(ALGORITHM_SHA256_WITH_ECDSA, ignoreCase = true)) {
      return false
    }

    val cleanedSig = signatureValue.trim().replace("\\s+".toRegex(), "")
    if (cleanedSig.isEmpty()) return false

    val signatureBytes =
      try {
        Base64.getDecoder().decode(cleanedSig)
      } catch (_: IllegalArgumentException) {
        return false
      }
    if (signatureBytes.isEmpty()) return false

    return try {
      val verifier = Signature.getInstance(ALGORITHM_SHA256_WITH_ECDSA)
      verifier.initVerify(publicKey)
      verifier.update(payloadBytes)
      verifier.verify(signatureBytes)
    } catch (_: GeneralSecurityException) {
      false
    } catch (_: IllegalArgumentException) {
      false
    }
  }

  private fun parsePublicKey(rawConfig: String?): PublicKey? {
    val normalized = normalizeBase64Spki(rawConfig) ?: return null
    return try {
      val keyBytes = Base64.getDecoder().decode(normalized)
      val spec = X509EncodedKeySpec(keyBytes)
      KeyFactory.getInstance(KEY_ALGORITHM_EC).generatePublic(spec)
    } catch (_: GeneralSecurityException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun normalizeBase64Spki(rawConfig: String?): String? {
    if (rawConfig.isNullOrBlank()) return null
    val stripped =
      rawConfig
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\\s+".toRegex(), "")
        .trim()
    return stripped.takeIf { it.isNotEmpty() }
  }

  companion object {
    const val KEY_ALGORITHM_EC = "EC"
    const val ALGORITHM_SHA256_WITH_ECDSA = "SHA256withECDSA"

    fun encodePublicKeySpkiBase64(publicKey: PublicKey): String =
      Base64.getEncoder().encodeToString(publicKey.encoded)
  }
}
