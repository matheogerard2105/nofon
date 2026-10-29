package com.nofon.core.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Colonnes publiques de `users` (TODO-18) — jamais `select()` sans liste explicite sur
 * cette table : `anon`/`authenticated` n'ont qu'un accès colonne par colonne
 * (`0001_init.sql`, `revoke all` + `grant select (...)`), pas table entière. Un
 * `SELECT *` implicite référence aussi `deleted_at`/`birth_date`/`best_streak_days`
 * (colonnes hors grant pour l'un des deux rôles ou les deux) et échoue en base avec
 * `permission denied for table users` — constaté en conditions réelles en testant la
 * recherche (Phase 7).
 */
private val PUBLIC_USER_COLUMNS = Columns.list("id", "handle", "avatar_url")

@Singleton
class SupabaseSocialGraphRepository @Inject constructor(
    private val client: SupabaseClient
) : SocialGraphRepository {

    override suspend fun follow(followerId: String, followeeId: String): Result<Unit> = runCatching {
        client.from("follows").insert(NewFollow(followerId = followerId, followeeId = followeeId))
        Unit
    }

    override suspend fun unfollow(followerId: String, followeeId: String): Result<Unit> = runCatching {
        client.from("follows").delete {
            filter {
                eq("follower_id", followerId)
                eq("followee_id", followeeId)
            }
        }
        Unit
    }

    override suspend fun isFollowing(followerId: String, followeeId: String): Result<Boolean> = runCatching {
        client.from("follows").select {
            filter {
                eq("follower_id", followerId)
                eq("followee_id", followeeId)
            }
        }.decodeList<NewFollow>().isNotEmpty()
    }

    override suspend fun followers(userId: String): Result<List<UserSearchResult>> = runCatching {
        val followerIds = client.from("follows").select {
            filter { eq("followee_id", userId) }
        }.decodeList<NewFollow>().map { it.followerId }
        usersByIdsRaw(followerIds)
    }

    override suspend fun following(userId: String): Result<List<UserSearchResult>> = runCatching {
        val followeeIds = client.from("follows").select {
            filter { eq("follower_id", userId) }
        }.decodeList<NewFollow>().map { it.followeeId }
        usersByIdsRaw(followeeIds)
    }

    override suspend fun block(blockerId: String, blockedId: String): Result<Unit> = runCatching {
        client.from("blocks").insert(NewBlock(blockerId = blockerId, blockedId = blockedId))
        Unit
    }

    override suspend fun unblock(blockerId: String, blockedId: String): Result<Unit> = runCatching {
        client.from("blocks").delete {
            filter {
                eq("blocker_id", blockerId)
                eq("blocked_id", blockedId)
            }
        }
        Unit
    }

    override suspend fun blockedUsers(blockerId: String): Result<List<UserSearchResult>> = runCatching {
        val blockedIds = client.from("blocks").select {
            filter { eq("blocker_id", blockerId) }
        }.decodeList<NewBlock>().map { it.blockedId }
        usersByIdsRaw(blockedIds)
    }

    override suspend fun report(
        reporterId: String,
        targetType: String,
        targetId: String,
        reason: String
    ): Result<Unit> = runCatching {
        client.from("reports").insert(
            NewReport(reporterId = reporterId, targetType = targetType, targetId = targetId, reason = reason)
        )
        Unit
    }

    override suspend fun searchUsers(query: String, excludingBlockedBy: String): Result<List<UserSearchResult>> =
        runCatching {
            val results = client.from("users").select(PUBLIC_USER_COLUMNS) {
                filter { ilike("handle", "%$query%") }
                limit(20)
            }.decodeList<UserSearchResult>()
            val blockedIds = blockedUsers(excludingBlockedBy).getOrDefault(emptyList()).map { it.id }.toSet()
            results.filter { it.id !in blockedIds }
        }

    override suspend fun usersByIds(ids: List<String>): Result<List<UserSearchResult>> = runCatching {
        usersByIdsRaw(ids)
    }

    private suspend fun usersByIdsRaw(ids: List<String>): List<UserSearchResult> {
        if (ids.isEmpty()) return emptyList()
        return client.from("users").select(PUBLIC_USER_COLUMNS) {
            filter { isIn("id", ids) }
        }.decodeList<UserSearchResult>()
    }
}
