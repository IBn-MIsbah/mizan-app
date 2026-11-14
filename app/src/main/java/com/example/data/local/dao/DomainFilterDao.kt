package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.FilterDatasetDomainEntity
import com.example.data.local.entity.FilterDatasetMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainFilterDao {

  @Query("SELECT * FROM filter_datasets ORDER BY category ASC, dataset_id ASC")
  fun observeDatasets(): Flow<List<FilterDatasetMetadataEntity>>

  @Query("SELECT * FROM filter_datasets WHERE dataset_id = :datasetId LIMIT 1")
  suspend fun getDatasetMetadata(datasetId: String): FilterDatasetMetadataEntity?

  @Query("SELECT * FROM filter_datasets WHERE category IN (:categories)")
  suspend fun getDatasetsByCategories(categories: List<String>): List<FilterDatasetMetadataEntity>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertDatasetsIgnore(entities: List<FilterDatasetMetadataEntity>)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertDatasetMetadataIgnore(entity: FilterDatasetMetadataEntity): Long

  @Update
  suspend fun updateDatasetMetadata(entity: FilterDatasetMetadataEntity)

  @Transaction
  suspend fun upsertDatasetMetadata(entity: FilterDatasetMetadataEntity) {
    if (insertDatasetMetadataIgnore(entity) == -1L) {
      updateDatasetMetadata(entity)
    }
  }

  @Query(
    "SELECT normalized_domain FROM filter_dataset_domains WHERE dataset_id = :datasetId AND generation = :generation"
  )
  suspend fun getDomainsByGeneration(datasetId: String, generation: String): List<String>

  @Query(
    "DELETE FROM filter_dataset_domains WHERE dataset_id = :datasetId AND generation = :generation"
  )
  suspend fun deleteDomainsByGeneration(datasetId: String, generation: String)

  @Query(
    "UPDATE filter_dataset_domains SET generation = :newGeneration WHERE dataset_id = :datasetId AND generation = :oldGeneration"
  )
  suspend fun promoteGeneration(datasetId: String, oldGeneration: String, newGeneration: String)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertDatasetDomains(entities: List<FilterDatasetDomainEntity>)

  /**
   * Atomically stages verified domains, demotes the previous ACTIVE set to ROLLBACK for
   * disaster recovery, and promotes STAGING to ACTIVE inside a single SQLite transaction.
   */
  @Transaction
  suspend fun activateVerifiedDataset(
    metadata: FilterDatasetMetadataEntity,
    domains: List<String>,
  ) {
    upsertDatasetMetadata(metadata)
    val datasetId = metadata.datasetId
    deleteDomainsByGeneration(datasetId, GENERATION_STAGING)
    if (domains.isNotEmpty()) {
      domains.chunked(500).forEach { chunk ->
        insertDatasetDomains(
          chunk.map { domain ->
            FilterDatasetDomainEntity(
              datasetId = datasetId,
              normalizedDomain = domain,
              generation = GENERATION_STAGING,
            )
          }
        )
      }
    }
    deleteDomainsByGeneration(datasetId, GENERATION_ROLLBACK)
    promoteGeneration(datasetId, GENERATION_ACTIVE, GENERATION_ROLLBACK)
    promoteGeneration(datasetId, GENERATION_STAGING, GENERATION_ACTIVE)
  }

  /**
   * Atomically restores the last known good ROLLBACK generation back to ACTIVE if available.
   */
  @Transaction
  suspend fun rollbackDatasetToLastKnownGood(
    datasetId: String,
    restoredMetadata: FilterDatasetMetadataEntity,
  ) {
    deleteDomainsByGeneration(datasetId, GENERATION_STAGING)
    deleteDomainsByGeneration(datasetId, GENERATION_ACTIVE)
    promoteGeneration(datasetId, GENERATION_ROLLBACK, GENERATION_ACTIVE)
    upsertDatasetMetadata(restoredMetadata)
  }

  companion object {
    const val GENERATION_ACTIVE = "ACTIVE"
    const val GENERATION_STAGING = "STAGING"
    const val GENERATION_ROLLBACK = "ROLLBACK"
  }
}
