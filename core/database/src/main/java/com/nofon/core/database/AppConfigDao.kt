package com.nofon.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppConfigDao {
    @Upsert
    suspend fun upsertAll(entries: List<AppConfigCacheEntity>)

    @Query("SELECT * FROM app_config_cache")
    fun observeAll(): Flow<List<AppConfigCacheEntity>>

    @Query("SELECT * FROM app_config_cache WHERE `key` = :key")
    suspend fun get(key: String): AppConfigCacheEntity?
}
