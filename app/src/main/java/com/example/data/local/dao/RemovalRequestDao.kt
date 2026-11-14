package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.RemovalRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemovalRequestDao {

  @Query("SELECT * FROM removal_requests ORDER BY requested_at_epochMillis DESC LIMIT 1")
  fun observeLatest(): Flow<RemovalRequestEntity?>

  @Query(
    "SELECT * FROM removal_requests WHERE status IN ('PENDING', 'READY_TO_EXECUTE') ORDER BY requested_at_epochMillis DESC LIMIT 1"
  )
  suspend fun getLatestOpen(): RemovalRequestEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: RemovalRequestEntity)
}
