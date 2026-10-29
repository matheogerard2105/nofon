package com.nofon.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.database.DailyAggregateDao
import com.nofon.core.database.DailyAggregateEntity
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.DailyResult
import com.nofon.core.network.Debt
import com.nofon.core.network.DebtRepository
import com.nofon.core.network.Goal
import com.nofon.core.network.GoalRepository
import com.nofon.core.network.LedgerEntry
import com.nofon.core.tracking.TrackingProvider
import com.nofon.core.tracking.work.TrackingScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dérivé de `goals`/`daily_stake_shares`/`daily_results` (serveur, seule source
 * décisionnaire — ARCHITECTURE-2.md §3), pas d'un flux réactif Room : rafraîchi
 * explicitement ([HomeViewModel.refreshActiveGoal]), pas observé en continu.
 */
data class GoalDashboardUiState(
    val goal: Goal,
    val dayIndex: Int,
    val securedCents: Long,
    val atStakeCents: Long,
    val forfeitedCents: Long,
    val todayStatus: String,
    /** 7 derniers jours calendaires (fuseau de l'objectif) jusqu'à aujourd'hui inclus, ordre chronologique — bande "7 derniers jours" de l'Accueil (design 1b). */
    val recentDays: List<DailyResult>
)

/**
 * Objectif gratuit affiché dans "Autres objectifs" (Accueil). `daily_results` alimenté
 * automatiquement depuis la résolution de TODO-24 (`PRODUCT_TODOS-2.md`) : le worker
 * boucle désormais aussi sur les objectifs gratuits actifs, pas seulement le payant.
 */
data class FreeGoalUiState(
    val goal: Goal,
    val dayIndex: Int,
    /** 3 derniers jours calendaires (fuseau de l'objectif) jusqu'à aujourd'hui inclus, ordre chronologique. */
    val recentDays: List<DailyResult>,
    /** Sur les jours écoulés (pas la durée totale) ; `null` si aucun jour écoulé pour l'instant. */
    val successRatePercent: Int?
)

/** Récapitulatif de fin d'objectif (PRODUCT_SPEC-2.md §13), affiché une seule fois. */
data class GoalRecapUiState(
    val goal: Goal,
    val successDays: Int,
    val failedDays: Int,
    val noDataDays: Int,
    val successRatePercent: Int,
    val bestStreakDays: Int,
    val securedCents: Long,
    val debt: Debt?,
    val settlementEntry: LedgerEntry?
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val trackingProvider: TrackingProvider,
    dailyAggregateDao: DailyAggregateDao,
    private val scheduler: TrackingScheduler,
    private val authRepository: AuthRepository,
    private val goalRepository: GoalRepository,
    private val debtRepository: DebtRepository,
    private val recapStore: GoalRecapAcknowledgementStore
) : ViewModel() {

    private val _permissionGranted = MutableStateFlow(trackingProvider.hasUsageAccess())
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    val today: StateFlow<DailyAggregateEntity?> = dailyAggregateDao
        .observeDay(LocalDate.now().toString())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _activeGoal = MutableStateFlow<GoalDashboardUiState?>(null)
    val activeGoal: StateFlow<GoalDashboardUiState?> = _activeGoal.asStateFlow()

    private val _freeGoals = MutableStateFlow<List<FreeGoalUiState>>(emptyList())
    val freeGoals: StateFlow<List<FreeGoalUiState>> = _freeGoals.asStateFlow()

    private val _loadingGoal = MutableStateFlow(false)
    val loadingGoal: StateFlow<Boolean> = _loadingGoal.asStateFlow()

    private val _completedGoalRecap = MutableStateFlow<GoalRecapUiState?>(null)
    val completedGoalRecap: StateFlow<GoalRecapUiState?> = _completedGoalRecap.asStateFlow()

    /** Bannière persistante, distincte du récapitulatif ponctuel — informative uniquement (TODO-13). */
    private val _unpaidDebtBanner = MutableStateFlow<Debt?>(null)
    val unpaidDebtBanner: StateFlow<Debt?> = _unpaidDebtBanner.asStateFlow()

    init {
        refreshActiveGoal()
    }

    /** Rappelé au retour des réglages système (`onResume`) : la permission a pu changer. */
    fun refreshPermission() {
        val granted = trackingProvider.hasUsageAccess()
        _permissionGranted.value = granted
        if (granted) scheduler.syncNow()
    }

    fun openUsageAccessSettings(context: Context) {
        context.startActivity(trackingProvider.usageAccessSettingsIntent())
    }

    fun syncNow() {
        scheduler.syncNow()
        refreshActiveGoal()
    }

    fun refreshActiveGoal() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId
        if (userId == null) {
            _activeGoal.value = null
            _freeGoals.value = emptyList()
            _completedGoalRecap.value = null
            _unpaidDebtBanner.value = null
            return
        }
        viewModelScope.launch { refreshFreeGoals(userId) }
        viewModelScope.launch { refreshCurrentActiveGoal(userId) }
        viewModelScope.launch { refreshCompletedGoalRecap(userId) }
    }

    private suspend fun refreshCurrentActiveGoal(userId: String) {
        _loadingGoal.value = true
        val goal = goalRepository.activeStakedGoal(userId).getOrNull()
        if (goal == null) {
            _activeGoal.value = null
            _loadingGoal.value = false
            return
        }

        val shares = goalRepository.stakeShares(goal.id).getOrDefault(emptyList())
        val results = goalRepository.dailyResults(goal.id).getOrDefault(emptyList())
        val goalZone = runCatching { ZoneId.of(goal.timezone) }.getOrDefault(ZoneId.systemDefault())
        val today = LocalDate.now(goalZone)
        val dayIndex = (ChronoUnit.DAYS.between(LocalDate.parse(goal.startDate), today) + 1)
            .toInt()
            .coerceIn(1, goal.durationDays)

        val resultsByDate = results.associateBy { it.localDate }
        val recentDays = (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            resultsByDate[date] ?: DailyResult(goalId = goal.id, localDate = date, status = "")
        }

        _activeGoal.value = GoalDashboardUiState(
            goal = goal,
            dayIndex = dayIndex,
            securedCents = shares.filter { it.outcome == "SECURED" }.sumOf { it.amountCents },
            atStakeCents = shares.filter { it.outcome == "PENDING" }.sumOf { it.amountCents },
            forfeitedCents = shares.filter { it.outcome == "FORFEITED" }.sumOf { it.amountCents },
            todayStatus = results.find { it.localDate == today.toString() }?.status ?: "PENDING",
            recentDays = recentDays
        )
        _loadingGoal.value = false
    }

    private suspend fun refreshFreeGoals(userId: String) {
        val goals = goalRepository.activeFreeGoals(userId).getOrDefault(emptyList())
        _freeGoals.value = goals.map { goal ->
            val results = goalRepository.dailyResults(goal.id).getOrDefault(emptyList())
            val goalZone = runCatching { ZoneId.of(goal.timezone) }.getOrDefault(ZoneId.systemDefault())
            val today = LocalDate.now(goalZone)
            val dayIndex = (ChronoUnit.DAYS.between(LocalDate.parse(goal.startDate), today) + 1)
                .toInt()
                .coerceIn(1, goal.durationDays)

            val resultsByDate = results.associateBy { it.localDate }
            val recentDays = (2 downTo 0).map { offset ->
                val date = today.minusDays(offset.toLong()).toString()
                resultsByDate[date] ?: DailyResult(goalId = goal.id, localDate = date, status = "")
            }

            val elapsedDates = (0 until dayIndex).map { LocalDate.parse(goal.startDate).plusDays(it.toLong()).toString() }
            val elapsedResults = elapsedDates.mapNotNull { resultsByDate[it] }
            val successRatePercent = if (elapsedResults.isEmpty()) {
                null
            } else {
                (elapsedResults.count { it.status == "SUCCESS" } * 100) / elapsedResults.size
            }

            FreeGoalUiState(goal = goal, dayIndex = dayIndex, recentDays = recentDays, successRatePercent = successRatePercent)
        }
    }

    private suspend fun refreshCompletedGoalRecap(userId: String) {
        val goal = goalRepository.latestStakedGoal(userId).getOrNull()
        if (goal == null || goal.status != "COMPLETED") {
            _completedGoalRecap.value = null
            _unpaidDebtBanner.value = null
            return
        }

        val debt = debtRepository.debtForGoal(goal.id).getOrNull()
        _unpaidDebtBanner.value = debt?.takeIf { it.status == "UNPAID_DEBT" }

        if (recapStore.isAcknowledged(goal.id)) {
            _completedGoalRecap.value = null
            return
        }

        val results = goalRepository.dailyResults(goal.id).getOrDefault(emptyList())
        val shares = goalRepository.stakeShares(goal.id).getOrDefault(emptyList())
        val ledger = debtRepository.ledgerEntriesForGoal(goal.id).getOrDefault(emptyList())

        _completedGoalRecap.value = GoalRecapUiState(
            goal = goal,
            successDays = results.count { it.status == "SUCCESS" },
            failedDays = results.count { it.status == "FAILED" },
            noDataDays = results.count { it.status == "NO_DATA" },
            successRatePercent = if (goal.durationDays > 0) {
                (results.count { it.status == "SUCCESS" } * 100) / goal.durationDays
            } else 0,
            bestStreakDays = goal.bestStreakDays,
            securedCents = shares.filter { it.outcome == "SECURED" }.sumOf { it.amountCents },
            debt = debt,
            settlementEntry = ledger.find { it.entryType == "SETTLEMENT" }
        )
    }

    fun acknowledgeRecap(goalId: String) {
        viewModelScope.launch {
            recapStore.acknowledge(goalId)
            _completedGoalRecap.value = null
        }
    }
}
