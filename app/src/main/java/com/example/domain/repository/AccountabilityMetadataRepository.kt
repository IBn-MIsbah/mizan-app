package com.example.domain.repository

import com.example.domain.model.AccountabilityMetadata
import kotlinx.coroutines.flow.Flow

interface AccountabilityMetadataRepository {
  fun observeMetadata(): Flow<PersistenceResult<AccountabilityMetadata>>

  suspend fun updateMetadata(metadata: AccountabilityMetadata): PersistenceResult<Unit>
}
