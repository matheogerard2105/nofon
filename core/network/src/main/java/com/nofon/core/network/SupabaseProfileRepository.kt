package com.nofon.core.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class SupabaseProfileRepository @Inject constructor(
    private val client: SupabaseClient
) : ProfileRepository {

    override suspend fun publicProfile(userId: String): Result<PublicProfile?> = runCatching {
        client.from("public_profile_stats").select {
            filter { eq("user_id", userId) }
        }.decodeList<PublicProfile>().firstOrNull()
    }

    override suspend fun updateHandle(userId: String, handle: String): Result<Unit> = runCatching {
        client.from("users").update(UpdateHandleRequest(handle = handle)) {
            filter { eq("id", userId) }
        }
        Unit
    }

    override suspend fun dailyMeasurements(userId: String, sinceLocalDate: String?): Result<List<DailyMeasurementPoint>> =
        runCatching {
            client.from("user_daily_measurements").select {
                filter {
                    eq("user_id", userId)
                    sinceLocalDate?.let { gte("local_date", it) }
                }
                order("local_date", Order.ASCENDING)
            }.decodeList<DailyMeasurementPoint>()
        }

    override suspend fun uploadAvatar(userId: String, context: Context, imageUri: Uri): Result<String> = runCatching {
        val bytes = context.contentResolver.openInputStream(imageUri)?.use { stream ->
            val original = BitmapFactory.decodeStream(stream)
                ?: error("Image illisible.")
            ByteArrayOutputStream().use { out ->
                downscale(centerCropSquare(original), MAX_DIMENSION_PX).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } ?: error("Impossible de lire l'image sélectionnée.")

        val path = "$userId/avatar.jpg"
        client.storage.from(AVATARS_BUCKET).upload(path, bytes) {
            upsert = true
            contentType = ContentType.Image.JPEG
        }
        // Chemin fixe (toujours "{userId}/avatar.jpg", TODO-18/photo modifiable) : sans
        // paramètre distinct à chaque envoi, l'URL ne change jamais d'une photo à
        // l'autre, et Coil (comme tout cache HTTP) continue de servir l'ancienne image
        // en cache — constaté en conditions réelles ("on ne peut plus la changer" après
        // le premier envoi). Le serveur ignore ce paramètre, seul le cache client le voit.
        val url = client.storage.from(AVATARS_BUCKET).publicUrl(path) + "?t=${System.currentTimeMillis()}"
        client.from("users").update(UpdateAvatarRequest(avatarUrl = url)) {
            filter { eq("id", userId) }
        }
        url
    }

    /**
     * Recadrage carré centré (demande explicite : "mettre sous format cadré quand on la
     * met") — fait une fois à l'upload, jamais côté affichage : tous les endroits qui
     * montrent cet avatar (profil, abonnés/abonnements, fil, commentaires) obtiennent
     * ainsi la même image déjà carrée, sans dépendre de `ContentScale.Crop` partout.
     */
    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        val side = kotlin.math.min(bitmap.width, bitmap.height)
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

    private companion object {
        const val AVATARS_BUCKET = "avatars"
        const val MAX_DIMENSION_PX = 512
        const val JPEG_QUALITY = 85
    }
}
