package com.example.data.repository

import android.database.sqlite.SQLiteException
import com.example.core.common.DispatcherProvider
import com.example.data.local.dao.AccountabilityMetadataDao
import com.example.data.local.mapper.CorruptLocalDataException
import com.example.data.local.mapper.PersistenceMappers.toDomain
import com.example.data.local.mapper.PersistenceMappers.toEntity
import com.example.domain.model.AccountabilityMetadata
import com.example.domain.repository.AccountabilityMetadataRepository
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
class RoomAccountabilityMetadataRepository
@Inject
constructor(
  private val accountabilityMetadataDao: AccountabilityMetadataDao,
  private val dispatcherProvider: DispatcherProvider,
) : AccountabilityMetadataRepository {

  override fun observeMetadata(): Flow<PersistenceResult<AccountabilityMetadata>> =
    accountabilityMetadataDao
      .observe()
      .map<_, PersistenceResult<AccountabilityMetadata>> { entity ->
        PersistenceResult.Success(entity?.toDomain() ?: AccountabilityMetadata())
      }
      .catch { throwable -> emit(PersistenceResult.Failure(mapThrowable(throwable))) }
      .flowOn(dispatcherProvider.io)

  override suspend fun updateMetadata(metadata: AccountabilityMetadata): PersistenceResult<Unit> =
    withContext(dispatcherProvider.io) {
      try {
        accountabilityMetadataDao.upsert(metadata.toEntity())
        PersistenceResult.Success(Unit)
      } catch (t: Throwable) {
        PersistenceResult.Failure(mapThrowable(t))
      }
    }

  private fun mapThrowable(t: Throwable): PersistenceError =
    when (t) {
      is CorruptLocalDataException ->
        PersistenceError.CorruptData(t.message ?: "Corrupt accountability metadata")
      is SQLiteException ->
        PersistenceError.StorageFailure("Local database operation failed")
      else -> throw t
    }
}
