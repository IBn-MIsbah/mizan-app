package com.example.data.filter

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.MizanDatabase
import com.example.data.repository.RoomDomainFilterRepository
import com.example.domain.filter.DomainRuleMatcher
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DatasetAvailabilityStatus
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DatasetStatusEvaluator
import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.model.RestrictionMode
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.RemoteDatasetUpdateResult
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUserPreferencesRepository
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignedRemoteDatasetUpdaterTest {

  private lateinit var db: MizanDatabase
  private lateinit var keyPair: KeyPair
  private lateinit var verifier: EcdsaDomainDatasetSignatureVerifier
  private lateinit var repo: RoomDomainFilterRepository
  private lateinit var prefsRepo: FakeUserPreferencesRepository
  private val dispatchers = FakeDispatcherProvider()
  private val timeProvider = FakeTimeProvider()
  private val json = Json { encodeDefaults = true }

  private val manifestUrl = "https://feeds.example.invalid/mizan/social/manifest.json"
  private val payloadUrl = "https://feeds.example.invalid/mizan/social/domains-v1.txt"

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db =
      Room.inMemoryDatabaseBuilder(context, MizanDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    keyPair = generator.generateKeyPair()
    verifier =
      EcdsaDomainDatasetSignatureVerifier(
        TrustedDatasetKeyConfig(
          EcdsaDomainDatasetSignatureVerifier.encodePublicKeySpkiBase64(keyPair.public)
        )
      )
    repo =
      RoomDomainFilterRepository(
        domainFilterDao = db.domainFilterDao(),
        signatureVerifier = verifier,
        timeProvider = timeProvider,
        dispatcherProvider = dispatchers,
      )
    prefsRepo = FakeUserPreferencesRepository()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun sign(bytes: ByteArray, priv: java.security.PrivateKey = keyPair.private): String {
    val signer = Signature.getInstance(EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA)
    signer.initSign(priv)
    signer.update(bytes)
    return Base64.getEncoder().encodeToString(signer.sign())
  }

  private fun configuredPolicy(): DatasetFeedPolicy =
    DatasetFeedPolicy(
      remoteUpdatesEnabled = true,
      sources =
        mapOf(
          DatasetSeedDefaults.SOCIAL_DATASET_ID to
            DatasetFeedSourceSpec(
              datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
              category = DomainCategory.SOCIAL_MEDIA,
              manifestUrl = manifestUrl,
              sourceName = "Signed Mizan Feed",
              license = "CC-BY-4.0",
            )
        ),
    )

  private fun signedManifestFor(
    versionCode: Long,
    payload: ByteArray,
    overrideSig: String? = null,
    overrideSha: String? = null,
    overridePayloadUrl: String = payloadUrl,
  ): SignedDatasetManifest =
    SignedDatasetManifest(
      datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
      category = DomainCategory.SOCIAL_MEDIA,
      versionCode = versionCode,
      sourceName = "Signed Mizan Feed",
      license = "CC-BY-4.0",
      publishedAtEpochMillis = timeProvider.wallClock.epochMillis,
      payloadUrl = overridePayloadUrl,
      contentSha256 = overrideSha ?: sha256Hex(payload),
      signatureAlgorithm = EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
      signatureValue = overrideSig ?: sign(payload),
      limitationsNote = "Domain filtering cannot inspect HTTPS paths.",
    )

  private fun buildUpdater(
    fetcher: FeedHttpFetcher,
    networkChecker: UpdateNetworkPolicyChecker = UpdateNetworkPolicyChecker { UpdateNetworkEligibility.Eligible },
    policy: DatasetFeedPolicy = configuredPolicy(),
    sigVerifier: EcdsaDomainDatasetSignatureVerifier = verifier,
  ): SignedRemoteDatasetUpdater =
    SignedRemoteDatasetUpdater(
      feedPolicy = policy,
      signatureVerifier = sigVerifier,
      domainFilterRepository = repo,
      userPreferencesRepository = prefsRepo,
      feedHttpFetcher = fetcher,
      networkPolicyChecker = networkChecker,
    )

  @Test
  fun `disabled policy preserves locally cached offline datasets cleanly`() = runTest {
    val v1Payload = "instagram.com\ntiktok.com\n".encodeToByteArray()
    val importer = SignedLocalDatasetImporter(repo)
    val pkg =
      SignedDatasetPackage(
        datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
        category = DomainCategory.SOCIAL_MEDIA,
        versionCode = 1L,
        sourceName = "Offline Signed Pack",
        license = "CC-BY-4.0",
        contentSha256 = sha256Hex(v1Payload),
        signatureAlgorithm = EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
        signatureValue = sign(v1Payload),
        domainsPayload = v1Payload.decodeToString(),
        limitationsNote = "Domain-level only.",
      )
    assertTrue(
      importer.importPackageBytes(json.encodeToString(pkg).encodeToByteArray())
        is SignedPackageImportOutcome.Success
    )

    val disabledUpdater =
      buildUpdater(
        fetcher = FeedHttpFetcher { _, _ -> FeedFetchResult.NetworkError("should not fetch") },
        policy = DatasetFeedPolicy(remoteUpdatesEnabled = false),
      )
    val outcome = disabledUpdater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
    assertTrue(outcome is RemoteDatasetUpdateResult.Disabled)

    val active =
      (repo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
          as PersistenceResult.Success)
        .data[DomainCategory.SOCIAL_MEDIA]
    assertEquals(setOf("instagram.com", "tiktok.com"), active)
  }

  @Test
  fun `valid signed update activates domains detects upToDate and supports atomic rollback`() =
    runTest {
      val v1Payload = "instagram.com\nreddit.com\n".encodeToByteArray()
      val v1Manifest = signedManifestFor(1L, v1Payload)
      val v1Fetcher = FeedHttpFetcher { url, _ ->
        when (url) {
          manifestUrl -> FeedFetchResult.Success(json.encodeToString(v1Manifest).encodeToByteArray())
          payloadUrl -> FeedFetchResult.Success(v1Payload)
          else -> FeedFetchResult.HttpError(404, "Not found")
        }
      }

      val updaterV1 = buildUpdater(v1Fetcher)
      val firstRes = updaterV1.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
      assertTrue(firstRes is RemoteDatasetUpdateResult.Updated)
      assertEquals(
        setOf("instagram.com", "reddit.com"),
        (repo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA],
      )

      // Same version + matching hash -> UpToDate (does not clobber active domains or rollback slot)
      val upToDateRes = updaterV1.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
      assertTrue(upToDateRes is RemoteDatasetUpdateResult.UpToDate)

      // Upgrade to v2
      val v2Payload = "tiktok.com\nx.com\n".encodeToByteArray()
      val v2Manifest = signedManifestFor(2L, v2Payload)
      val updaterV2 =
        buildUpdater(
          FeedHttpFetcher { url, _ ->
            when (url) {
              manifestUrl ->
                FeedFetchResult.Success(json.encodeToString(v2Manifest).encodeToByteArray())
              payloadUrl -> FeedFetchResult.Success(v2Payload)
              else -> FeedFetchResult.HttpError(404, "Not found")
            }
          }
        )
      val secondRes = updaterV2.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
      assertTrue(secondRes is RemoteDatasetUpdateResult.Updated)
      val v2Meta =
        (repo.getDatasetMetadata(DatasetSeedDefaults.SOCIAL_DATASET_ID)
            as PersistenceResult.Success)
          .data!!
      assertEquals(2L, v2Meta.versionCode)
      assertEquals(1L, v2Meta.previousVersionCode)

      // Roll back to v1 restores v1 domains atomically
      val rolledBack =
        (repo.rollbackDataset(DatasetSeedDefaults.SOCIAL_DATASET_ID) as PersistenceResult.Success)
          .data
      assertEquals(1L, rolledBack.versionCode)
      assertNull(rolledBack.previousVersionCode)
      assertEquals(
        setOf("instagram.com", "reddit.com"),
        (repo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA],
      )
    }

  @Test
  fun `invalid signature tampered hash downgrade non-https empty payload and offline errors never erase valid cached dataset`() =
    runTest {
      val v5Payload = "instagram.com\ntiktok.com\n".encodeToByteArray()
      val v5Manifest = signedManifestFor(5L, v5Payload)
      val okUpdater =
        buildUpdater(
          FeedHttpFetcher { url, _ ->
            when (url) {
              manifestUrl ->
                FeedFetchResult.Success(json.encodeToString(v5Manifest).encodeToByteArray())
              payloadUrl -> FeedFetchResult.Success(v5Payload)
              else -> FeedFetchResult.HttpError(404, "Not found")
            }
          }
        )
      assertTrue(
        okUpdater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
          is RemoteDatasetUpdateResult.Updated
      )

      // 1. Offline / metered deferral leaves v5 active and reports transient error
      val offlineUpdater =
        buildUpdater(
          fetcher = FeedHttpFetcher { _, _ -> FeedFetchResult.NetworkError("Offline") },
          networkChecker =
            UpdateNetworkPolicyChecker {
              UpdateNetworkEligibility.Ineligible(
                reason = "Wi-Fi-only update policy active on metered cellular network.",
                isTransientOffline = true,
              )
            },
        )
      val offlineOutcome = offlineUpdater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
      assertTrue(offlineOutcome is RemoteDatasetUpdateResult.Failed)
      assertTrue((offlineOutcome as RemoteDatasetUpdateResult.Failed).isTransientNetworkError)

      // 2. Forged signature on v6 -> rejected, v5 preserved, lastError surfaced for FAILED badge
      val badPayload = "new.example\n".encodeToByteArray()
      val forgedSigManifest =
        signedManifestFor(6L, badPayload, overrideSig = sign("wrong".encodeToByteArray()))
      val forgedUpdater =
        buildUpdater(
          FeedHttpFetcher { url, _ ->
            when (url) {
              manifestUrl ->
                FeedFetchResult.Success(json.encodeToString(forgedSigManifest).encodeToByteArray())
              payloadUrl -> FeedFetchResult.Success(badPayload)
              else -> FeedFetchResult.HttpError(404, "Not found")
            }
          }
        )
      val forgedOutcome = forgedUpdater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
      assertTrue(forgedOutcome is RemoteDatasetUpdateResult.Failed)
      assertFalse((forgedOutcome as RemoteDatasetUpdateResult.Failed).isTransientNetworkError)

      // 3. Downgrade v4 -> rejected, v5 preserved
      val downgradeManifest = signedManifestFor(4L, badPayload)
      val downgradeUpdater =
        buildUpdater(
          FeedHttpFetcher { url, _ ->
            FeedFetchResult.Success(json.encodeToString(downgradeManifest).encodeToByteArray())
          }
        )
      assertTrue(
        downgradeUpdater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
          is RemoteDatasetUpdateResult.Failed
      )

      // 4. Non-HTTPS payloadUrl -> rejected, v5 preserved
      val insecureManifest =
        signedManifestFor(6L, badPayload, overridePayloadUrl = "http://insecure.example/domains.txt")
      val insecureUpdater =
        buildUpdater(
          FeedHttpFetcher { _, _ ->
            FeedFetchResult.Success(json.encodeToString(insecureManifest).encodeToByteArray())
          }
        )
      assertTrue(
        insecureUpdater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
          is RemoteDatasetUpdateResult.Failed
      )

      // 5. Empty/comments-only payload on v6 -> rejected, v5 preserved
      val emptyPayload = "# comment only\n   \n".encodeToByteArray()
      val emptyManifest = signedManifestFor(6L, emptyPayload)
      val emptyUpdater =
        buildUpdater(
          FeedHttpFetcher { url, _ ->
            when (url) {
              manifestUrl ->
                FeedFetchResult.Success(json.encodeToString(emptyManifest).encodeToByteArray())
              payloadUrl -> FeedFetchResult.Success(emptyPayload)
              else -> FeedFetchResult.HttpError(404, "Not found")
            }
          }
        )
      assertTrue(
        emptyUpdater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
          is RemoteDatasetUpdateResult.Failed
      )

      // 6. Conflicting hash at same version v5 -> rejected, v5 domains preserved
      val altPayload = "other.example\n".encodeToByteArray()
      val sameVerConflictManifest = signedManifestFor(5L, altPayload)
      val conflictUpdater =
        buildUpdater(
          FeedHttpFetcher { _, _ ->
            FeedFetchResult.Success(json.encodeToString(sameVerConflictManifest).encodeToByteArray())
          }
        )
      assertTrue(
        conflictUpdater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
          is RemoteDatasetUpdateResult.Failed
      )

      // Verify v5 domains remained 100% intact across all rejection modes while status is FAILED with fallback
      val domains =
        (repo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA]
      assertEquals(setOf("instagram.com", "tiktok.com"), domains)

      val meta =
        (repo.getDatasetMetadata(DatasetSeedDefaults.SOCIAL_DATASET_ID)
            as PersistenceResult.Success)
          .data!!
      assertEquals(5L, meta.versionCode)
      assertEquals(DatasetTrustStatus.INSTALLED_VERIFIED, meta.trustStatus)
      assertTrue(meta.isUsable)
      assertNotNull(meta.lastError)
      assertEquals(
        DatasetAvailabilityStatus.FAILED,
        DatasetStatusEvaluator.evaluate(meta, isUpdating = false, nowEpochMillis = timeProvider.wallClock.epochMillis),
      )

      // Verify unsigned local SAF import is also rejected cleanly while keeping v5 intact
      val importer = SignedLocalDatasetImporter(repo)
      val forgedPkg =
        SignedDatasetPackage(
          datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
          category = DomainCategory.SOCIAL_MEDIA,
          versionCode = 7L,
          sourceName = "Unsigned Local File",
          license = "Unknown",
          contentSha256 = sha256Hex(altPayload),
          signatureAlgorithm = EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
          signatureValue = "AAAA",
          domainsPayload = altPayload.decodeToString(),
          limitationsNote = "Domain only.",
        )
      assertTrue(
        importer.importPackageBytes(json.encodeToString(forgedPkg).encodeToByteArray())
          is SignedPackageImportOutcome.Rejected
      )
      assertEquals(
        setOf("instagram.com", "tiktok.com"),
        (repo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA],
      )
    }

  private val adultManifestUrl = "https://feeds.example.invalid/mizan/adult/manifest.json"
  private val adultPayloadUrl = "https://feeds.example.invalid/mizan/adult/domains.txt"

  private fun dualCategoryPolicy(remoteUpdatesEnabled: Boolean = true): DatasetFeedPolicy =
    DatasetFeedPolicy(
      remoteUpdatesEnabled = remoteUpdatesEnabled,
      sources =
        linkedMapOf(
          DatasetSeedDefaults.ADULT_DATASET_ID to
            DatasetFeedSourceSpec(
              datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
              category = DomainCategory.ADULT_CONTENT,
              manifestUrl = adultManifestUrl,
              sourceName = "Signed Mizan Adult Feed",
              license = "CC-BY-4.0",
            ),
          DatasetSeedDefaults.SOCIAL_DATASET_ID to
            DatasetFeedSourceSpec(
              datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
              category = DomainCategory.SOCIAL_MEDIA,
              manifestUrl = manifestUrl,
              sourceName = "Signed Mizan Social Feed",
              license = "CC-BY-4.0",
            ),
        ),
    )

  private fun signedManifestForCategory(
    datasetId: String,
    category: DomainCategory,
    versionCode: Long,
    payload: ByteArray,
    payloadLocation: String,
    overrideSig: String? = null,
  ): SignedDatasetManifest =
    SignedDatasetManifest(
      datasetId = datasetId,
      category = category,
      versionCode = versionCode,
      sourceName =
        if (category == DomainCategory.ADULT_CONTENT) "Signed Mizan Adult Feed"
        else "Signed Mizan Social Feed",
      license = "CC-BY-4.0",
      publishedAtEpochMillis = timeProvider.wallClock.epochMillis,
      payloadUrl = payloadLocation,
      contentSha256 = sha256Hex(payload),
      signatureAlgorithm = EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
      signatureValue = overrideSig ?: sign(payload),
      limitationsNote = "Domain filtering cannot inspect HTTPS paths.",
    )

  private fun meteredCellularChecker(): UpdateNetworkPolicyChecker =
    UpdateNetworkPolicyChecker { wifiOnly ->
      if (wifiOnly) {
        UpdateNetworkEligibility.Ineligible(
          reason = "Wi-Fi-only update policy active and current network is metered; update deferred.",
          isTransientOffline = true,
        )
      } else {
        UpdateNetworkEligibility.Eligible
      }
    }

  private fun configureSystemNetworkCapabilities(
    hasInternet: Boolean,
    unmeteredWifi: Boolean,
  ): AndroidUpdateNetworkPolicyChecker {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val shadowCm = Shadows.shadowOf(cm)
    val network: Network = cm.activeNetwork ?: cm.allNetworks.first()
    val caps = ShadowNetworkCapabilities.newInstance()
    val shadowCaps = Shadows.shadowOf(caps)
    if (hasInternet) {
      shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    if (unmeteredWifi) {
      shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
      shadowCaps.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    } else {
      shadowCaps.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    shadowCm.setNetworkCapabilities(network, caps)
    return AndroidUpdateNetworkPolicyChecker(context)
  }

  @Test
  fun `adult dataset updates on both metered cellular and unmetered wifi regardless of wifiOnly preference`() =
    runTest {
      // wifiOnlyFilterUpdates defaults to true, yet ADULT_CONTENT still permits metered cellular
      assertTrue(prefsRepo.preferences.first().wifiOnlyFilterUpdates)

      val v1Payload = "adult-one.example\nadult-two.example\n".encodeToByteArray()
      val v1Manifest =
        signedManifestForCategory(
          datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
          category = DomainCategory.ADULT_CONTENT,
          versionCode = 1L,
          payload = v1Payload,
          payloadLocation = adultPayloadUrl,
        )
      val requestedUrls = mutableListOf<String>()
      val cellularChecker = configureSystemNetworkCapabilities(hasInternet = true, unmeteredWifi = false)
      val cellularUpdater =
        buildUpdater(
          fetcher =
            FeedHttpFetcher { url, _ ->
              requestedUrls += url
              when (url) {
                adultManifestUrl ->
                  FeedFetchResult.Success(json.encodeToString(v1Manifest).encodeToByteArray())
                adultPayloadUrl -> FeedFetchResult.Success(v1Payload)
                else -> FeedFetchResult.HttpError(404, "Not found")
              }
            },
          networkChecker = cellularChecker,
          policy = dualCategoryPolicy(),
        )

      val cellularOutcome = cellularUpdater.checkAndUpdate(DatasetSeedDefaults.ADULT_DATASET_ID)
      assertTrue(cellularOutcome is RemoteDatasetUpdateResult.Updated)
      assertEquals(listOf(adultManifestUrl, adultPayloadUrl), requestedUrls)
      assertEquals(
        setOf("adult-one.example", "adult-two.example"),
        (repo.getActiveDomainsForCategories(setOf(DomainCategory.ADULT_CONTENT))
            as PersistenceResult.Success)
          .data[DomainCategory.ADULT_CONTENT],
      )

      // Now switch to unmetered Wi-Fi and upgrade to v2
      val v2Payload = "adult-two.example\nadult-three.example\n".encodeToByteArray()
      val v2Manifest =
        signedManifestForCategory(
          datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
          category = DomainCategory.ADULT_CONTENT,
          versionCode = 2L,
          payload = v2Payload,
          payloadLocation = adultPayloadUrl,
        )
      val wifiChecker = configureSystemNetworkCapabilities(hasInternet = true, unmeteredWifi = true)
      val wifiUpdater =
        buildUpdater(
          fetcher =
            FeedHttpFetcher { url, _ ->
              when (url) {
                adultManifestUrl ->
                  FeedFetchResult.Success(json.encodeToString(v2Manifest).encodeToByteArray())
                adultPayloadUrl -> FeedFetchResult.Success(v2Payload)
                else -> FeedFetchResult.HttpError(404, "Not found")
              }
            },
          networkChecker = wifiChecker,
          policy = dualCategoryPolicy(),
        )

      val wifiOutcome = wifiUpdater.checkAndUpdate(DatasetSeedDefaults.ADULT_DATASET_ID)
      assertTrue(wifiOutcome is RemoteDatasetUpdateResult.Updated)
      assertEquals(
        setOf("adult-two.example", "adult-three.example"),
        (repo.getActiveDomainsForCategories(setOf(DomainCategory.ADULT_CONTENT))
            as PersistenceResult.Success)
          .data[DomainCategory.ADULT_CONTENT],
      )
    }

  @Test
  fun `social dataset respects wifiOnly preference and updates on cellular only when wifiOnly is disabled`() =
    runTest {
      // Seed cached v1 offline first so we can verify preservation when deferred
      val v1Payload = "instagram.com\nreddit.com\n".encodeToByteArray()
      val v1Pkg =
        SignedDatasetPackage(
          datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
          category = DomainCategory.SOCIAL_MEDIA,
          versionCode = 1L,
          sourceName = "Cached Social Pack",
          license = "CC-BY-4.0",
          contentSha256 = sha256Hex(v1Payload),
          signatureAlgorithm = EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
          signatureValue = sign(v1Payload),
          domainsPayload = v1Payload.decodeToString(),
          limitationsNote = "Domain-only.",
        )
      assertTrue(
        SignedLocalDatasetImporter(repo).importPackageBytes(json.encodeToString(v1Pkg).encodeToByteArray())
          is SignedPackageImportOutcome.Success
      )

      val v2Payload = "tiktok.com\nx.com\n".encodeToByteArray()
      val v2Manifest = signedManifestFor(2L, v2Payload)
      var socialFetchCalls = 0
      val cellularChecker = configureSystemNetworkCapabilities(hasInternet = true, unmeteredWifi = false)
      val updater =
        buildUpdater(
          fetcher =
            FeedHttpFetcher { url, _ ->
              socialFetchCalls += 1
              when (url) {
                manifestUrl -> FeedFetchResult.Success(json.encodeToString(v2Manifest).encodeToByteArray())
                payloadUrl -> FeedFetchResult.Success(v2Payload)
                else -> FeedFetchResult.HttpError(404, "Not found")
              }
            },
          networkChecker = cellularChecker,
          policy = dualCategoryPolicy(),
        )

      // wifiOnlyFilterUpdates = true -> SOCIAL_MEDIA must defer on metered cellular with zero HTTP calls
      prefsRepo.setWifiOnlyFilterUpdates(true)
      val deferredOutcome = updater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
      assertTrue(deferredOutcome is RemoteDatasetUpdateResult.Failed)
      assertTrue((deferredOutcome as RemoteDatasetUpdateResult.Failed).isTransientNetworkError)
      assertEquals(0, socialFetchCalls)
      // Cached v1 social domains remain active and untouched
      assertEquals(
        setOf("instagram.com", "reddit.com"),
        (repo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA],
      )

      // wifiOnlyFilterUpdates = false -> SOCIAL_MEDIA permits metered cellular update
      prefsRepo.setWifiOnlyFilterUpdates(false)
      val cellularAllowedOutcome = updater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
      assertTrue(cellularAllowedOutcome is RemoteDatasetUpdateResult.Updated)
      assertEquals(2, socialFetchCalls)
      assertEquals(
        setOf("tiktok.com", "x.com"),
        (repo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA],
      )
    }

  @Test
  fun `checkAndUpdateAll updates adult on cellular while deferring social and preserving offline vpn filtering`() =
    runTest {
      // Seed v1 social dataset offline prior to cellular sync
      val socialV1 = "instagram.com\n".encodeToByteArray()
      val socialPkg =
        SignedDatasetPackage(
          datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
          category = DomainCategory.SOCIAL_MEDIA,
          versionCode = 1L,
          sourceName = "Seed Social",
          license = "CC-BY-4.0",
          contentSha256 = sha256Hex(socialV1),
          signatureAlgorithm = EcdsaDomainDatasetSignatureVerifier.ALGORITHM_SHA256_WITH_ECDSA,
          signatureValue = sign(socialV1),
          domainsPayload = socialV1.decodeToString(),
          limitationsNote = "Domain-only.",
        )
      SignedLocalDatasetImporter(repo).importPackageBytes(json.encodeToString(socialPkg).encodeToByteArray())

      val adultV1 = "blocked-adult.example\n".encodeToByteArray()
      val adultManifest =
        signedManifestForCategory(
          datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
          category = DomainCategory.ADULT_CONTENT,
          versionCode = 1L,
          payload = adultV1,
          payloadLocation = adultPayloadUrl,
        )
      prefsRepo.setWifiOnlyFilterUpdates(true)

      val fetched = mutableListOf<String>()
      val updater =
        buildUpdater(
          fetcher =
            FeedHttpFetcher { url, _ ->
              fetched += url
              when (url) {
                adultManifestUrl ->
                  FeedFetchResult.Success(json.encodeToString(adultManifest).encodeToByteArray())
                adultPayloadUrl -> FeedFetchResult.Success(adultV1)
                else -> FeedFetchResult.HttpError(404, "Should not fetch social on cellular")
              }
            },
          networkChecker = meteredCellularChecker(),
          policy = dualCategoryPolicy(),
        )

      val results = updater.checkAndUpdateAll()
      assertTrue(results[DatasetSeedDefaults.ADULT_DATASET_ID] is RemoteDatasetUpdateResult.Updated)
      assertTrue(results[DatasetSeedDefaults.SOCIAL_DATASET_ID] is RemoteDatasetUpdateResult.Failed)
      assertTrue(
        (results[DatasetSeedDefaults.SOCIAL_DATASET_ID] as RemoteDatasetUpdateResult.Failed)
          .isTransientNetworkError
      )
      assertEquals(listOf(adultManifestUrl, adultPayloadUrl), fetched)

      // Failed cellular update on adult v2 (bad signature) must preserve adult v1 & offline VPN rules
      val badAdultV2 = "new-bad.example\n".encodeToByteArray()
      val forgedAdultManifest =
        signedManifestForCategory(
          datasetId = DatasetSeedDefaults.ADULT_DATASET_ID,
          category = DomainCategory.ADULT_CONTENT,
          versionCode = 2L,
          payload = badAdultV2,
          payloadLocation = adultPayloadUrl,
          overrideSig = sign("tampered".encodeToByteArray()),
        )
      val badCellularUpdater =
        buildUpdater(
          fetcher =
            FeedHttpFetcher { url, _ ->
              when (url) {
                adultManifestUrl ->
                  FeedFetchResult.Success(json.encodeToString(forgedAdultManifest).encodeToByteArray())
                adultPayloadUrl -> FeedFetchResult.Success(badAdultV2)
                else -> FeedFetchResult.NetworkError("Offline")
              }
            },
          networkChecker = meteredCellularChecker(),
          policy = dualCategoryPolicy(),
        )
      val badOutcome = badCellularUpdater.checkAndUpdate(DatasetSeedDefaults.ADULT_DATASET_ID)
      assertTrue(badOutcome is RemoteDatasetUpdateResult.Failed)
      assertFalse((badOutcome as RemoteDatasetUpdateResult.Failed).isTransientNetworkError)

      // Now turn device completely offline and confirm cached adult v1 + social v1 VPN filtering remains unaffected
      val activeDatasets =
        (repo.getActiveDomainsForCategories(
            setOf(DomainCategory.ADULT_CONTENT, DomainCategory.SOCIAL_MEDIA)
          ) as PersistenceResult.Success)
          .data
      assertEquals(setOf("blocked-adult.example"), activeDatasets[DomainCategory.ADULT_CONTENT])
      assertEquals(setOf("instagram.com"), activeDatasets[DomainCategory.SOCIAL_MEDIA])

      val commitment =
        (CommitmentStateMachine.activate(
            CommitmentStateMachine.createDraft(
              id = CommitmentId("c-offline-filter"),
              rules =
                CommitmentRules(
                  mode = RestrictionMode.STRICT,
                  restrictedApps = emptySet(),
                  websiteFilteringEnabled = true,
                  blockedDomainCategories =
                    setOf(DomainCategory.ADULT_CONTENT, DomainCategory.SOCIAL_MEDIA),
                ),
              now = timeProvider.wallClock,
            ),
            agreedToExactRules = true,
            now = timeProvider.wallClock,
          ) as CommitmentResult.Success)
          .commitment
      assertTrue(
        DomainRuleMatcher.evaluate(
            commitment,
            "www.blocked-adult.example",
            timeProvider.wallClock,
            activeDatasets,
          )
          .isBlocked
      )
      assertTrue(
        DomainRuleMatcher.evaluate(
            commitment,
            "m.instagram.com",
            timeProvider.wallClock,
            activeDatasets,
          )
          .isBlocked
      )
    }

  @Test
  fun `disabled feeds and missing trusted signing keys make zero network requests on cellular and wifi`() =
    runTest {
      var httpCalls = 0
      val fetcher = FeedHttpFetcher { _, _ ->
        httpCalls += 1
        FeedFetchResult.NetworkError("Should never run")
      }
      val noKeyVerifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig(null))

      // 1. Disabled remote updates + missing key
      val disabledBoth =
        buildUpdater(
          fetcher = fetcher,
          networkChecker = UpdateNetworkPolicyChecker { UpdateNetworkEligibility.Eligible },
          policy = dualCategoryPolicy(remoteUpdatesEnabled = false),
          sigVerifier = noKeyVerifier,
        )
      prefsRepo.setWifiOnlyFilterUpdates(false)
      assertTrue(
        disabledBoth.checkAndUpdate(DatasetSeedDefaults.ADULT_DATASET_ID)
          is RemoteDatasetUpdateResult.Disabled
      )
      assertTrue(
        disabledBoth.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
          is RemoteDatasetUpdateResult.Disabled
      )

      // 2. remoteUpdatesEnabled = true + valid URLs, but NO pinned key -> still Disabled with 0 calls
      val enabledNoKey =
        buildUpdater(
          fetcher = fetcher,
          networkChecker = UpdateNetworkPolicyChecker { UpdateNetworkEligibility.Eligible },
          policy = dualCategoryPolicy(remoteUpdatesEnabled = true),
          sigVerifier = noKeyVerifier,
        )
      val noKeyResults = enabledNoKey.checkAndUpdateAll()
      assertTrue(noKeyResults[DatasetSeedDefaults.ADULT_DATASET_ID] is RemoteDatasetUpdateResult.Disabled)
      assertTrue(noKeyResults[DatasetSeedDefaults.SOCIAL_DATASET_ID] is RemoteDatasetUpdateResult.Disabled)
      assertEquals(0, httpCalls)
    }
}
