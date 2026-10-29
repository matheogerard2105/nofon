package com.nofon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.network.DailyResult
import com.nofon.core.network.GoalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Historique d'un objectif payant (PRODUCT_SPEC-2.md §7.4 : "récapitulatif complet,
 * illimité"). Limité aux données déjà calculées par `evaluate_measurement_batch`
 * (`daily_results`) — pas les courbes/heatmap/comparaisons de §7.2, qui demandent une
 * infrastructure absente du projet (catégorisation d'applications notamment, TODO-26).
 */
@HiltViewModel
class GoalHistoryViewModel @Inject constructor(
    private val goalRepository: GoalRepository
) : ViewModel() {

    private val _results = MutableStateFlow<List<DailyResult>>(emptyList())
    val results: StateFlow<List<DailyResult>> = _results.asStateFlow()

    fun load(goalId: String) {
        viewModelScope.launch {
            _results.value = goalRepository.dailyResults(goalId).getOrDefault(emptyList())
                .sortedByDescending { it.localDate }
        }
    }
}
