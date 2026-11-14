package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.AllowedAppEntity
import com.example.data.local.entity.CommitmentBlockedDomainEntity
import com.example.data.local.entity.CommitmentEntity
import com.example.data.local.entity.CommitmentRulesEntity
import com.example.data.local.entity.CommitmentWithRelations
import com.example.data.local.entity.RestrictedAppEntity
import com.example.data.local.entity.ScheduleWindowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommitmentDao {

  @Transaction
  @Query("SELECT * FROM commitments WHERE lifecycle_state = 'ACTIVE' ORDER BY created_at_epochMillis DESC LIMIT 1")
  fun observeActiveCommitment(): Flow<CommitmentWithRelations?>

  @Transaction
  @Query("SELECT * FROM commitments ORDER BY created_at_epochMillis DESC")
  fun observeAllCommitments(): Flow<List<CommitmentWithRelations>>

  @Transaction
  @Query("SELECT * FROM commitments WHERE id = :commitmentId LIMIT 1")
  suspend fun getCommitmentWithRelations(commitmentId: String): CommitmentWithRelations?

  @Query("SELECT EXISTS(SELECT 1 FROM commitments WHERE id = :commitmentId)")
  suspend fun commitmentExists(commitmentId: String): Boolean

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertCommitment(entity: CommitmentEntity)

  @Update
  suspend fun updateCommitment(entity: CommitmentEntity)

  @Query("SELECT EXISTS(SELECT 1 FROM commitment_rules WHERE commitment_id = :commitmentId)")
  suspend fun rulesExist(commitmentId: String): Boolean

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertRules(entity: CommitmentRulesEntity)

  @Update
  suspend fun updateRules(entity: CommitmentRulesEntity)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertRestrictedApps(entities: List<RestrictedAppEntity>)

  @Query("DELETE FROM restricted_apps WHERE commitment_id = :commitmentId")
  suspend fun deleteRestrictedApps(commitmentId: String)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertAllowedApps(entities: List<AllowedAppEntity>)

  @Query("DELETE FROM allowed_apps WHERE commitment_id = :commitmentId")
  suspend fun deleteAllowedApps(commitmentId: String)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertScheduleWindows(entities: List<ScheduleWindowEntity>)

  @Query("DELETE FROM schedule_windows WHERE commitment_id = :commitmentId")
  suspend fun deleteScheduleWindows(commitmentId: String)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertBlockedDomains(entities: List<CommitmentBlockedDomainEntity>)

  @Query("DELETE FROM commitment_blocked_domains WHERE commitment_id = :commitmentId")
  suspend fun deleteBlockedDomains(commitmentId: String)

  @Query("DELETE FROM commitments WHERE id = :commitmentId")
  suspend fun deleteCommitmentById(commitmentId: String): Int

  /**
   * Atomically persists a commitment and its normalized rules, restricted apps, allowed apps,
   * schedule windows, and blocked domains. Uses explicit insert/update instead of REPLACE on
   * `commitments` so existing `daily_usages`, `usage_events`, and `security_events` foreign keys
   * are never cascade-deleted during lifecycle updates.
   */
  @Transaction
  suspend fun saveFullCommitment(
    commitment: CommitmentEntity,
    rules: CommitmentRulesEntity,
    restrictedApps: List<RestrictedAppEntity>,
    allowedApps: List<AllowedAppEntity>,
    scheduleWindows: List<ScheduleWindowEntity>,
    blockedDomains: List<CommitmentBlockedDomainEntity> = emptyList(),
  ) {
    if (commitmentExists(commitment.id)) {
      updateCommitment(commitment)
    } else {
      insertCommitment(commitment)
    }

    if (rulesExist(commitment.id)) {
      updateRules(rules)
    } else {
      insertRules(rules)
    }

    deleteRestrictedApps(commitment.id)
    if (restrictedApps.isNotEmpty()) {
      insertRestrictedApps(restrictedApps)
    }

    deleteAllowedApps(commitment.id)
    if (allowedApps.isNotEmpty()) {
      insertAllowedApps(allowedApps)
    }

    deleteScheduleWindows(commitment.id)
    if (scheduleWindows.isNotEmpty()) {
      insertScheduleWindows(scheduleWindows)
    }

    deleteBlockedDomains(commitment.id)
    if (blockedDomains.isNotEmpty()) {
      insertBlockedDomains(blockedDomains)
    }
  }
}
