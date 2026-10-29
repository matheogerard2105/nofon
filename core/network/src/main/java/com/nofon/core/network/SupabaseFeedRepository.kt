package com.nofon.core.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseFeedRepository @Inject constructor(
    private val client: SupabaseClient
) : FeedRepository {

    override suspend fun feed(before: String?, limit: Int): Result<List<FeedEvent>> = runCatching {
        client.from("feed_events").select {
            filter {
                if (before != null) {
                    lt("created_at", before)
                }
            }
            order("created_at", Order.DESCENDING)
            limit(limit.toLong())
        }.decodeList<FeedEvent>()
    }

    override suspend fun engagementFor(feedEventIds: List<String>): Result<List<FeedEventEngagement>> = runCatching {
        if (feedEventIds.isEmpty()) return@runCatching emptyList()
        client.from("feed_event_engagement_stats").select {
            filter { isIn("feed_event_id", feedEventIds) }
        }.decodeList<FeedEventEngagement>()
    }

    override suspend fun commentsFor(feedEventId: String): Result<List<Comment>> = runCatching {
        client.from("comments").select {
            filter { eq("feed_event_id", feedEventId) }
            order("created_at", Order.ASCENDING)
        }.decodeList<Comment>()
    }

    override suspend fun postComment(feedEventId: String, authorId: String, body: String): Result<Comment> =
        runCatching {
            client.from("comments")
                .insert(NewComment(feedEventId = feedEventId, authorId = authorId, body = body)) { select() }
                .decodeSingle<Comment>()
        }

    override suspend fun deleteComment(commentId: String): Result<Unit> = runCatching {
        client.from("comments").delete {
            filter { eq("id", commentId) }
        }
        Unit
    }

    override suspend fun reactionsFor(feedEventId: String): Result<List<Reaction>> = runCatching {
        client.from("reactions").select {
            filter { eq("feed_event_id", feedEventId) }
        }.decodeList<Reaction>()
    }

    override suspend fun react(feedEventId: String, userId: String): Result<Unit> = runCatching {
        client.from("reactions").insert(NewReaction(feedEventId = feedEventId, userId = userId))
        Unit
    }

    override suspend fun unreact(feedEventId: String, userId: String): Result<Unit> = runCatching {
        client.from("reactions").delete {
            filter {
                eq("feed_event_id", feedEventId)
                eq("user_id", userId)
            }
        }
        Unit
    }
}
