package com.nofon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.database.AppConfigCacheEntity
import com.nofon.core.database.AppConfigDao
import com.nofon.core.network.AppConfigRepository
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.GoalRepository
import com.nofon.core.network.NewGoalRequest
import com.nofon.core.tracking.TrackingProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class GoalKind { FREE, STAKED }
enum class ConstraintKind { TIME, OPENS, BOTH }

/**
 * `stakeEuros`/`timeLimitMinutes` sont des unités d'affichage — la conversion vers les
 * unités serveur (centimes, secondes) n'a lieu qu'à l'envoi (`submit`), jamais stockée
 * en double pour éviter un désaccord entre les deux représentations.
 */
data class GoalCreationFormState(
    val kind: GoalKind = GoalKind.FREE,
    val durationDays: Int = 30,
    val constraintType: ConstraintKind = ConstraintKind.TIME,
    val timeLimitMinutes: Int = 60,
    val opensLimit: Int = 30,
    val excludedPackages: Set<String> = emptySet(),
    val stakeEuros: Int = 0,
    val maxDailyStakeCents: Long = 500,
    val maxTotalStakeCents: Long = 50_000,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val createdGoalId: String? = null
) {
    val totalStakeCents: Long get() = stakeEuros * 100L
    val dailyStakeCents: Long get() = if (durationDays > 0) totalStakeCents / durationDays else 0
    /** PRODUCT_SPEC-2.md §5.1 : caution minimale de 1 €/jour. */
    val minStakeEuros: Int get() = durationDays
    val belowMinimumStake: Boolean get() = kind == GoalKind.STAKED && stakeEuros < minStakeEuros
    val exceedsDailyCap: Boolean get() = kind == GoalKind.STAKED && dailyStakeCents > maxDailyStakeCents
    val exceedsTotalCap: Boolean get() = kind == GoalKind.STAKED && totalStakeCents > maxTotalStakeCents

    val isValid: Boolean
        get() = durationDays in 3..365 &&
            (constraintType == ConstraintKind.OPENS || timeLimitMinutes * 60 in 900..18000) &&
            (constraintType == ConstraintKind.TIME || opensLimit in 5..100) &&
            (kind == GoalKind.FREE || (!belowMinimumStake && !exceedsDailyCap && !exceedsTotalCap))
}

@HiltViewModel
class GoalCreationViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val goalRepository: GoalRepository,
    private val appConfigRepository: AppConfigRepository,
    private val appConfigDao: AppConfigDao,
    private val trackingProvider: TrackingProvider
) : ViewModel() {

    private val _state = MutableStateFlow(GoalCreationFormState())
    val state: StateFlow<GoalCreationFormState> = _state.asStateFlow()

    init {
        refreshAppConfig()
    }

    fun availableApps(): List<TrackingProvider.InstalledApp> = trackingProvider.launchableApps()

    private fun refreshAppConfig() {
        viewModelScope.launch {
            appConfigRepository.fetchAll().onSuccess { entries ->
                appConfigDao.upsertAll(
                    entries.map { AppConfigCacheEntity(it.key, it.value.toString(), System.currentTimeMillis()) }
                )
                val daily = entries.find { it.key == "max_daily_stake_cents" }?.value?.jsonPrimitive?.longOrNull
                val total = entries.find { it.key == "max_total_stake_cents" }?.value?.jsonPrimitive?.longOrNull
                _state.value = _state.value.copy(
                    maxDailyStakeCents = daily ?: _state.value.maxDailyStakeCents,
                    maxTotalStakeCents = total ?: _state.value.maxTotalStakeCents
                )
            }
        }
    }

    fun updateKind(kind: GoalKind) { _state.value = _state.value.copy(kind = kind) }
    fun updateDuration(days: Int) { _state.value = _state.value.copy(durationDays = days) }
    fun updateConstraintType(type: ConstraintKind) { _state.value = _state.value.copy(constraintType = type) }
    fun updateTimeLimitMinutes(minutes: Int) { _state.value = _state.value.copy(timeLimitMinutes = minutes) }
    fun updateOpensLimit(count: Int) { _state.value = _state.value.copy(opensLimit = count) }
    fun updateExcludedPackages(packages: Set<String>) { _state.value = _state.value.copy(excludedPackages = packages) }
    fun updateStakeEuros(euros: Int) { _state.value = _state.value.copy(stakeEuros = euros) }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun submit() {
        val form = _state.value
        if (!form.isValid || form.isSubmitting) return
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return

        _state.value = form.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val startDate = LocalDate.now(zone)
            val endDate = startDate.plusDays(form.durationDays.toLong())

            val request = NewGoalRequest(
                userId = userId,
                kind = form.kind.name,
                durationDays = form.durationDays,
                startDate = startDate.toString(),
                endDate = endDate.toString(),
                constraintType = form.constraintType.name,
                timeLimitSeconds = if (form.constraintType != ConstraintKind.OPENS) form.timeLimitMinutes * 60 else null,
                opensLimit = if (form.constraintType != ConstraintKind.TIME) form.opensLimit else null,
                timezone = zone.id,
                maxCommittedCents = if (form.kind == GoalKind.STAKED) form.totalStakeCents else 0
            )

            goalRepository.createGoal(request, form.excludedPackages.toList())
                .onSuccess { goal ->
                    _state.value = _state.value.copy(isSubmitting = false, createdGoalId = goal.id)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(isSubmitting = false, error = e.message ?: "Échec de la création.")
                }
        }
    }

    fun consumeCreatedGoal() { _state.value = _state.value.copy(createdGoalId = null) }
}
