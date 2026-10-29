package com.nofon.core.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

private val ACTIVE_MEMBERSHIP_STATUSES = listOf("PENDING", "ACTIVE", "LEAVING")
private const val AVATARS_BUCKET = "avatars"
private const val AVATAR_MAX_DIMENSION_PX = 512
private const val AVATAR_JPEG_QUALITY = 85

@Singleton
class SupabaseGroupRepository @Inject constructor(
    private val client: SupabaseClient
) : GroupRepository {

    override suspend fun createGroup(creatorId: String, creatorGoalId: String): Result<Group> = runCatching {
        client.from("groups")
            .insert(NewGroupRequest(creatorId = creatorId, creatorGoalId = creatorGoalId)) { select() }
            .decodeSingle<Group>()
    }

    override suspend fun groupById(groupId: String): Result<Group?> = runCatching {
        client.from("groups").select {
            filter { eq("id", groupId) }
        }.decodeList<Group>().firstOrNull()
    }

    override suspend fun groupByCode(code: String): Result<Group?> = runCatching {
        client.from("groups").select {
            filter { eq("code", code) }
        }.decodeList<Group>().firstOrNull()
    }

    override suspend fun myMembership(userId: String): Result<GroupMember?> = runCatching {
        client.from("group_members").select {
            filter {
                eq("user_id", userId)
                isIn("status", ACTIVE_MEMBERSHIP_STATUSES)
            }
        }.decodeList<GroupMember>().firstOrNull()
    }

    override suspend fun requestToJoin(groupId: String, userId: String, goalId: String): Result<Unit> = runCatching {
        client.from("group_members").insert(
            NewGroupMemberRequest(groupId = groupId, userId = userId, goalId = goalId)
        )
        Unit
    }

    override suspend fun pendingRequests(groupId: String): Result<List<GroupMember>> = runCatching {
        client.from("group_members").select {
            filter {
                eq("group_id", groupId)
                eq("status", "PENDING")
            }
        }.decodeList<GroupMember>()
    }

    override suspend fun acceptRequest(groupId: String, userId: String): Result<Unit> = runCatching {
        client.from("group_members").update(AcceptMemberUpdate(status = "ACTIVE")) {
            filter {
                eq("group_id", groupId)
                eq("user_id", userId)
            }
        }
        Unit
    }

    override suspend fun rejectRequest(groupId: String, userId: String): Result<Unit> = runCatching {
        client.from("group_members").delete {
            filter {
                eq("group_id", groupId)
                eq("user_id", userId)
            }
        }
        Unit
    }

    override suspend fun leaveGroup(groupId: String, userId: String): Result<Unit> =
        initiateLeave(groupId, userId, initiatedBy = "SELF")

    override suspend fun removeMember(groupId: String, userId: String): Result<Unit> =
        initiateLeave(groupId, userId, initiatedBy = "CREATOR")

    private suspend fun initiateLeave(groupId: String, userId: String, initiatedBy: String): Result<Unit> =
        runCatching {
            val now = Instant.now()
            client.from("group_members").update(
                LeaveMemberUpdate(
                    status = "LEAVING",
                    leaveInitiatedBy = initiatedBy,
                    leaveRequestedAt = now.toString(),
                    leftEffectiveAt = now.plusSeconds(24 * 3600).toString()
                )
            ) {
                filter {
                    eq("group_id", groupId)
                    eq("user_id", userId)
                }
            }
            Unit
        }

    override suspend fun members(groupId: String): Result<List<GroupMember>> = runCatching {
        client.from("group_members").select {
            filter { eq("group_id", groupId) }
        }.decodeList<GroupMember>()
    }

    override suspend fun dailyResults(groupId: String): Result<List<GroupDailyResult>> = runCatching {
        client.from("group_daily_results").select {
            filter { eq("group_id", groupId) }
        }.decodeList<GroupDailyResult>()
    }

    override suspend fun requestFinalizationIfStale(groupId: String, localDate: String): Result<Unit> = runCatching {
        client.from("group_finalization_requests").insert(
            NewGroupFinalizationRequest(groupId = groupId, localDate = localDate)
        )
        Unit
    }

    override suspend fun renameGroup(groupId: String, name: String): Result<Unit> = runCatching {
        client.from("groups").update(UpdateGroupNameRequest(name = name)) {
            filter { eq("id", groupId) }
        }
        Unit
    }

    override suspend fun uploadGroupAvatar(groupId: String, context: Context, imageUri: Uri): Result<String> =
        runCatching {
            val bytes = context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val original = BitmapFactory.decodeStream(stream) ?: error("Image illisible.")
                ByteArrayOutputStream().use { out ->
                    downscale(centerCropSquare(original), AVATAR_MAX_DIMENSION_PX)
                        .compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
                    out.toByteArray()
                }
            } ?: error("Impossible de lire l'image sélectionnée.")

            val path = "groups/$groupId/avatar.jpg"
            client.storage.from(AVATARS_BUCKET).upload(path, bytes) {
                upsert = true
                contentType = ContentType.Image.JPEG
            }
            // Suffixe cache-busting : même raison que ProfileRepository.uploadAvatar
            // (chemin fixe, sinon Coil continue de servir l'ancienne image en cache).
            val url = client.storage.from(AVATARS_BUCKET).publicUrl(path) + "?t=${System.currentTimeMillis()}"
            client.from("groups").update(UpdateGroupAvatarRequest(avatarUrl = url)) {
                filter { eq("id", groupId) }
            }
            url
        }

    override suspend fun dissolveGroup(groupId: String): Result<Unit> = runCatching {
        val now = Instant.now()
        client.from("groups").update(
            DissolveGroupRequest(
                dissolveRequestedAt = now.toString(),
                dissolveEffectiveAt = now.plusSeconds(24 * 3600).toString()
            )
        ) {
            filter { eq("id", groupId) }
        }
        Unit
    }

    override suspend fun memberGoalSummaries(groupId: String): Result<List<GroupMemberGoalSummary>> = runCatching {
        client.from("group_member_goal_summary").select {
            filter { eq("group_id", groupId) }
        }.decodeList<GroupMemberGoalSummary>()
    }

    override suspend fun memberDailyProgress(groupId: String): Result<List<GroupMemberDailyProgress>> = runCatching {
        client.from("group_member_daily_progress").select {
            filter { eq("group_id", groupId) }
        }.decodeList<GroupMemberDailyProgress>()
    }

    /** Même recadrage carré centré que `SupabaseProfileRepository` — cohérence visuelle avatars profil/groupe. */
    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        val side = min(bitmap.width, bitmap.height)
        if (side == bitmap.width && side == bitmap.height) return bitmap
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, x, y, side, side)
    }

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largestSide = max(bitmap.width, bitmap.height)
        if (largestSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largestSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
