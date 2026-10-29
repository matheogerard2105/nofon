package com.nofon.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Résumé local d'une journée civile, dans le fuseau de l'appareil — Phase 3 n'a pas
 * encore de fuseau figé, ça n'existe qu'à partir d'un objectif (Phase 4). Purement un
 * cache client pour affichage ; aucune conséquence financière n'en dépend
 * (`ARCHITECTURE-2.md` §3 : le serveur reste seul décisionnaire une fois les
 * objectifs introduits).
 *
 * `nofonScreenQuality`/`totalForegroundQuality`/`unlocksQuality`/`appLaunchesQuality`
 * reprennent le vocabulaire `Quality` du domaine (`MEASURED`/`RECONSTRUCTED`/
 * `ESTIMATED`/`UNAVAILABLE`), stocké en texte pour rester lisible en base.
 */
@Entity(tableName = "daily_aggregate")
data class DailyAggregateEntity(
    @PrimaryKey val localDate: String,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val nofonScreenMillis: Long,
    val nofonScreenQuality: String,
    val totalForegroundMillis: Long,
    val totalForegroundQuality: String,
    val unlocks: Int,
    val unlocksQuality: String,
    val appLaunches: Int,
    val appLaunchesQuality: String,
    val eventCount: Int,
    val anomalyCount: Int,
    val gapCount: Int,
    /** `MEASURED` (flavor `real`) ou `SIMULATED` (flavor `simulated`) — jamais mélangés. */
    val source: String,
    val lastUpdatedMillis: Long
)
