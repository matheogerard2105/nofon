package com.nofon.core.network

/**
 * État d'authentification exposé par `AuthRepository`, découplé du type `SessionStatus`
 * de Supabase : le reste de l'app ne dépend jamais directement du SDK Supabase.
 */
sealed interface AuthState {
    data object Initializing : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String) : AuthState
    data class Error(val message: String) : AuthState
}
