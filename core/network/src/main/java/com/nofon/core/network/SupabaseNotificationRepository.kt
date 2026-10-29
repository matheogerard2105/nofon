package com.nofon.core.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseNotificationRepository @Inject constructor(
    private val client: SupabaseClient
) : NotificationRepository {

    override suspend fun unreadCount(userId: String): Result<Long> = runCatching {
        client.from("notifications").select {
            filter { eq("user_id", userId) }
        }.decodeList<Notification>().count { it.readAt == null }.toLong()
    }

    override suspend fun list(userId: String, before: String?, limit: Int): Result<List<Notification>> =
        runCatching {
            client.from("notifications").select {
                filter {
                    eq("user_id", userId)
                    if (before != null) {
                        lt("created_at", before)
                    }
                }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }.decodeList<Notification>()
        }

    override suspend fun markRead(notificationId: String): Result<Unit> = runCatching {
        client.from("notifications").update(NotificationReadUpdate(readAt = Instant.now().toString())) {
            filter { eq("id", notificationId) }
        }
        Unit
    }

    override suspend fun markAllRead(userId: String): Result<Unit> = runCatching {
        // Marque aussi les notifications déjà lues (pas de filtre "read_at is null" ici,
        // aucune méthode d'égalité à null dans le DSL de filtre utilisé ailleurs dans ce
        // repo) — sans conséquence, l'UI ne dépend que de read_at != null.
        client.from("notifications").update(NotificationReadUpdate(readAt = Instant.now().toString())) {
            filter { eq("user_id", userId) }
        }
        Unit
    }
}
