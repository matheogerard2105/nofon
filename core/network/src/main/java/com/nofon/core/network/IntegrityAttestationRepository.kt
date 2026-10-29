package com.nofon.core.network

import android.content.Context

/**
 * TODO-30 (PRODUCT_TODOS-2.md) : capture uniquement pour l'instant — le jeton renvoyé
 * est transmis et stocké tel quel (`measurement_batches.integrity_token`,
 * `0007_hardening.sql`), jamais décodé/vérifié côté serveur. Le décodage réel exige un
 * appel à l'API Google (`playintegrity.googleapis.com`) avec un compte de service, donc
 * une infrastructure hors de Postgres (Edge Function) et des identifiants Google Cloud
 * que l'utilisateur doit provisionner — non construit tant que l'argent reste simulé.
 */
interface IntegrityAttestationRepository {

    /** @param requestHash identifie la requête liée à ce jeton (encodé en nonce) — jamais réutilisé tel quel entre deux appels. */
    suspend fun requestAttestationToken(context: Context, requestHash: String): Result<String>
}
