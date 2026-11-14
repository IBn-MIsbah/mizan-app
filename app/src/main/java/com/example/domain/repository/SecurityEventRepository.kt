package com.example.domain.repository

import com.example.domain.model.CommitmentId
import com.example.domain.model.SecurityEvent
import kotlinx.coroutines.flow.Flow

interface SecurityEventRepository {
  fun observeSecurityEvents(
    commitmentId: CommitmentId? = null,
  ): Flow<PersistenceResult<List<SecurityEvent>>>

  suspend fun recordEvent(event: SecurityEvent): PersistenceResult<Unit>
}
