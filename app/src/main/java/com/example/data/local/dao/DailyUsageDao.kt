package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.ActiveUsageSessionEntity
import com.example.data.local.entity.DailyUsageEntity
import com.example.data.local.entity.DailyUsageWithEvents
import com.example.data.local.entity.UsageEventEntity
import com.example.data.local.entity.UsageIntegrityStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyUsageDao {

  @Transaction
  @Query(
    "SELECT * FROM daily_usages WHERE commitment_id = :commitmentId AND local_date = :localDate LIMIT 1"
  )
  fun observeDailyUsageWithEvents(
    commitmentId: String,
    localDate: String,
  ): Flow<DailyUsageWithEvents?>

  @Transaction
  @Query(
    "SELECT * FROM daily_usages WHERE commitment_id = :commitmentId AND local_date = :localDate LIMIT 1"
  )
  suspend fun getDailyUsageWithEvents(
    commitmentId: String,
    localDate: String,
  ): DailyUsageWithEvents?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertDailyUsage(entity: DailyUsageEntity): Long

  @Query(
    """
    UPDATE daily_usages
    SET consumed_millis = :consumedMillis,
        coverage_status = :coverageStatus
    WHERE id = :dailyUsageId
    """
  )
  suspend fun updateConsumedAndCoverage(
    dailyUsageId: Long,
    consumedMillis: Long,
    coverageStatus: String,
  )

  @Query(
    """
    UPDATE daily_usages
    SET coverage_status = :coverageStatus,
        incomplete_reason = :incompleteReason
    WHERE id = :dailyUsageId
    """
  )
  suspend fun updateCoverageStatus(
    dailyUsageId: Long,
    coverageStatus: String,
    incompleteReason: String?,
  )

  @Query(
    """
    UPDATE daily_usages
    SET last_reconciled_epoch_millis = :reconciledAtEpochMillis
    WHERE id = :dailyUsageId
    """
  )
  suspend fun updateLastReconciledAt(
    dailyUsageId: Long,
    reconciledAtEpochMillis: Long,
  )

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertUsageEventIgnore(entity: UsageEventEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertActiveSession(entity: ActiveUsageSessionEntity)

  @Query("SELECT * FROM active_usage_sessions WHERE id = 1 LIMIT 1")
  suspend fun getActiveSession(): ActiveUsageSessionEntity?

  @Query("DELETE FROM active_usage_sessions")
  suspend fun clearActiveSession()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertIntegrityState(entity: UsageIntegrityStateEntity)

  @Query("SELECT * FROM usage_integrity_state WHERE id = 1 LIMIT 1")
  suspend fun getIntegrityState(): UsageIntegrityStateEntity?
}
