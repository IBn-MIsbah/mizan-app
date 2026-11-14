package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.filter.DatasetFeedPolicy
import com.example.data.filter.EcdsaDomainDatasetSignatureVerifier
import com.example.data.filter.FeedFetchResult
import com.example.data.filter.FeedHttpFetcher
import com.example.data.filter.SignedRemoteDatasetUpdater
import com.example.data.filter.TrustedDatasetKeyConfig
import com.example.data.filter.UpdateNetworkEligibility
import com.example.data.filter.UpdateNetworkPolicyChecker
import com.example.data.local.db.MizanDatabase
import com.example.data.repository.RoomCommitmentRepository
import com.example.data.repository.RoomDomainFilterRepository
import com.example.domain.filter.DomainRuleMatcher
import com.example.domain.lifecycle.CommitmentResult
import com.example.domain.lifecycle.CommitmentStateMachine
import com.example.domain.model.BlockedDomain
import com.example.domain.model.CommitmentId
import com.example.domain.model.CommitmentRules
import com.example.domain.model.DatasetSeedDefaults
import com.example.domain.model.DatasetTrustStatus
import com.example.domain.model.DomainCategory
import com.example.domain.model.DomainName
import com.example.domain.model.DomainRuleType
import com.example.domain.model.RestrictionMode
import com.example.domain.repository.DatasetImportResult
import com.example.domain.repository.DomainDatasetSignatureVerifier
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.RemoteDatasetUpdateResult
import com.example.testing.FakeDispatcherProvider
import com.example.testing.FakeTimeProvider
import com.example.testing.FakeUserPreferencesRepository
import java.security.MessageDigest
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
class RoomDomainFilterRepositoryTest {

  private lateinit var db: MizanDatabase
  private val dispatchers = FakeDispatcherProvider()
  private val timeProvider = FakeTimeProvider()

  private class TestSignatureVerifier(
    override val isTrustedKeyConfigured: Boolean = true,
    private val expectedSignature: String = "VALID_SIG",
  ) : DomainDatasetSignatureVerifier {
    override fun verify(
      payloadBytes: ByteArray,
      signatureAlgorithm: String,
      signatureValue: String,
    ): Boolean = signatureValue == expectedSignature && signatureAlgorithm == "TEST-SIG"
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db =
      Room.inMemoryDatabaseBuilder(context, MizanDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `default datasets stay missing_or_empty and remote updater reports disabled cleanly`() = runTest {
    val unconfiguredVerifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig())
    val repo =
      RoomDomainFilterRepository(
        domainFilterDao = db.domainFilterDao(),
        signatureVerifier = unconfiguredVerifier,
        timeProvider = timeProvider,
        dispatcherProvider = dispatchers,
      )
    repo.ensureDefaultDatasetsInitialized()

    val datasets = (repo.observeDatasets().first() as PersistenceResult.Success).data
    assertEquals(2, datasets.size)
    assertTrue(datasets.all { it.trustStatus == DatasetTrustStatus.MISSING_OR_EMPTY })
    assertTrue(datasets.none { it.isUsable })

    val updater =
      SignedRemoteDatasetUpdater(
        feedPolicy = DatasetFeedPolicy(remoteUpdatesEnabled = false),
        signatureVerifier = unconfiguredVerifier,
        domainFilterRepository = repo,
        userPreferencesRepository = FakeUserPreferencesRepository(),
        feedHttpFetcher = FeedHttpFetcher { _, _ -> FeedFetchResult.NetworkError("offline") },
        networkPolicyChecker = UpdateNetworkPolicyChecker { UpdateNetworkEligibility.Eligible },
      )
    val updateRes = updater.checkAndUpdate(DatasetSeedDefaults.SOCIAL_DATASET_ID)
    assertTrue(updateRes is RemoteDatasetUpdateResult.Disabled)
  }

  @Test
  fun `rejects missing verification key bad checksum invalid signature and rollback version while preserving last known good`() =
    runTest {
      val v1Payload = "alpha.example\nbeta.example\n".encodeToByteArray()
      val v1Hash = sha256Hex(v1Payload)

      // 1. No trusted key configured -> rejected cleanly
      val noKeyRepo =
        RoomDomainFilterRepository(
          domainFilterDao = db.domainFilterDao(),
          signatureVerifier = EcdsaDomainDatasetSignatureVerifier(TrustedDatasetKeyConfig()),
          timeProvider = timeProvider,
          dispatcherProvider = dispatchers,
        )
      val rejectedNoKey =
        (noKeyRepo.stageVerifyAndActivateDataset(
            datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
            category = DomainCategory.SOCIAL_MEDIA,
            sourceName = "Signed Test Feed",
            license = "CC-BY-4.0",
            versionCode = 1L,
            expectedSha256 = v1Hash,
            signatureAlgorithm = "TEST-SIG",
            signatureValue = "VALID_SIG",
            rawPayloadBytes = v1Payload,
            limitationsNote = "Test only",
            requireSignature = true,
          ) as PersistenceResult.Success)
          .data
      assertTrue(rejectedNoKey is DatasetImportResult.Rejected)

      // 2. Trusted key configured -> v1 activates atomically
      val trustedRepo =
        RoomDomainFilterRepository(
          domainFilterDao = db.domainFilterDao(),
          signatureVerifier = TestSignatureVerifier(),
          timeProvider = timeProvider,
          dispatcherProvider = dispatchers,
        )
      val okV1 =
        (trustedRepo.stageVerifyAndActivateDataset(
            datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
            category = DomainCategory.SOCIAL_MEDIA,
            sourceName = "Signed Test Feed",
            license = "CC-BY-4.0",
            versionCode = 10L,
            expectedSha256 = v1Hash,
            signatureAlgorithm = "TEST-SIG",
            signatureValue = "VALID_SIG",
            rawPayloadBytes = v1Payload,
            limitationsNote = "Test only",
            requireSignature = true,
          ) as PersistenceResult.Success)
          .data
      assertTrue(okV1 is DatasetImportResult.Success)

      // 3. Invalid signature on v11 -> rejected AND v10 active domains remain intact!
      val v2Payload = "gamma.example\n".encodeToByteArray()
      val v2Hash = sha256Hex(v2Payload)
      val badSig =
        (trustedRepo.stageVerifyAndActivateDataset(
            datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
            category = DomainCategory.SOCIAL_MEDIA,
            sourceName = "Signed Test Feed",
            license = "CC-BY-4.0",
            versionCode = 11L,
            expectedSha256 = v2Hash,
            signatureAlgorithm = "TEST-SIG",
            signatureValue = "FORGED_SIG",
            rawPayloadBytes = v2Payload,
            limitationsNote = "Test only",
            requireSignature = true,
          ) as PersistenceResult.Success)
          .data
      assertTrue(badSig is DatasetImportResult.Rejected)

      // 4. Rollback version (version 9 <= 10) -> rejected AND v10 active domains remain intact!
      val rollbackVersionRejected =
        (trustedRepo.stageVerifyAndActivateDataset(
            datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
            category = DomainCategory.SOCIAL_MEDIA,
            sourceName = "Signed Test Feed",
            license = "CC-BY-4.0",
            versionCode = 9L,
            expectedSha256 = v2Hash,
            signatureAlgorithm = "TEST-SIG",
            signatureValue = "VALID_SIG",
            rawPayloadBytes = v2Payload,
            limitationsNote = "Test only",
            requireSignature = true,
          ) as PersistenceResult.Success)
          .data
      assertTrue(rollbackVersionRejected is DatasetImportResult.Rejected)

      // 5. Checksum mismatch -> rejected AND v10 active domains remain intact!
      val badChecksum =
        (trustedRepo.stageVerifyAndActivateDataset(
            datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
            category = DomainCategory.SOCIAL_MEDIA,
            sourceName = "Signed Test Feed",
            license = "CC-BY-4.0",
            versionCode = 11L,
            expectedSha256 = "0".repeat(64),
            signatureAlgorithm = "TEST-SIG",
            signatureValue = "VALID_SIG",
            rawPayloadBytes = v2Payload,
            limitationsNote = "Test only",
            requireSignature = true,
          ) as PersistenceResult.Success)
          .data
      assertTrue(badChecksum is DatasetImportResult.Rejected)

      val activeAfterRejects =
        (trustedRepo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA]
      assertEquals(setOf("alpha.example", "beta.example"), activeAfterRejects)

      // 6. Valid v12 update succeeds and atomic rollback restores v10 cleanly
      val v12Payload = "gamma.example\ndelta.example\n".encodeToByteArray()
      val v12Ok =
        (trustedRepo.stageVerifyAndActivateDataset(
            datasetId = DatasetSeedDefaults.SOCIAL_DATASET_ID,
            category = DomainCategory.SOCIAL_MEDIA,
            sourceName = "Signed Test Feed",
            license = "CC-BY-4.0",
            versionCode = 12L,
            expectedSha256 = sha256Hex(v12Payload),
            signatureAlgorithm = "TEST-SIG",
            signatureValue = "VALID_SIG",
            rawPayloadBytes = v12Payload,
            limitationsNote = "Test only",
            requireSignature = true,
          ) as PersistenceResult.Success)
          .data
      assertTrue(v12Ok is DatasetImportResult.Success)
      assertEquals(
        setOf("gamma.example", "delta.example"),
        (trustedRepo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA],
      )

      val rolledBack =
        (trustedRepo.rollbackDataset(DatasetSeedDefaults.SOCIAL_DATASET_ID)
            as PersistenceResult.Success)
          .data
      assertEquals(10L, rolledBack.versionCode)
      assertEquals(
        setOf("alpha.example", "beta.example"),
        (trustedRepo.getActiveDomainsForCategories(setOf(DomainCategory.SOCIAL_MEDIA))
            as PersistenceResult.Success)
          .data[DomainCategory.SOCIAL_MEDIA],
      )
    }

  @Test
  fun `custom blocked and allowed domains persist offline and evaluate even with empty datasets`() =
    runTest {
      val commitmentRepo = RoomCommitmentRepository(db.commitmentDao(), dispatchers)
      val rules =
        CommitmentRules(
          mode = RestrictionMode.STRICT,
          restrictedApps = emptySet(),
          websiteFilteringEnabled = true,
          blockedDomains =
            setOf(
              BlockedDomain(DomainName("offline.example"), ruleType = DomainRuleType.BLOCK),
              BlockedDomain(DomainName("docs.offline.example"), ruleType = DomainRuleType.ALLOW),
            ),
          blockedDomainCategories = setOf(DomainCategory.SOCIAL_MEDIA),
        )
      val draft = CommitmentStateMachine.createDraft(CommitmentId("c-off-1"), rules, timeProvider.wallClock)
      val active =
        (CommitmentStateMachine.activate(draft, agreedToExactRules = true, now = timeProvider.wallClock)
            as CommitmentResult.Success)
          .commitment
      assertTrue(commitmentRepo.saveCommitment(active) is PersistenceResult.Success)

      val reloaded =
        (commitmentRepo.getCommitment(CommitmentId("c-off-1")) as PersistenceResult.Success).data
          as com.example.domain.model.Commitment.Active
      assertTrue(reloaded.rules.websiteFilteringEnabled)
      assertTrue(DomainRuleMatcher.evaluate(reloaded, "offline.example", timeProvider.wallClock).isBlocked)
      assertFalse(DomainRuleMatcher.evaluate(reloaded, "docs.offline.example", timeProvider.wallClock).isBlocked)
    }
}
