package com.nofon.core.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseGoalRepository @Inject constructor(
    private val client: SupabaseClient
) : GoalRepository {

    override suspend fun createGoal(request: NewGoalRequest, excludedPackages: List<String>): Result<Goal> =
        runCatching {
            val goal = client.from("goals").insert(request) { select() }.decodeSingle<Goal>()
            if (excludedPackages.isNotEmpty()) {
                client.from("goal_excluded_apps").insert(
                    excludedPackages.map { NewGoalExcludedApp(goalId = goal.id, packageName = it) }
                )
            }
            goal
        }

    override suspend fun activeStakedGoal(userId: String): Result<Goal?> = runCatching {
        // PRODUCT_SPEC-2.md §2.1 : un seul objectif payant actif à la fois — invariant
        // produit, jamais imposé en base (aucune contrainte d'unicité sur (user_id,
        // kind, status)). Sans tri explicite, un SELECT sans ORDER BY n'a pas d'ordre
        // garanti : si plusieurs lignes existent quand même (ex. données de test),
        // le résultat pouvait varier d'un appel à l'autre — constaté en conditions
        // réelles (rattrapage TrackingSyncWorker ciblant un goal_id différent selon les
        // exécutions). La plus récemment créée fait foi.
        client.from("goals").select {
            filter {
                eq("user_id", userId)
                eq("kind", "STAKED")
                eq("status", "ACTIVE")
            }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeList<Goal>().firstOrNull()
    }

    override suspend fun latestStakedGoal(userId: String): Result<Goal?> = runCatching {
        client.from("goals").select {
            filter {
                eq("user_id", userId)
                eq("kind", "STAKED")
            }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeList<Goal>().firstOrNull()
    }

    override suspend fun activeFreeGoals(userId: String): Result<List<Goal>> = runCatching {
        client.from("goals").select {
            filter {
                eq("user_id", userId)
                eq("kind", "FREE")
                eq("status", "ACTIVE")
            }
        }.decodeList<Goal>()
    }

    override suspend fun allGoals(userId: String): Result<List<Goal>> = runCatching {
        client.from("goals").select {
            filter { eq("user_id", userId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Goal>()
    }

    override suspend fun excludedApps(goalId: String): Result<List<String>> = runCatching {
        client.from("goal_excluded_apps").select {
            filter { eq("goal_id", goalId) }
        }.decodeList<NewGoalExcludedApp>().map { it.packageName }
    }

    override suspend fun dailyResults(goalId: String): Result<List<DailyResult>> = runCatching {
        client.from("daily_results").select {
            filter { eq("goal_id", goalId) }
        }.decodeList<DailyResult>()
    }

    override suspend fun stakeShares(goalId: String): Result<List<DailyStakeShare>> = runCatching {
        client.from("daily_stake_shares").select {
            filter { eq("goal_id", goalId) }
        }.decodeList<DailyStakeShare>()
    }

    override suspend fun submitMeasurementBatch(batch: NewMeasurementBatch): Result<Unit> = runCatching {
        client.from("measurement_batches").insert(batch)
        Unit
    }

    override suspend fun upsertLiveDayMeasurement(measurement: LiveDayMeasurement): Result<Unit> = runCatching {
        client.from("live_day_measurements").upsert(measurement)
        Unit
    }

    override suspend fun hasBatchFor(goalId: String, localDate: String): Result<Boolean> = runCatching {
        client.from("measurement_batches").select {
            filter {
                eq("goal_id", goalId)
                eq("local_date", localDate)
            }
        }.decodeList<NewMeasurementBatch>().isNotEmpty()
    }
}
