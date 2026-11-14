package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.SecurityEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurityEventDao {

  @Query("SELECT * FROM security_events ORDER BY occurred_at_epochMillis DESC")
  fun observeAll(): Flow<List<SecurityEventEntity>>

  @Query(
    "SELECT * FROM security_events WHERE commitment_id = :commitmentId ORDER BY occurred_at_epochMillis DESC"
  )
  fun observeForCommitment(commitmentId: String): Flow<List<SecurityEventEntity>>

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: SecurityEventEntity)
}
