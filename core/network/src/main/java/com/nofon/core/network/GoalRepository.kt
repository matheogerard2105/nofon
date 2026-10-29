package com.nofon.core.network

/**
 * Objectifs. Le client n'écrit jamais dans `daily_results` ni `daily_stake_shares` —
 * seuls `goals`, `goal_excluded_apps` et `measurement_batches` acceptent une
 * insertion côté client (ARCHITECTURE-2.md §3, RLS de `0002_goals.sql`). Aucun
 * UPDATE ni DELETE n'est jamais exposé : un objectif lancé ne peut plus être modifié
 * ni abandonné (PRODUCT_SPEC-2.md §2.3).
 */
interface GoalRepository {

    /**
     * Crée l'objectif puis ses exceptions figées. Deux appels REST distincts, sans
     * transaction couvrant les deux (limite connue, acceptable à ce stade) : en cas
     * d'échec sur les exceptions après un objectif déjà créé, l'appelant le signale
     * plutôt que de le masquer.
     */
    suspend fun createGoal(request: NewGoalRequest, excludedPackages: List<String>): Result<Goal>

    /** Le seul objectif payant actif de l'utilisateur, s'il existe (PRODUCT_SPEC-2.md §2.1 : un seul à la fois). */
    suspend fun activeStakedGoal(userId: String): Result<Goal?>

    /** Le plus récent objectif payant, quel que soit son statut (y compris COMPLETED) — sert à détecter la fin d'un objectif. */
    suspend fun latestStakedGoal(userId: String): Result<Goal?>

    /** Plusieurs possibles simultanément (PRODUCT_SPEC-2.md §2.1). Affichage seul pour l'instant — TODO-24. */
    suspend fun activeFreeGoals(userId: String): Result<List<Goal>>

    /**
     * Tous les objectifs de l'utilisateur, tout statut confondu — "historique des
     * objectifs" du profil (TODO-32, PRODUCT_TODOS-2.md). RLS `goals_select_self`
     * (0002_goals.sql) limite cet appel à ses propres objectifs uniquement : jamais
     * appelable avec un autre userId que le sien.
     */
    suspend fun allGoals(userId: String): Result<List<Goal>>

    /** Paquets exclus de l'objectif, figés à la création (TODO-07) — jamais modifiables ensuite. */
    suspend fun excludedApps(goalId: String): Result<List<String>>

    suspend fun dailyResults(goalId: String): Result<List<DailyResult>>

    suspend fun stakeShares(goalId: String): Result<List<DailyStakeShare>>

    suspend fun submitMeasurementBatch(batch: NewMeasurementBatch): Result<Unit>

    /** Upsert de la mesure provisoire du jour — rejouable à volonté, sans conséquence. */
    suspend fun upsertLiveDayMeasurement(measurement: LiveDayMeasurement): Result<Unit>

    /** `true` si un lot a déjà été soumis pour ce jour (évite une resoumission inutile). */
    suspend fun hasBatchFor(goalId: String, localDate: String): Result<Boolean>
}
