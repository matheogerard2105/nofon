package com.nofon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.Goal
import com.nofon.core.network.GoalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * TODO-32 (PRODUCT_TODOS-2.md) : "historique des objectifs" ouvert depuis le
 * pourcentage de réussite du profil — self uniquement (RLS `goals_select_self`,
 * `0002_goals.sql` : aucune manière de lister les objectifs d'un tiers).
 */
@HiltViewModel
class GoalsListViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val goalRepository: GoalRepository
) : ViewModel() {

    private val _goals = MutableStateFlow<List<Goal>>(emptyList())
    val goals: StateFlow<List<Goal>> = _goals.asStateFlow()

    fun load() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        viewModelScope.launch {
            _goals.value = goalRepository.allGoals(userId).getOrDefault(emptyList())
        }
    }
}
