package com.nofon.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NewFollow(
    @SerialName("follower_id") val followerId: String,
    @SerialName("followee_id") val followeeId: String
)

@Serializable
data class NewBlock(
    @SerialName("blocker_id") val blockerId: String,
    @SerialName("blocked_id") val blockedId: String
)

/** Écrit dans `reports` — capture uniquement, aucune modération automatisée (voir `0005_social.sql`). */
@Serializable
data class NewReport(
    @SerialName("reporter_id") val reporterId: String,
    @SerialName("target_type") val targetType: String, // USER / COMMENT / FEED_EVENT
    @SerialName("target_id") val targetId: String,
    val reason: String
)

/**
 * Ligne minimale exposée par `users` pour un résultat de recherche, une liste
 * d'abonnés/abonnements, ou une liste d'utilisateurs bloqués — colonnes publiques
 * uniquement (PRODUCT_SPEC-2.md §10.1, TODO-18).
 */
@Serializable
data class UserSearchResult(
    val id: String,
    val handle: String,
    @SerialName("avatar_url") val avatarUrl: String? = null
)
