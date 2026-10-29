package com.nofon.core.network

import android.content.Context
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * API Classic (pas Standard) : un seul appel, pas de "warm-up" à préparer à l'avance, et
 * pas de cloud project number à configurer côté client (contrairement à la Standard API)
 * — cohérent avec l'usage capture-only actuel (voir IntegrityAttestationRepository). Ne
 * renverra un verdict exploitable que depuis un build installé via une piste Play
 * Console (test interne minimum) — même limitation documentée pour Google Sign-In
 * (SupabaseAuthRepository) sur ce projet.
 */
@Singleton
class PlayIntegrityAttestationRepository @Inject constructor() : IntegrityAttestationRepository {

    override suspend fun requestAttestationToken(context: Context, requestHash: String): Result<String> =
        runCatching {
            val nonce = Base64.encodeToString(
                requestHash.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE
            )
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
            val response = suspendCancellableCoroutine<IntegrityTokenResponse> { continuation ->
                IntegrityManagerFactory.create(context).requestIntegrityToken(request)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            response.token()
        }
}
