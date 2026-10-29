package com.nofon.core.network

/** Notifications internes (`notifications`, `0005_social.sql`) — jamais de push (SPEC §11). */
interface NotificationRepository {
    suspend fun unreadCount(userId: String): Result<Long>
    suspend fun list(userId: String, before: String? = null, limit: Int = 20): Result<List<Notification>>
    suspend fun markRead(notificationId: String): Result<Unit>
    suspend fun markAllRead(userId: String): Result<Unit>
}
