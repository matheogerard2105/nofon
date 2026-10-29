package com.nofon.core.database

import androidx.room.Entity

/** Répartition par application pour une journée locale (voir [DailyAggregateEntity]). */
@Entity(tableName = "daily_app_usage", primaryKeys = ["localDate", "packageName"])
data class DailyAppUsageEntity(
    val localDate: String,
    val packageName: String,
    val foregroundMillis: Long,
    val sessionCount: Int,
    val launchCount: Int
)
