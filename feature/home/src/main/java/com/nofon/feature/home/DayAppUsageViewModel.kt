package com.nofon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nofon.core.database.DailyAggregateDao
import com.nofon.core.tracking.TrackingProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AppUsageRowUiState(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long,
    val sessionCount: Int,
    val launchCount: Int
)

data class DayAppUsageUiState(
    val localDate: String,
    val totalMillis: Long?,
    val unlocks: Int?,
    val quality: String?,
    val apps: List<AppUsageRowUiState>
)

/**
 * Détail app par app d'une journée locale — demande explicite (diagnostiquer un écart
 * de temps d'écran). Source PUREMENT locale (`DailyAppUsageEntity`, `TrackingSyncWorker`
 * .aggregateDay/backfillLocalHistoryIfNeeded) : le serveur ne reçoit jamais de détail
 * par application (`measurement_batches` n'a que le total), donc ce détail n'existe que
 * sur l'appareil qui a mesuré cette journée, et seulement dans la fenêtre de rétention
 * locale (30 jours glissants, `TrackingSyncWorker.BACKFILL_DAYS`).
 */
@HiltViewModel
class DayAppUsageViewModel @Inject constructor(
    private val dailyAggregateDao: DailyAggregateDao,
    private val trackingProvider: TrackingProvider
) : ViewModel() {

    private val _state = MutableStateFlow<DayAppUsageUiState?>(null)
    val state: StateFlow<DayAppUsageUiState?> = _state.asStateFlow()

    fun load(localDate: String) {
        viewModelScope.launch {
            combine(
                dailyAggregateDao.observeDay(localDate),
                dailyAggregateDao.observeAppUsageForDay(localDate)
            ) { day, apps ->
                DayAppUsageUiState(
                    localDate = localDate,
                    totalMillis = day?.nofonScreenMillis,
                    unlocks = day?.unlocks,
                    quality = day?.nofonScreenQuality,
                    apps = apps.map { app ->
                        AppUsageRowUiState(
                            packageName = app.packageName,
                            label = trackingProvider.labelFor(app.packageName),
                            foregroundMillis = app.foregroundMillis,
                            sessionCount = app.sessionCount,
                            launchCount = app.launchCount
                        )
                    }
                )
            }.collect { _state.value = it }
        }
    }
}
