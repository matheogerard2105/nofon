package com.nofon.core.network

/**
 * Fil chronologique (`feed_events`), commentaires et réactions (`0005_social.sql`).
 * Regroupés dans un seul repository — même granularité que `GoalRepository`, qui
 * couvre `goals`/`goal_excluded_apps`/`daily_results`/`daily_stake_shares`/
 * `measurement_batches` : "contenu affiché sur le fil", pas une table à la fois.
 * `feed_events` lui-même est en lecture seule côté client (peuplé par les triggers
 * serveur) ; seuls `comments`/`reactions` sont écrits ici.
 */
interface FeedRepository {
    /** Pagination keyset sur `created_at` — `before` = curseur de la dernière page lue. */
    suspend fun feed(before: String? = null, limit: Int = 20): Result<List<FeedEvent>>

    /** Un seul aller-retour pour toute une page de fil — voir `FeedEventEngagement`. */
    suspend fun engagementFor(feedEventIds: List<String>): Result<List<FeedEventEngagement>>

    suspend fun commentsFor(feedEventId: String): Result<List<Comment>>
    suspend fun postComment(feedEventId: String, authorId: String, body: String): Result<Comment>
    suspend fun deleteComment(commentId: String): Result<Unit>

    suspend fun reactionsFor(feedEventId: String): Result<List<Reaction>>
    suspend fun react(feedEventId: String, userId: String): Result<Unit>
    suspend fun unreact(feedEventId: String, userId: String): Result<Unit>
}
