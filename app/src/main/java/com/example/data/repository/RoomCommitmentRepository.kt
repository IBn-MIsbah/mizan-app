package com.example.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import com.example.core.common.DispatcherProvider
import com.example.data.local.dao.CommitmentDao
import com.example.data.local.mapper.CorruptLocalDataException
import com.example.data.local.mapper.PersistenceMappers.toDomain
import com.example.data.local.mapper.PersistenceMappers.toEntities
import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import com.example.domain.repository.CommitmentRepository
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class RoomCommitmentRepository
@Inject
constructor(
  private val commitmentDao: CommitmentDao,
  private val dispatcherProvider: DispatcherProvider,
) : CommitmentRepository {

  override fun observeActiveCommitment(): Flow<PersistenceResult<Commitment?>> =
    commitmentDao
      .observeActiveCommitment()
      .map<_, PersistenceResult<Commitment?>> { relation ->
        PersistenceResult.Success(relation?.toDomain())
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)

  override fun observeAllCommitments(): Flow<PersistenceResult<List<Commitment>>> =
    commitmentDao
      .observeAllCommitments()
      .map<_, PersistenceResult<List<Commitment>>> { list ->
        PersistenceResult.Success(list.map { it.toDomain() })
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)

  override suspend fun getCommitment(id: CommitmentId): PersistenceResult<Commitment?> =
    withContext(dispatcherProvider.io) {
      try {
        PersistenceResult.Success(commitmentDao.getCommitmentWithRelations(id.value)?.toDomain())
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun saveCommitment(commitment: Commitment): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        val bundle = commitment.toEntities()
        commitmentDao.saveFullCommitment(
          commitment = bundle.commitment,
          rules = bundle.rules,
          restrictedApps = bundle.restrictedApps,
          allowedApps = bundle.allowedApps,
          scheduleWindows = bundle.scheduleWindows,
          blockedDomains = bundle.blockedDomains,
        )
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun deleteCommitment(id: CommitmentId): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        val deleted = commitmentDao.deleteCommitmentById(id.value)
        if (deleted > 0) {
          PersistenceResult.Success(Unit)
        } else {
          PersistenceResult.Failure(PersistenceError.NotFound("Commitment"))
        }
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  private fun mapThrowable(t: Throwable): PersistenceError =
    when (t) {
      is CorruptLocalDataException ->
        PersistenceError.CorruptData(t.message ?: "Corrupt commitment record")
      is SQLiteConstraintException ->
        PersistenceError.ConstraintViolation("Commitment constraint violation")
      is SQLiteException ->
        PersistenceError.StorageFailure("Local database operation failed")
      else -> throw t
    }
}
