package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.AccountabilityMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountabilityMetadataDao {

  @Query("SELECT * FROM accountability_metadata WHERE id = :id LIMIT 1")
  fun observe(id: Int = AccountabilityMetadataEntity.SINGLETON_ID): Flow<AccountabilityMetadataEntity?>

  @Query("SELECT EXISTS(SELECT 1 FROM accountability_metadata WHERE id = :id)")
  suspend fun exists(id: Int = AccountabilityMetadataEntity.SINGLETON_ID): Boolean

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: AccountabilityMetadataEntity)

  @Update
  suspend fun update(entity: AccountabilityMetadataEntity)

  @Transaction
  suspend fun upsert(entity: AccountabilityMetadataEntity) {
    if (exists(entity.id)) {
      update(entity)
    } else {
      insert(entity)
    }
  }
}
