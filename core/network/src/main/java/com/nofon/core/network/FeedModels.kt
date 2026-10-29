package com.nofon.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Lu depuis `feed_events` (`0005_social.sql`). Aucune écriture client — peuplée
 * uniquement par `award_exploit()`/`evaluate_measurement_batch()`. `content` est un
 * instantané figé au moment de l'événement (mêmes clés que `jsonb_build_object` côté
 * SQL : `exploit_code`/`exploit_name`/`rarity` pour `EXPLOIT_UNLOCKED`,
 * `kind`/`constraint_type`/`duration_days` pour `GOAL_COMPLETED`), jamais recalculé.
 */
@Serializable
data class FeedEvent(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("event_type") val eventType: String, // GOAL_COMPLETED / EXPLOIT_UNLOCKED
    @SerialName("source_id") val sourceId: String,
    val content: JsonElement,
    @SerialName("created_at") val createdAt: String
)

/** Lu/écrit dans `comments`. Visibilité et suppression : voir RLS de `0005_social.sql`. */
@Serializable
data class Comment(
    val id: String,
    @SerialName("feed_event_id") val feedEventId: String,
    @SerialName("author_id") val authorId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class NewComment(
    @SerialName("feed_event_id") val feedEventId: String,
    @SerialName("author_id") val authorId: String,
    val body: String
)

/** Lu/écrit dans `reactions` — un seul type de réaction (pas de taxonomie, voir SPEC §10). */
@Serializable
data class Reaction(
    @SerialName("feed_event_id") val feedEventId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class NewReaction(
    @SerialName("feed_event_id") val feedEventId: String,
    @SerialName("user_id") val userId: String
)

/**
 * Lu depuis la vue `feed_event_engagement_stats` (`0005_social.sql`) — compteurs +
 * "ai-je réagi" en un seul aller-retour par page de fil, plutôt qu'un appel par item.
 */
@Serializable
data class FeedEventEngagement(
    @SerialName("feed_event_id") val feedEventId: String,
    @SerialName("reaction_count") val reactionCount: Long,
    @SerialName("comment_count") val commentCount: Long,
    @SerialName("did_i_react") val didIReact: Boolean
)
