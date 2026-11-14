package com.example.data.repository

import android.database.sqlite.SQLiteException
import com.example.core.common.DispatcherProvider
import com.example.data.local.dao.RemovalRequestDao
import com.example.data.local.mapper.CorruptLocalDataException
import com.example.data.local.mapper.PersistenceMappers.toDomain
import com.example.data.local.mapper.PersistenceMappers.toEntity
import com.example.domain.model.RemovalRequest
import com.example.domain.repository.PersistenceError
import com.example.domain.repository.PersistenceResult
import com.example.domain.repository.RemovalRequestRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class RoomRemovalRequestRepository
@Inject
constructor(
  private val removalRequestDao: RemovalRequestDao,
  private val dispatcherProvider: DispatcherProvider,
) : RemovalRequestRepository {

  override fun observeLatestRequest(): Flow<PersistenceResult<RemovalRequest?>> =
    removalRequestDao
      .observeLatest()
      .map<_, PersistenceResult<RemovalRequest?>> { entity ->
        PersistenceResult.Success(entity?.toDomain())
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)

  override suspend fun getLatestOpenRequest(): PersistenceResult<RemovalRequest?> =
    withContext(dispatcherProvider.io) {
      try {
        PersistenceResult.Success(removalRequestDao.getLatestOpen()?.toDomain())
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  override suspend fun saveRequest(request: RemovalRequest): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        removalRequestDao.upsert(request.toEntity())
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  private fun mapThrowable(t: Throwable): PersistenceError =
    when (t) {
      is CorruptLocalDataException ->
        PersistenceError.CorruptData(t.message ?: "Corrupt removal request record")
      is SQLiteException ->
        PersistenceError.StorageFailure("Local database operation failed")
      else -> throw t
    }
}
