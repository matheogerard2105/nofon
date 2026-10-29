package com.nofon.core.network

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class SupabaseAuthRepository @Inject constructor(
    private val client: SupabaseClient
) : AuthRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val authState: StateFlow<AuthState> = client.auth.sessionStatus
        .map { status ->
            when (status) {
                is SessionStatus.Authenticated -> AuthState.SignedIn(status.session.user?.id ?: "")
                is SessionStatus.NotAuthenticated -> AuthState.SignedOut
                is SessionStatus.RefreshFailure -> AuthState.Error("Session expirée, reconnecte-toi.")
                SessionStatus.Initializing -> AuthState.Initializing
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, AuthState.Initializing)

    override suspend fun signUpWithEmail(email: String, password: String, birthDate: LocalDate): Result<Unit> =
        runCatching {
            client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                data = buildJsonObject {
                    put("birth_date", birthDate.toString())
                }
            }
            Unit
        }

    override suspend fun signInWithEmail(email: String, password: String): Result<Unit> =
        runCatching {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        }

    override suspend fun signInWithGoogle(context: Context): Result<Unit> = runCatching {
        require(BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            "GOOGLE_WEB_CLIENT_ID absent de local.properties — crée un Client ID OAuth " +
                "(Google Cloud Console) pour com.nofon.app avant de tester cette connexion."
        }

        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(context, request)
        val credential = result.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Identifiant Google inattendu."
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken

        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            this.provider = Google
        }
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }

    override suspend fun requestAccountDeletion(): Result<Unit> = runCatching {
        client.postgrest.rpc("request_account_deletion")
        client.auth.signOut()
    }

    override suspend fun isAccountActive(userId: String): Result<Boolean> = runCatching {
        client.postgrest.rpc("user_is_active", buildJsonObject { put("p_user_id", userId) })
            .decodeAs<Boolean>()
    }
}
