package com.example.domain.repository

import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.DailyUsage
import com.example.domain.model.PersistedActiveUsageSession
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageIntegrityCheckpoint
import com.example.domain.time.DomainDate
import kotlinx.coroutines.flow.Flow

interface DailyUsageRepository {
  fun observeDailyUsage(
    commitmentId: CommitmentId,
    date: DomainDate,
  ): Flow<PersistenceResult<DailyUsage>>

  suspend fun getDailyUsage(
    commitmentId: CommitmentId,
    date: DomainDate,
  ): PersistenceResult<DailyUsage>

  suspend fun recordUsageEvent(
    commitment: Commitment,
    event: UsageEvent,
  ): PersistenceResult<DailyUsage>

  suspend fun markDayIncomplete(
    commitmentId: CommitmentId,
    date: DomainDate,
    reason: String,
  ): PersistenceResult<DailyUsage>

  suspend fun markReconciliationAttempt(
    commitmentId: CommitmentId,
    date: DomainDate,
    reconciledAtEpochMillis: Long,
    coverageStatus: UsageCoverageStatus? = null,
    incompleteReason: String? = null,
  ): PersistenceResult<DailyUsage>

  suspend fun saveActiveUsageSession(session: PersistedActiveUsageSession): PersistenceResult<Unit>

  suspend fun getActiveUsageSession(): PersistenceResult<PersistedActiveUsageSession?>

  suspend fun clearActiveUsageSession(): PersistenceResult<Unit>

  suspend fun getIntegrityCheckpoint(): PersistenceResult<UsageIntegrityCheckpoint?>

  suspend fun saveIntegrityCheckpoint(checkpoint: UsageIntegrityCheckpoint): PersistenceResult<Unit>
}
