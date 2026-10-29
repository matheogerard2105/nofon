package com.nofon.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyAggregateDao {

    @Upsert
    suspend fun upsertDay(day: DailyAggregateEntity)

    @Query("SELECT * FROM daily_aggregate WHERE localDate = :localDate")
    fun observeDay(localDate: String): Flow<DailyAggregateEntity?>

    @Query("SELECT * FROM daily_app_usage WHERE localDate = :localDate ORDER BY foregroundMillis DESC")
    fun observeAppUsageForDay(localDate: String): Flow<List<DailyAppUsageEntity>>

    @Query("DELETE FROM daily_app_usage WHERE localDate = :localDate")
    suspend fun clearAppUsageForDay(localDate: String)

    @Upsert
    suspend fun insertAppUsage(usage: List<DailyAppUsageEntity>)

    /** Remplace la répartition par application du jour — évite d'accumuler des lignes obsolètes entre deux synchros. */
    @Transaction
    suspend fun replaceAppUsageForDay(localDate: String, usage: List<DailyAppUsageEntity>) {
        clearAppUsageForDay(localDate)
        insertAppUsage(usage)
    }
}
