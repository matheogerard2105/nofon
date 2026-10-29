package com.nofon.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lu depuis `notifications` (`0005_social.sql`). Aucune écriture client hormis
 * `read_at` (accusé de lecture) — peuplée par les triggers `notify_new_follower`/
 * `notify_reaction`/`notify_comment`. Types couverts par cette itération uniquement :
 * "réponse" et les événements de groupe restent hors périmètre (voir la table dans
 * `0005_social.sql`).
 */
@Serializable
data class Notification(
    val id: String,
    @SerialName("user_id") val userId: String,
    val type: String, // NEW_FOLLOWER / REACTION / COMMENT
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("feed_event_id") val feedEventId: String? = null,
    @SerialName("comment_id") val commentId: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
internal data class NotificationReadUpdate(
    @SerialName("read_at") val readAt: String
)
