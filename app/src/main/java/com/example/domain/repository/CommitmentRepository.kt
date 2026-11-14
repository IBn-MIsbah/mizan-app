package com.example.domain.repository

import com.example.domain.model.Commitment
import com.example.domain.model.CommitmentId
import kotlinx.coroutines.flow.Flow

interface CommitmentRepository {
  fun observeActiveCommitment(): Flow<PersistenceResult<Commitment?>>

  fun observeAllCommitments(): Flow<PersistenceResult<List<Commitment>>>

  suspend fun getCommitment(id: CommitmentId): PersistenceResult<Commitment?>

  suspend fun saveCommitment(commitment: Commitment): PersistenceResult<Unit>

  suspend fun deleteCommitment(id: CommitmentId): PersistenceResult<Unit>
}
