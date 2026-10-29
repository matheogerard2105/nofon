package com.nofon.core.network

import android.content.Context
import android.net.Uri

/**
 * Groupes (`groups`/`group_members`/`group_daily_results`, `0006_groups.sql`) —
 * regroupés dans un seul repository, même granularité que `GoalRepository`/
 * `FeedRepository`. La résolution de `group_success` est entièrement côté serveur
 * (déclenchée par `evaluate_measurement_batch`/`resolve_group_day`) — ce repository
 * ne fait jamais de calcul métier, seulement lire/écrire les tables.
 */
interface GroupRepository {
    suspend fun createGroup(creatorId: String, creatorGoalId: String): Result<Group>
    suspend fun groupById(groupId: String): Result<Group?>
    suspend fun groupByCode(code: String): Result<Group?>

    /** Ligne PENDING/ACTIVE/LEAVING de l'utilisateur, s'il en a une (SPEC §12.1 : un seul groupe actif à la fois). */
    suspend fun myMembership(userId: String): Result<GroupMember?>

    suspend fun requestToJoin(groupId: String, userId: String, goalId: String): Result<Unit>
    suspend fun pendingRequests(groupId: String): Result<List<GroupMember>>
    suspend fun acceptRequest(groupId: String, userId: String): Result<Unit>
    suspend fun rejectRequest(groupId: String, userId: String): Result<Unit>
    suspend fun leaveGroup(groupId: String, userId: String): Result<Unit>
    suspend fun removeMember(groupId: String, userId: String): Result<Unit>

    suspend fun members(groupId: String): Result<List<GroupMember>>
    suspend fun dailyResults(groupId: String): Result<List<GroupDailyResult>>

    /** Filet de sécurité TODO-22 — voir `TrackingSyncWorker`, même pattern que le règlement de dette. */
    suspend fun requestFinalizationIfStale(groupId: String, localDate: String): Result<Unit>

    /** Créateur uniquement — appliqué en RLS (`groups_update_creator`), pas seulement côté client. */
    suspend fun renameGroup(groupId: String, name: String): Result<Unit>

    /** Même traitement (carré, downscale) que `ProfileRepository.uploadAvatar` — créateur uniquement. */
    suspend fun uploadGroupAvatar(groupId: String, context: Context, imageUri: Uri): Result<String>

    /**
     * Créateur uniquement — même mécanique à délai de 24h que `leaveGroup`/`removeMember`
     * (0006_groups.sql), calculée ici plutôt que côté serveur (ARCHITECTURE-2.md §3 :
     * seule une DÉCISION financière ne doit jamais dépendre de l'horloge du client ; la
     * dissolution n'en est pas une — confirmé explicitement, aucun effet sur les
     * objectifs individuels).
     */
    suspend fun dissolveGroup(groupId: String): Result<Unit>

    /** Règle (jamais la progression) de l'objectif de chaque membre — vue `group_member_goal_summary`. */
    suspend fun memberGoalSummaries(groupId: String): Result<List<GroupMemberGoalSummary>>

    /** Progression quotidienne de chaque membre depuis la création du groupe — vue `group_member_daily_progress`. */
    suspend fun memberDailyProgress(groupId: String): Result<List<GroupMemberDailyProgress>>
}
