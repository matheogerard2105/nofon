package com.nofon.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Lu depuis `goals`. Voir `supabase/migrations/0002_goals.sql`. */
@Serializable
data class Goal(
    val id: String,
    @SerialName("user_id") val userId: String,
    val kind: String, // FREE / STAKED
    val status: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("constraint_type") val constraintType: String, // TIME / OPENS / BOTH
    @SerialName("time_limit_seconds") val timeLimitSeconds: Int? = null,
    @SerialName("opens_limit") val opensLimit: Int? = null,
    val timezone: String,
    @SerialName("max_committed_cents") val maxCommittedCents: Long,
    @SerialName("current_streak_days") val currentStreakDays: Int,
    @SerialName("best_streak_days") val bestStreakDays: Int = 0
)

/**
 * Payload d'insertion — ne contient QUE ce que le client renseigne. `id`, `status`,
 * `current_streak_days` sont des valeurs par défaut côté base (`0002_goals.sql`),
 * jamais fixées par le client.
 */
@Serializable
data class NewGoalRequest(
    @SerialName("user_id") val userId: String,
    val kind: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("constraint_type") val constraintType: String,
    @SerialName("time_limit_seconds") val timeLimitSeconds: Int? = null,
    @SerialName("opens_limit") val opensLimit: Int? = null,
    val timezone: String,
    @SerialName("max_committed_cents") val maxCommittedCents: Long
)

@Serializable
data class NewGoalExcludedApp(
    @SerialName("goal_id") val goalId: String,
    @SerialName("package_name") val packageName: String
)

@Serializable
data class DailyResult(
    @SerialName("goal_id") val goalId: String,
    @SerialName("local_date") val localDate: String,
    val status: String, // PENDING / PROVISIONAL / SUCCESS / FAILED / NO_DATA
    @SerialName("personal_success") val personalSuccess: Boolean? = null,
    @SerialName("daily_success") val dailySuccess: Boolean? = null,
    @SerialName("measured_time_seconds") val measuredTimeSeconds: Int? = null,
    @SerialName("measured_opens") val measuredOpens: Int? = null
)

@Serializable
data class DailyStakeShare(
    @SerialName("goal_id") val goalId: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("amount_cents") val amountCents: Long,
    val outcome: String // PENDING / SECURED / FORFEITED
)

/**
 * Un lot par jour clos ; l'évaluation (trigger `evaluate_measurement_batch`) est
 * entièrement côté serveur. `integrityToken` (TODO-30, `0007_hardening.sql`) : capture
 * uniquement, jamais décodé côté serveur pour l'instant — `null` si l'appel Play
 * Integrity échoue, jamais bloquant pour la soumission elle-même.
 */
@Serializable
data class NewMeasurementBatch(
    @SerialName("goal_id") val goalId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("measured_time_seconds") val measuredTimeSeconds: Int,
    @SerialName("measured_opens") val measuredOpens: Int,
    @SerialName("data_quality") val dataQuality: String, // MEASURED / RECONSTRUCTED / ESTIMATED / UNAVAILABLE
    @SerialName("integrity_token") val integrityToken: String?
)

/**
 * Mesure provisoire de la journée en cours (`0017_live_day_measurements.sql`), mise à
 * jour à chaque passage du worker de fond — purement d'affichage (profil, graphique de
 * groupe), jamais évaluée : seul [NewMeasurementBatch] a une conséquence.
 */
@Serializable
data class LiveDayMeasurement(
    @SerialName("goal_id") val goalId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("measured_time_seconds") val measuredTimeSeconds: Int,
    @SerialName("measured_opens") val measuredOpens: Int,
    @SerialName("data_quality") val dataQuality: String,
    @SerialName("updated_at") val updatedAt: String
)
