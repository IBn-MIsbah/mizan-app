package com.example.data.filter

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcdsaDomainDatasetSignatureVerifierTest {

  private fun generateEcKeyPair(): KeyPair {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    return generator.generateKeyPair()
  }

  private fun signPayload(privateKey: java.security.PrivateKey, payload: ByteArray): String {
    val signer = Signature.getInstance(EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA)
    signer.initSign(privateKey)
    signer.update(payload)
    return Base64.getEncoder().encodeToString(signer.sign())
  }

  @Test
  fun `fails closed when trusted key config is null blank or malformed`() {
    val keyPair = generateEcKeyPair()
    val payload = "example.com\n".encodeToByteArray()
    val signature = signPayload(keyPair.private, payload)

    val nullVerifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig(null))
    assertFalse(nullVerifier.isTrustedKeyConfigured)
    assertFalse(
      nullVerifier.verify(
        payload,
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        signature,
      )
    )

    val blankVerifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig("   \n  "))
    assertFalse(blankVerifier.isTrustedKeyConfigured)
    assertFalse(
      blankVerifier.verify(
        payload,
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        signature,
      )
    )

    val malformedVerifier =
      EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig("not-a-valid-spki-key!!!"))
    assertFalse(malformedVerifier.isTrustedKeyConfigured)
    assertFalse(
      malformedVerifier.verify(
        payload,
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        signature,
      )
    )
  }

  @Test
  fun `verifies authentic ECDSA signature and accepts PEM header formatting`() {
    val keyPair = generateEcKeyPair()
    val spkiBase64 = EcdsaDomainDatasetSignatureVerifier.encodePublicKeySpkiBase64(keyPair.public)
    val pemSpki =
      "-----BEGIN PUBLIC KEY-----\n$spkiBase64\n-----END PUBLIC KEY-----"
    val payload = "instagram.com\ntiktok.com\n".encodeToByteArray()
    val signature = signPayload(keyPair.private, payload)

    val rawVerifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig(spkiBase64))
    val pemVerifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig(pemSpki))

    assertTrue(rawVerifier.isTrustedKeyConfigured)
    assertTrue(pemVerifier.isTrustedKeyConfigured)
    assertTrue(
      rawVerifier.verify(
        payload,
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        signature,
      )
    )
    assertTrue(
      pemVerifier.verify(
        payload,
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        signature,
      )
    )
  }

  @Test
  fun `rejects tampered payload wrong signing key empty payload unknown algorithm and invalid base64`() {
    val trustedKeyPair = generateEcKeyPair()
    val untrustedKeyPair = generateEcKeyPair()
    val spkiBase64 =
      EcdsaDomainDatasetSignatureVerifier.encodePublicKeySpkiBase64(trustedKeyPair.public)
    val verifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig(spkiBase64))

    val originalPayload = "reddit.com\nx.com\n".encodeToByteArray()
    val validSig = signPayload(trustedKeyPair.private, originalPayload)
    val wrongKeySig = signPayload(untrustedKeyPair.private, originalPayload)

    assertFalse(
      verifier.verify(
        "reddit.com\nevil-injected.example\n".encodeToByteArray(),
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        validSig,
      )
    )
    assertFalse(
      verifier.verify(
        originalPayload,
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        wrongKeySig,
      )
    )
    assertFalse(
      verifier.verify(
        ByteArray(0),
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        validSig,
      )
    )
    assertFalse(verifier.verify(originalPayload, "NONEwithECDSA", validSig))
    assertFalse(
      verifier.verify(
        originalPayload,
        EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        "***not-base64***",
      )
    )
  }
}
