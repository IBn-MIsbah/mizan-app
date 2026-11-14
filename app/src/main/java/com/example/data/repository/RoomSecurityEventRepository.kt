package com.example.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import com.example.core.common.DispatcherProvider
import com.example.data.local.dao.SecurityEventDao
import com.example.data.local.mapper.CorruptLocalDataException
import com.example.data.local.mapper.PersistenceMappers.toDomain
import com.example.data.local.mapper.PersistenceMappers.toEntity
import com.example.domain.model.CommitmentId
import com.example.domain.model.SecurityEvent
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.SecurityEventRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class RoomSecurityEventRepository
@Inject
constructor(
  private val securityEventDao: SecurityEventDao,
  private val dispatcherProvider: DispatcherProvider,
) : SecurityEventRepository {

  override fun observeSecurityEvents(
    commitmentId: CommitmentId?,
  ): Flow<PersistenceResult<List<SecurityEvent>>> {
    val source =
      if (commitmentId == null) {
        securityEventDao.observeAll()
      } else {
        securityEventDao.observeForCommitment(commitmentId.value)
      }
    return source
      .map<_, PersistenceResult<List<SecurityEvent>>> { list ->
        PersistenceResult.Success(list.map { it.toDomain() })
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)
  }

  override suspend fun recordEvent(event: SecurityEvent): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        securityEventDao.insert(event.toEntity())
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  private fun mapThrowable(t: Throwable): PersistenceError =
    when (t) {
      is CorruptLocalDataException ->
        PersistenceError.CorruptData(t.message ?: "Corrupt security event record")
      is SQLiteConstraintException ->
        PersistenceError.ConstraintViolation("Security event constraint violation")
      is SQLiteException ->
        PersistenceError.StorageFailure("Local database operation failed")
      else -> throw t
    }
}
