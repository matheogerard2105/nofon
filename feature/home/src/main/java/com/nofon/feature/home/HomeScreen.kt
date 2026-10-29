package com.nofon.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.AlertBanner
import com.nofon.core.designsystem.DayState
import com.nofon.core.designsystem.DayStateChip
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonPrimaryButton
import com.nofon.core.designsystem.NofonSecondaryButton
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.SectionLabel
import com.nofon.core.designsystem.dayStateColor
import com.nofon.core.designsystem.dayStateFor
import com.nofon.core.network.DailyResult
import com.nofon.core.network.Goal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun HomeScreen(
    onOpenHistory: (String) -> Unit = {},
    onOpenAppUsage: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    var showCreation by remember { mutableStateOf(false) }

    if (showCreation) {
        GoalCreationScreen(
            onDone = {
                showCreation = false
                viewModel.refreshActiveGoal()
            }
        )
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = NofonTheme.colors
    val permissionGranted by viewModel.permissionGranted.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val activeGoal by viewModel.activeGoal.collectAsStateWithLifecycle()
    val freeGoals by viewModel.freeGoals.collectAsStateWithLifecycle()
    val completedGoalRecap by viewModel.completedGoalRecap.collectAsStateWithLifecycle()
    val unpaidDebtBanner by viewModel.unpaidDebtBanner.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermission()
                viewModel.refreshActiveGoal()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val debtBanner = unpaidDebtBanner
        if (debtBanner != null) {
            AlertBanner(
                title = "Le prélèvement final n'a pas abouti",
                subtitle = "Montant dû : ${fmtCents(debtBanner.amountCents)} — aucune action requise, paiements simulés uniquement."
            )
        }

        if (!permissionGranted) {
            AlertBanner(
                title = "La mesure d'usage est désactivée.",
                subtitle = "Les journées passeront en « donnée indisponible ».",
                actionLabel = "Activer l'accès",
                onAction = { viewModel.openUsageAccessSettings(context) }
            )
        }

        val goal = activeGoal
        if (goal != null) {
            DonutGoalCard(
                state = goal,
                todayScreenMillis = today?.nofonScreenMillis,
                todayUnlocks = today?.unlocks,
                onOpenHistory = { onOpenHistory(goal.goal.id) },
                onOpenAppDetail = { onOpenAppUsage(LocalDate.now().toString()) },
                onSync = viewModel::syncNow
            )
        } else {
            NofonCard {
                Text("Aucun objectif payant en cours", style = NofonTextStyles.heading, color = colors.onSurface)
                Text(
                    "Un objectif payant engage une caution répartie sur sa durée, sécurisée jour après jour.",
                    style = NofonTextStyles.body,
                    color = colors.onSurfaceVariant
                )
                NofonPrimaryButton("Créer un objectif", onClick = { showCreation = true })
            }
        }

        if (goal != null) {
            NofonSecondaryButton("Créer un autre objectif", onClick = { showCreation = true })
        }

        if (freeGoals.isNotEmpty()) {
            FreeGoalsSection(freeGoals)
        }

        EndOfListNote(if (goal != null) "C'est tout. Rendez-vous demain." else "Fin de l'écran.")
    }

    val recap = completedGoalRecap
    if (recap != null) {
        GoalRecapDialog(state = recap, onAcknowledge = { viewModel.acknowledgeRecap(recap.goal.id) })
    }
}

@Composable
private fun DonutGoalCard(
    state: GoalDashboardUiState,
    todayScreenMillis: Long?,
    todayUnlocks: Int?,
    onOpenHistory: () -> Unit,
    onOpenAppDetail: () -> Unit,
    onSync: () -> Unit
) {
    val goal = state.goal
    val colors = NofonTheme.colors
    val total = (state.securedCents + state.atStakeCents + state.forfeitedCents).coerceAtLeast(1)
    val recoveredFraction = state.securedCents.toFloat() / total
    val lostFraction = state.forfeitedCents.toFloat() / total

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val strokeWidth = 20.dp.toPx()
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2)
                    var startAngle = -90f

                    fun segment(fraction: Float, color: Color) {
                        val sweep = 360f * fraction
                        if (sweep > 0f) {
                            drawArc(
                                color = color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth)
                            )
                            startAngle += sweep
                        }
                    }
                    segment(recoveredFraction, colors.accent)
                    segment(lostFraction, colors.dayMissed)
                    segment((1f - recoveredFraction - lostFraction).coerceAtLeast(0f), colors.dayUpcoming)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtCents(state.securedCents), style = NofonTextStyles.amountLarge(30.sp), color = colors.accent)
                    Text(
                        "récupérés sur ${fmtCents(total)}",
                        style = NofonTextStyles.small,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Jour ${state.dayIndex} / ${goal.durationDays}",
                        style = NofonTextStyles.smallMono,
                        color = colors.onSurfaceTertiary
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot(colors.accent, "Récupéré")
                LegendDot(colors.dayUpcoming, "En jeu ${fmtCents(state.atStakeCents)}")
                LegendDot(colors.dayMissed, "Perdu ${fmtCents(state.forfeitedCents)}")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                Modifier.weight(1f),
                "Écran aujourd'hui",
                todayScreenMillis?.let { fmtDuration(it) } ?: "—",
                goal.timeLimitSeconds?.let { "limite ${it / 60} min" }
            )
            MetricCard(
                Modifier.weight(1f),
                "Déverrouillages",
                todayUnlocks?.toString() ?: "—",
                goal.opensLimit?.let { "limite $it" }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(Modifier.weight(1f), "Série actuelle", "${goal.currentStreakDays} jours", "meilleure ${goal.bestStreakDays}")
            MetricCard(Modifier.weight(1f), "État du jour", fmtStatus(state.todayStatus), null)
        }

        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("7 derniers jours")
                Text(
                    "Synchroniser",
                    style = NofonTextStyles.small,
                    color = colors.accent,
                    modifier = Modifier.clickable(onClick = onSync)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                state.recentDays.forEach { day -> RecentDayColumn(Modifier.weight(1f), day, goal.timezone) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NofonPrimaryButton("Voir le détail", onClick = onOpenAppDetail, modifier = Modifier.weight(1f))
            NofonSecondaryButton("Historique", onClick = onOpenHistory, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = NofonTextStyles.small, color = NofonTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun MetricCard(modifier: Modifier, label: String, value: String, caption: String?) {
    val colors = NofonTheme.colors
    NofonCard(modifier = modifier) {
        Text(label, style = NofonTextStyles.small, color = colors.onSurfaceVariant)
        Text(value, style = NofonTextStyles.metricDisplay, color = colors.onSurface)
        caption?.let { Text(it, style = NofonTextStyles.small, color = colors.onSurfaceTertiary) }
    }
}

@Composable
private fun RecentDayColumn(modifier: Modifier, day: DailyResult, timezone: String) {
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
    val date = runCatching { LocalDate.parse(day.localDate) }.getOrNull()
    val letter = date?.let { frenchDayLetter(it.dayOfWeek) } ?: ""
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(dayStateColor(dayStateFor(day.status.ifBlank { null })), RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(letter, style = NofonTextStyles.small, color = NofonTheme.colors.onSurfaceTertiary)
    }
    // fuseau conservé pour un futur affichage de la date complète en info-bulle
    zone.id
}

private fun frenchDayLetter(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "L"
    DayOfWeek.TUESDAY -> "M"
    DayOfWeek.WEDNESDAY -> "M"
    DayOfWeek.THURSDAY -> "J"
    DayOfWeek.FRIDAY -> "V"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "D"
}

/**
 * Hiérarchie secondaire (PRODUCT_SPEC-2.md §8) : pas d'anneau ni de grille de métriques
 * comme l'objectif payant, mais un historique court (3 derniers jours) et un taux de
 * réussite depuis la résolution de TODO-24 (`daily_results` désormais alimenté pour les
 * objectifs gratuits par `TrackingSyncWorker`, au même titre que le payant).
 */
@Composable
private fun FreeGoalsSection(goals: List<FreeGoalUiState>) {
    val colors = NofonTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Autres objectifs", modifier = Modifier.padding(bottom = 2.dp))
        goals.forEach { state -> FreeGoalCard(state) }
    }
}

@Composable
private fun FreeGoalCard(state: FreeGoalUiState) {
    val goal = state.goal
    val colors = NofonTheme.colors
    NofonCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Sans caution · ${limitLabel(goal)}",
                style = NofonTextStyles.body,
                color = colors.onSurfaceVariant
            )
            Text(
                "Jour ${state.dayIndex} / ${goal.durationDays}",
                style = NofonTextStyles.smallMono,
                color = colors.onSurfaceTertiary
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                state.recentDays.forEach { day ->
                    DayStateChip(dayStateFor(day.status.ifBlank { null }))
                }
            }
            Text(
                state.successRatePercent?.let { "$it % de réussite" } ?: "Pas encore de donnée",
                style = NofonTextStyles.small,
                color = colors.onSurfaceVariant
            )
        }
    }
}

private fun limitLabel(goal: Goal): String = when (goal.constraintType) {
    "TIME" -> "${(goal.timeLimitSeconds ?: 0) / 60} min"
    "OPENS" -> "${goal.opensLimit ?: 0} déverr."
    else -> "${(goal.timeLimitSeconds ?: 0) / 60} min · ${goal.opensLimit ?: 0} déverr."
}

internal fun fmtStatus(status: String): String = when (status) {
    "SUCCESS" -> "Réussi"
    "FAILED" -> "Manqué"
    "NO_DATA" -> "Donnée indisponible"
    "PROVISIONAL" -> "Provisoire"
    else -> "En cours"
}

/** Format `X,XX €` déterministe (virgule décimale, jamais dépendant de la locale de l'appareil). */
internal fun fmtCents(cents: Long): String = String.format(java.util.Locale.FRANCE, "%.2f €", cents / 100.0)

internal fun fmtDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h} h ${m.toString().padStart(2, '0')}" else "$m min"
}
