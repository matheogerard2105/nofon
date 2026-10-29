package com.nofon.core.network

import android.content.Context
import android.net.Uri

/** Profil public agrégé (`public_profile_stats`, `0005_social.sql`). */
interface ProfileRepository {
    suspend fun publicProfile(userId: String): Result<PublicProfile?>

    /**
     * `users.handle` est `unique not null` (`0001_init.sql`) — un handle déjà pris
     * échoue en base (violation de contrainte), jamais silencieusement accepté.
     */
    suspend fun updateHandle(userId: String, handle: String): Result<Unit>

    /**
     * @param sinceLocalDate borne basse incluse (`yyyy-MM-dd`), ou `null` pour tout
     *   l'historique disponible ("depuis la création du compte" — aucune mesure
     *   n'existe avant la création du premier objectif, inutile d'aller chercher
     *   `users.created_at` séparément). Trié par date croissante.
     */
    suspend fun dailyMeasurements(userId: String, sinceLocalDate: String?): Result<List<DailyMeasurementPoint>>

    /**
     * Décode, redimensionne et recompresse `imageUri` en JPEG avant l'upload (jamais le
     * fichier brut choisi par l'utilisateur — évite d'envoyer une photo de plusieurs Mo
     * telle quelle). Écrase toujours "{userId}/avatar.jpg" (bucket `avatars`,
     * `0009_avatars.sql`) plutôt que d'accumuler un fichier par changement de photo.
     * @return l'URL publique du fichier, déjà écrite dans `users.avatar_url`.
     */
    suspend fun uploadAvatar(userId: String, context: Context, imageUri: Uri): Result<String>
}
