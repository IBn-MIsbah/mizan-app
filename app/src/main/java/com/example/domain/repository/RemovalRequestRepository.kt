package com.example.domain.repository

import com.example.domain.model.RemovalRequest
import kotlinx.coroutines.flow.Flow

interface RemovalRequestRepository {
  fun observeLatestRequest(): Flow<PersistenceResult<RemovalRequest?>>

  suspend fun getLatestOpenRequest(): PersistenceResult<RemovalRequest?>

  suspend fun saveRequest(request: RemovalRequest): PersistenceResult<Unit>
}
