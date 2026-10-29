package com.nofon.feature.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun signIn(email: String, password: String) {
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            val result = authRepository.signInWithEmail(email, password)
            _uiState.value = result.fold(
                onSuccess = { AuthUiState(loading = false) },
                onFailure = { AuthUiState(loading = false, error = it.message ?: "Échec de la connexion.") }
            )
        }
    }

    fun signUp(email: String, password: String, birthDate: LocalDate) {
        if (!isAtLeast18(birthDate)) {
            _uiState.value = AuthUiState(error = "Nofon est réservé aux 18 ans et plus.")
            return
        }
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            val result = authRepository.signUpWithEmail(email, password, birthDate)
            _uiState.value = result.fold(
                onSuccess = { AuthUiState(loading = false) },
                onFailure = { AuthUiState(loading = false, error = it.message ?: "Échec de l'inscription.") }
            )
        }
    }

    fun signInWithGoogle(context: Context) {
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            val result = authRepository.signInWithGoogle(context)
            _uiState.value = result.fold(
                onSuccess = { AuthUiState(loading = false) },
                onFailure = { AuthUiState(loading = false, error = it.message ?: "Échec de la connexion Google.") }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _uiState.value = AuthUiState()
        }
    }

    private val _accountDeletionError = MutableStateFlow<String?>(null)
    val accountDeletionError: StateFlow<String?> = _accountDeletionError.asStateFlow()

    /** Déconnexion faite par le repository lui-même en cas de succès — voir AuthRepository. */
    fun deleteAccount() {
        viewModelScope.launch {
            authRepository.requestAccountDeletion().onFailure {
                _accountDeletionError.value = if (it.message?.contains("account_deletion_blocked_active_group_creator") == true) {
                    "Tu es créateur d'un groupe actif : quitte-le ou fais-le partir à son terme avant de supprimer ton compte."
                } else {
                    it.message ?: "La suppression a échoué, réessaie plus tard."
                }
            }
        }
    }

    fun clearAccountDeletionError() {
        _accountDeletionError.value = null
    }

    /**
     * TODO-35 (PRODUCT_TODOS-2.md) : appelée par NofonNavHost à chaque transition vers
     * `AuthState.SignedIn` (connexion fraîche ou session restaurée au lancement).
     * Échec réseau de la vérification elle-même => on laisse passer (pas de verrouillage
     * pour un problème d'infra) ; seul un `false` explicite déclenche la déconnexion.
     */
    fun verifyAccountStillActiveOrSignOut(userId: String) {
        viewModelScope.launch {
            if (authRepository.isAccountActive(userId).getOrDefault(true).not()) {
                authRepository.signOut()
                _uiState.value = AuthUiState(error = "Ce compte a été supprimé.")
            }
        }
    }
}
