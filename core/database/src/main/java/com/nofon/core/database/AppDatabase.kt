package com.nofon.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AppConfigCacheEntity::class, DailyAggregateEntity::class, DailyAppUsageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appConfigDao(): AppConfigDao
    abstract fun dailyAggregateDao(): DailyAggregateDao
}
