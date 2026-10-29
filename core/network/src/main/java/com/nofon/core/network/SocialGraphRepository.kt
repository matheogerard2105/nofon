package com.nofon.core.network

/**
 * Follow, blocage, signalement, recherche (`follows`/`blocks`/`reports`/`users`,
 * `0005_social.sql`) — regroupés comme `GoalRepository` regroupe plusieurs tables
 * autour d'un même domaine ("qui est connecté à qui"), pas une table = un repository.
 */
interface SocialGraphRepository {
    suspend fun follow(followerId: String, followeeId: String): Result<Unit>
    suspend fun unfollow(followerId: String, followeeId: String): Result<Unit>
    suspend fun isFollowing(followerId: String, followeeId: String): Result<Boolean>
    suspend fun followers(userId: String): Result<List<UserSearchResult>>
    suspend fun following(userId: String): Result<List<UserSearchResult>>

    suspend fun block(blockerId: String, blockedId: String): Result<Unit>
    suspend fun unblock(blockerId: String, blockedId: String): Result<Unit>
    suspend fun blockedUsers(blockerId: String): Result<List<UserSearchResult>>

    suspend fun report(reporterId: String, targetType: String, targetId: String, reason: String): Result<Unit>

    /**
     * Filtre `ilike` sur `handle`. Exclut les utilisateurs que l'appelant a bloqués
     * (RLS ne permet de lire que `blocks` où `blocker_id = soi` — impossible de savoir
     * côté client qui NOUS a bloqué ; ce cas reste couvert côté serveur pour follow/fil,
     * voir `0005_social.sql`, pas ici).
     */
    suspend fun searchUsers(query: String, excludingBlockedBy: String): Result<List<UserSearchResult>>

    /** Colonnes publiques (§10.1) pour un lot d'ids connus — ex. les membres d'un groupe. */
    suspend fun usersByIds(ids: List<String>): Result<List<UserSearchResult>>
}
