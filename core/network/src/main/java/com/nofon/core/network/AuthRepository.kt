package com.nofon.core.network

import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

/**
 * Point d'entrée unique pour l'authentification. L'appelant (`:feature:auth`) ne
 * connaît jamais Supabase directement, seulement cette interface.
 */
interface AuthRepository {

    val authState: StateFlow<AuthState>

    /**
     * @param birthDate déjà validée côté appelant (18 ans minimum, PRODUCT_SPEC §1,
     *   TODO-15) — cette méthode ne revalide pas la règle, elle transmet la donnée.
     *   La contrainte est aussi imposée en base (CHECK SQL), jamais uniquement côté client.
     */
    suspend fun signUpWithEmail(email: String, password: String, birthDate: LocalDate): Result<Unit>

    suspend fun signInWithEmail(email: String, password: String): Result<Unit>

    /**
     * Récupère un identifiant Google via Credential Manager puis authentifie
     * l'utilisateur auprès de Supabase. Nécessite `GOOGLE_WEB_CLIENT_ID` configuré
     * (local.properties) — échoue proprement (Result.failure) tant qu'il est vide.
     */
    suspend fun signInWithGoogle(context: Context): Result<Unit>

    suspend fun signOut()

    /**
     * TODO-29 (PRODUCT_TODOS-2.md) : soft-delete (`users.deleted_at`) via RPC serveur,
     * puis déconnexion locale immédiate en cas de succès. La garde anti-perte-de-données
     * (créateur d'un groupe actif) est imposée côté serveur, pas ici — un échec
     * `account_deletion_blocked_active_group_creator` remonte via Result.failure.
     */
    suspend fun requestAccountDeletion(): Result<Unit>

    /**
     * TODO-35 (PRODUCT_TODOS-2.md) : la suppression de compte (TODO-29) est un
     * soft-delete — `auth.users` (donc les identifiants) reste valide jusqu'à
     * l'effacement dur différé de 30 jours. Rien n'empêchait jusqu'ici de se
     * reconnecter à un compte déjà marqué supprimé pendant ce délai. Appelée après
     * toute transition vers `AuthState.SignedIn` (connexion fraîche ou session
     * restaurée) — `false` doit entraîner une déconnexion immédiate côté appelant.
     */
    suspend fun isAccountActive(userId: String): Result<Boolean>
}
