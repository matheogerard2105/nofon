package com.nofon.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Lu depuis `groups`. `code`/`timezone`/`end_date` calculés serveur (`create_group()`), jamais fournis par le client. */
@Serializable
data class Group(
    val id: String,
    @SerialName("creator_id") val creatorId: String,
    @SerialName("creator_goal_id") val creatorGoalId: String,
    val code: String,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val timezone: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("dissolve_requested_at") val dissolveRequestedAt: String? = null,
    @SerialName("dissolve_effective_at") val dissolveEffectiveAt: String? = null,
    @SerialName("created_at") val createdAt: String
)

/** Payload d'insertion — `code`/`timezone`/`end_date` sont dérivés par `create_group()` (`0006_groups.sql`). */
@Serializable
data class NewGroupRequest(
    @SerialName("creator_id") val creatorId: String,
    @SerialName("creator_goal_id") val creatorGoalId: String
)

/** Lu depuis `group_members`. Voir `ARCHITECTURE-2.md` §6 pour les valeurs de `status`. */
@Serializable
data class GroupMember(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("goal_id") val goalId: String,
    val status: String, // PENDING / ACTIVE / LEAVING / LEFT / REMOVED
    @SerialName("leave_initiated_by") val leaveInitiatedBy: String? = null, // SELF / CREATOR / NATURAL_END
    @SerialName("leave_requested_at") val leaveRequestedAt: String? = null,
    @SerialName("left_effective_at") val leftEffectiveAt: String? = null,
    @SerialName("created_at") val createdAt: String
)

/** Demande d'adhésion (`status='PENDING'` par défaut côté base) — les 3 conditions de SPEC §12.5 sont vérifiées par la RLS. */
@Serializable
data class NewGroupMemberRequest(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("goal_id") val goalId: String
)

/**
 * Payloads d'UPDATE minimaux — un par transition, pour ne jamais écraser un champ non
 * concerné (l'API envoie l'objet entier). Jamais de valeur par défaut sur `status` :
 * kotlinx.serialization omet par défaut les champs qui valent leur valeur par défaut
 * (`encodeDefaults = false`), ce qui enverrait un corps JSON vide et ne modifierait
 * rien — constaté en conditions réelles (bouton "Accepter" silencieusement sans effet).
 */
@Serializable
internal data class AcceptMemberUpdate(val status: String)

@Serializable
internal data class LeaveMemberUpdate(
    val status: String,
    @SerialName("leave_initiated_by") val leaveInitiatedBy: String, // SELF / CREATOR
    @SerialName("leave_requested_at") val leaveRequestedAt: String,
    @SerialName("left_effective_at") val leftEffectiveAt: String
)

/** Lu depuis `group_daily_results` — une ligne n'existe que pour un jour résolu (voir `0006_groups.sql`). */
@Serializable
data class GroupDailyResult(
    @SerialName("group_id") val groupId: String,
    @SerialName("local_date") val localDate: String,
    val status: String, // SUCCESS / FAILED
    @SerialName("resolved_at") val resolvedAt: String
)

@Serializable
data class NewGroupFinalizationRequest(
    @SerialName("group_id") val groupId: String,
    @SerialName("local_date") val localDate: String
)

@Serializable
internal data class UpdateGroupNameRequest(val name: String)

@Serializable
internal data class UpdateGroupAvatarRequest(@SerialName("avatar_url") val avatarUrl: String)

@Serializable
internal data class DissolveGroupRequest(
    @SerialName("dissolve_requested_at") val dissolveRequestedAt: String,
    @SerialName("dissolve_effective_at") val dissolveEffectiveAt: String
)

/** Lu depuis la vue `group_member_goal_summary` (0016_group_redesign.sql) — jamais le montant engagé. */
@Serializable
data class GroupMemberGoalSummary(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("constraint_type") val constraintType: String,
    @SerialName("time_limit_seconds") val timeLimitSeconds: Int? = null,
    @SerialName("opens_limit") val opensLimit: Int? = null
)

/** Lu depuis la vue `group_member_daily_progress` (0016_group_redesign.sql). */
@Serializable
data class GroupMemberDailyProgress(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("local_date") val localDate: String? = null,
    val status: String? = null,
    @SerialName("measured_time_seconds") val measuredTimeSeconds: Int? = null,
    @SerialName("measured_opens") val measuredOpens: Int? = null,
    @SerialName("constraint_type") val constraintType: String,
    @SerialName("time_limit_seconds") val timeLimitSeconds: Int? = null,
    @SerialName("opens_limit") val opensLimit: Int? = null
)
