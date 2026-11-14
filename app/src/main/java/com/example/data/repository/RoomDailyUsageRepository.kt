package com.example.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.example.core.common.DispatcherProvider
import com.example.data.local.dao.DailyUsageDao
import com.example.data.local.db.MizanDatabase
import com.example.data.local.entity.DailyUsageEntity
import com.example.data.local.mapper.CorruptLocalDataException
import com.example.data.local.mapper.PersistenceMappers.toDomain
import com.example.data.local.mapper.PersistenceMappers.toEntity
import com.example.data.local.mapper.PersistenceMappers.toIsoString
import com.example.domain.allowance.AllowanceError
import com.example.domain.allowance.AllowanceRecordOutcome
import com.example.domain.allowance.AllowanceRecordResult
import com.example.domain.allowance.SharedAllowanceAccountant
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.model.DailyUsage
import com.example.domain.model.PersistedActiveUsageSession
import com.example.domain.model.UsageCoverageStatus
import com.example.domain.model.UsageEvent
import com.example.domain.model.UsageIntegrityCheckpoint
import com.example.domain.repository.DailyUsageRepository
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.domain.time.DomainDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class RoomDailyUsageRepository
@Inject
constructor(
  private val database: MizanDatabase,
  private val dailyUsageDao: DailyUsageDao,
  private val dispatcherProvider: DispatcherProvider,
) : DailyUsageRepository {

  override fun observeDailyUsage(
    commitmentId: CommitmentId,
    date: DomainDate,
  ): Flow<PersistenceResult<DailyUsage>> =
    dailyUsageDao
      .observeDailyUsageWithEvents(commitmentId.value, date.toIsoString())
      .map<_, PersistenceResult<DailyUsage>> { relation ->
        val domain = relation?.toDomain() ?: DailyUsage.empty(commitmentId, date)
        PersistenceResult.Success(domain)
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)

  override suspend fun getDailyUsage(
    commitmentId: CommitmentId,
    date: DomainDate,
  ): PersistenceResult<DailyUsage> =
    withContext(dispatcherProvider.io) {
      try {
        val relation = dailyUsageDao.getDailyUsageWithEvents(commitmentId.value, date.toIsoString())
        PersistenceResult.Success(relation?.toDomain() ?: DailyUsage.empty(commitmentId, date))
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun recordUsageEvent(
    commitment: Commitment,
    event: UsageEvent,
  ): PersistenceResult<DailyUsage> =
    withContext(dispatcherProvider.io) {
      try {
        database.withTransaction {
          val dateIso = event.localDate.toIsoString()
          val existingRelation = dailyUsageDao.getDailyUsageWithEvents(commitment.id.value, dateIso)
          val existingDomain = existingRelation?.toDomain()

          when (val result = SharedAllowanceAccountant.recordUsage(commitment, existingDomain, event)) {
            is AllowanceRecordResult.Failure -> {
              val detail =
                when (result.error) {
                  AllowanceError.CommitmentNotActive -> "Commitment is not active"
                  AllowanceError.UsageCommitmentMismatch -> "Usage commitment mismatch"
                  AllowanceError.OlderDateCannotOverwriteCurrentDay ->
                    "Event date precedes current daily usage record"
                }
              PersistenceResult.Failure(PersistenceError.DomainRuleViolation(detail))
            }

            is AllowanceRecordResult.Success -> {
              val updatedDomain = result.dailyUsage
              if (
                result.outcome == AllowanceRecordOutcome.APPLIED ||
                  result.outcome == AllowanceRecordOutcome.RESET_FOR_NEW_DATE_AND_APPLIED
              ) {
                val dailyUsageId =
                  existingRelation?.dailyUsage?.id
                    ?: dailyUsageDao.insertDailyUsage(
                      DailyUsageEntity(
                        commitmentId = commitment.id.value,
                        localDate = dateIso,
                        consumedMillis = 0L,
                        coverageStatus = updatedDomain.coverageStatus.name,
                      )
                    )
                val insertedRow =
                  dailyUsageDao.insertUsageEventIgnore(
                    event.toEntity(dailyUsageId = dailyUsageId, commitmentId = commitment.id)
                  )
                if (insertedRow != -1L) {
                  dailyUsageDao.updateConsumedAndCoverage(
                    dailyUsageId = dailyUsageId,
                    consumedMillis = updatedDomain.consumed.inWholeMilliseconds,
                    coverageStatus = updatedDomain.coverageStatus.name,
                  )
                }
              }
              PersistenceResult.Success(updatedDomain)
            }
          }
        }
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun markDayIncomplete(
    commitmentId: CommitmentId,
    date: DomainDate,
    reason: String,
  ): PersistenceResult<DailyUsage> =
    withContext(dispatcherProvider.io) {
      try {
        database.withTransaction {
          val dateIso = date.toIsoString()
          val existingRelation = dailyUsageDao.getDailyUsageWithEvents(commitmentId.value, dateIso)
          val dailyUsageId =
            existingRelation?.dailyUsage?.id
              ?: dailyUsageDao.insertDailyUsage(
                DailyUsageEntity(
                  commitmentId = commitmentId.value,
                  localDate = dateIso,
                  consumedMillis = 0L,
                  coverageStatus = UsageCoverageStatus.INCOMPLETE.name,
                  incompleteReason = reason,
                )
              )
          dailyUsageDao.updateCoverageStatus(
            dailyUsageId = dailyUsageId,
            coverageStatus = UsageCoverageStatus.INCOMPLETE.name,
            incompleteReason = reason,
          )
          val refreshed = dailyUsageDao.getDailyUsageWithEvents(commitmentId.value, dateIso)
          PersistenceResult.Success(refreshed?.toDomain() ?: DailyUsage.empty(commitmentId, date))
        }
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun markReconciliationAttempt(
    commitmentId: CommitmentId,
    date: DomainDate,
    reconciledAtEpochMillis: Long,
    coverageStatus: UsageCoverageStatus?,
    incompleteReason: String?,
  ): PersistenceResult<DailyUsage> =
    withContext(dispatcherProvider.io) {
      try {
        database.withTransaction {
          val dateIso = date.toIsoString()
          val existingRelation = dailyUsageDao.getDailyUsageWithEvents(commitmentId.value, dateIso)
          val initialCoverage = coverageStatus ?: UsageCoverageStatus.MEASURED
          val dailyUsageId =
            existingRelation?.dailyUsage?.id
              ?: dailyUsageDao.insertDailyUsage(
                DailyUsageEntity(
                  commitmentId = commitmentId.value,
                  localDate = dateIso,
                  consumedMillis = 0L,
                  coverageStatus = initialCoverage.name,
                  incompleteReason = incompleteReason,
                  lastReconciledEpochMillis = reconciledAtEpochMillis,
                )
              )
          if (coverageStatus != null) {
            val currentStatus =
              existingRelation?.dailyUsage?.coverageStatus?.let { raw ->
                UsageCoverageStatus.entries.firstOrNull { it.name == raw }
              }
            // Do not silently downgrade an existing INCOMPLETE marker unless caller kept it consistent
            val targetStatus =
              if (currentStatus == UsageCoverageStatus.INCOMPLETE && coverageStatus != UsageCoverageStatus.INCOMPLETE) {
                UsageCoverageStatus.INCOMPLETE
              } else {
                coverageStatus
              }
            val targetReason =
              if (currentStatus == UsageCoverageStatus.INCOMPLETE && existingRelation?.dailyUsage?.incompleteReason != null) {
                existingRelation.dailyUsage.incompleteReason
              } else {
                incompleteReason
              }
            dailyUsageDao.updateCoverageStatus(
              dailyUsageId = dailyUsageId,
              coverageStatus = targetStatus.name,
              incompleteReason = targetReason,
            )
          }
          dailyUsageDao.updateLastReconciledAt(dailyUsageId, reconciledAtEpochMillis)
          val refreshed = dailyUsageDao.getDailyUsageWithEvents(commitmentId.value, dateIso)
          PersistenceResult.Success(refreshed?.toDomain() ?: DailyUsage.empty(commitmentId, date))
        }
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override fun observeRecentDailyUsage(limit: Int): Flow<PersistenceResult<List<DailyUsage>>> =
    dailyUsageDao
      .observeRecentDailyUsage(limit.coerceIn(1, 365))
      .map<_, PersistenceResult<List<DailyUsage>>> { list ->
        PersistenceResult.Success(list.map { it.toDomain() })
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)

  override fun observeDailyUsageForCommitment(
    commitmentId: CommitmentId,
  ): Flow<PersistenceResult<List<DailyUsage>>> =
    dailyUsageDao
      .observeDailyUsageForCommitment(commitmentId.value)
      .map<_, PersistenceResult<List<DailyUsage>>> { list ->
        PersistenceResult.Success(list.map { it.toDomain() })
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)

  override suspend fun recordBlockedAttempt(
    commitmentId: CommitmentId,
    date: DomainDate,
  ): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        database.withTransaction {
          val dateIso = date.toIsoString()
          val existingRelation = dailyUsageDao.getDailyUsageWithEvents(commitmentId.value, dateIso)
          val dailyUsageId =
            existingRelation?.dailyUsage?.id
              ?: dailyUsageDao.insertDailyUsage(
                DailyUsageEntity(
                  commitmentId = commitmentId.value,
                  localDate = dateIso,
                  consumedMillis = 0L,
                  coverageStatus = UsageCoverageStatus.MEASURED.name,
                  blockedAttempts = 0,
                )
              )
          dailyUsageDao.incrementBlockedAttempts(dailyUsageId)
        }
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun saveActiveUsageSession(
    session: PersistedActiveUsageSession,
  ): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dailyUsageDao.upsertActiveSession(session.toEntity())
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun getActiveUsageSession(): PersistenceResult<PersistedActiveUsageSession?> =
    withContext(dispatcherProvider.io) {
      try {
        PersistenceResult.Success(dailyUsageDao.getActiveSession()?.toDomain())
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun clearActiveUsageSession(): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dailyUsageDao.clearActiveSession()
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun getIntegrityCheckpoint(): PersistenceResult<UsageIntegrityCheckpoint?> =
    withContext(dispatcherProvider.io) {
      try {
        PersistenceResult.Success(dailyUsageDao.getIntegrityState()?.toDomain())
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun saveIntegrityCheckpoint(
    checkpoint: UsageIntegrityCheckpoint,
  ): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        dailyUsageDao.upsertIntegrityState(checkpoint.toEntity())
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  private fun mapThrowable(t: Throwable): PersistenceError =
    when (t) {
      is CorruptLocalDataException ->
        PersistenceError.CorruptData(t.message ?: "Corrupt daily usage record")
      is SQLiteConstraintException ->
        PersistenceError.ConstraintViolation("Daily usage constraint violation")
      is SQLiteException ->
        PersistenceError.StorageFailure("Local database operation failed")
      else -> throw t
    }
}
