package com.nofon.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lu depuis la vue `public_profile_stats` (`0005_social.sql`) — champs alignés sur
 * PRODUCT_SPEC-2.md §10.1 (TODO-18). Uniquement des totaux/maximums agrégés : aucune
 * ligne `goals` brute n'est exposée (la vue n'est pas `security_invoker`, exactement
 * comme `count_active_users` en 0004_exploits.sql). Ni "type général d'objectif" ni
 * "durée de l'objectif" (§10.1) ne sont couverts par cette itération — nécessiteraient
 * de choisir QUEL objectif afficher pour un utilisateur qui en a plusieurs, non tranché
 * (voir TODO-31, même souci pour `user_daily_measurements` ci-dessous).
 */
@Serializable
data class PublicProfile(
    @SerialName("user_id") val userId: String,
    val handle: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("current_streak_days") val currentStreakDays: Int? = null,
    @SerialName("best_streak_days") val bestStreakDays: Int,
    @SerialName("followers_count") val followersCount: Long,
    @SerialName("following_count") val followingCount: Long,
    @SerialName("active_goals_count") val activeGoalsCount: Long,
    @SerialName("completed_goals_count") val completedGoalsCount: Long,
    @SerialName("success_rate_percent") val successRatePercent: Double? = null
)

@Serializable
internal data class UpdateHandleRequest(val handle: String)

@Serializable
internal data class UpdateAvatarRequest(@SerialName("avatar_url") val avatarUrl: String)

/**
 * Lu depuis la vue `user_daily_measurements` (`0008_profile_screen_time.sql`, TODO-18
 * amendé les 2026-09-03/04 — temps d'écran et déverrouillages quotidiens agrégés,
 * publics). `dataQuality` (MEASURED / RECONSTRUCTED / ESTIMATED / UNAVAILABLE) permet au
 * graphique de laisser un trou plutôt qu'afficher un zéro trompeur sur UNAVAILABLE —
 * jamais un zéro silencieux (même principe NO_DATA que `daily_results`, TODO-06).
 */
@Serializable
data class DailyMeasurementPoint(
    @SerialName("local_date") val localDate: String,
    @SerialName("measured_time_seconds") val measuredTimeSeconds: Int,
    @SerialName("measured_opens") val measuredOpens: Int,
    @SerialName("data_quality") val dataQuality: String
)
