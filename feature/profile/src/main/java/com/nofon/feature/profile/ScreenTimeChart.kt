package com.nofon.feature.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.SectionLabel
import com.nofon.core.network.DailyMeasurementPoint
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * TODO-18 amendé (PRODUCT_TODOS-2.md, 2026-09-03/04) : temps d'écran + déverrouillages
 * quotidiens exacts, publics sur n'importe quel profil. `points` vient de
 * `user_daily_measurements` (0008_profile_screen_time.sql) — voir TODO-31 pour
 * l'ambiguïté multi-objectifs derrière la valeur affichée pour un jour donné.
 *
 * `comparisonPoints`/`comparisonLabel` : sur le profil d'un tiers, superpose nos propres
 * données (demande explicite — "comparable au notre"). `null` sur son propre profil.
 */
@Composable
fun ScreenTimeSection(
    range: ScreenTimeRange,
    points: List<DailyMeasurementPoint>,
    comparisonPoints: List<DailyMeasurementPoint>?,
    comparisonLabel: String?,
    onSelectRange: (ScreenTimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NofonTheme.colors

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Temps d'écran")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScreenTimeRange.entries.forEach { candidate ->
                val selected = candidate == range
                Box(
                    Modifier
                        .background(
                            if (selected) colors.accent else colors.surfaceVariant,
                            RoundedCornerShape(20.dp)
                        )
                        .clickable { onSelectRange(candidate) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        candidate.label,
                        style = NofonTextStyles.small,
                        color = if (selected) colors.onAccent else colors.onSurfaceVariant
                    )
                }
            }
        }
        if (comparisonPoints != null && comparisonLabel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ChartLegendDot("Ce profil", colors.accent)
                ChartLegendDot(comparisonLabel, NofonProfileBlue)
            }
        }
        // Même cadre que le reste de l'écran (demande explicite) — remplace le fond
        // ad hoc que portait directement le tracé.
        NofonCard {
            ScreenTimeLineChart(
                range = range,
                points = points,
                comparisonPoints = comparisonPoints,
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
        }
    }
}

@Composable
private fun ChartLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = NofonTextStyles.small, color = NofonTheme.colors.onSurfaceTertiary)
    }
}

@Composable
private fun ScreenTimeLineChart(
    range: ScreenTimeRange,
    points: List<DailyMeasurementPoint>,
    comparisonPoints: List<DailyMeasurementPoint>?,
    modifier: Modifier
) {
    val colors = NofonTheme.colors

    val today = LocalDate.now()
    val mainByDate = minutesByDate(points)
    val comparisonByDate = comparisonPoints?.let { minutesByDate(it) }
    val start = when (range) {
        ScreenTimeRange.ALL_TIME -> (mainByDate.keys + comparisonByDate.orEmpty().keys).minOrNull() ?: today
        ScreenTimeRange.WEEK -> today.minusDays(6)
        ScreenTimeRange.MONTH -> today.minusDays(29)
        ScreenTimeRange.YEAR -> today.minusDays(364)
    }
    val totalDays = (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceAtLeast(1)
    val mainValues = (0 until totalDays).map { offset -> mainByDate[start.plusDays(offset.toLong())] }
    val comparisonValues = comparisonByDate?.let { byDate ->
        (0 until totalDays).map { offset -> byDate[start.plusDays(offset.toLong())] }
    }
    val maxMinutes = (listOfNotNull(mainValues, comparisonValues).flatten().filterNotNull().maxOrNull() ?: 0f)
        .coerceAtLeast(60f)

    if (mainValues.all { it == null } && comparisonValues.orEmpty().all { it == null }) {
        Box(modifier) {
            Text(
                "Pas encore de données sur cette période.",
                style = NofonTextStyles.small,
                color = colors.onSurfaceTertiary,
                modifier = Modifier.padding(12.dp)
            )
        }
        return
    }

    // Sélection tactile (demande explicite : voir le temps de chaque jour, pas
    // seulement le maximum) — un simple appui plutôt qu'un vrai glisser continu, pour
    // rester fiable sur un tracé dense sans avoir pu le valider en conditions réelles
    // avec un vrai geste de glissement.
    var selectedIndex by remember(totalDays) { mutableStateOf<Int?>(null) }

    Column {
        val selected = selectedIndex
        Text(
            selected?.let { i ->
                val date = start.plusDays(i.toLong())
                val mainAt = mainValues.getOrNull(i)
                val compAt = comparisonValues?.getOrNull(i)
                buildString {
                    append(formatDate(date))
                    append(" · ")
                    append(mainAt?.let { formatMinutes(it) } ?: "pas de donnée")
                    if (compAt != null) append(" · ${compAt.let { formatMinutes(it) }} (comparaison)")
                }
            } ?: "Max : ${formatMinutes(maxMinutes)}",
            style = NofonTextStyles.small,
            color = if (selected != null) colors.onSurface else colors.onSurfaceTertiary
        )
        Canvas(
            modifier
                .padding(vertical = 4.dp)
                .pointerInput(totalDays) {
                    detectTapGestures { offset ->
                        if (totalDays <= 1) {
                            selectedIndex = 0
                            return@detectTapGestures
                        }
                        val stepX = size.width.toFloat() / (totalDays - 1)
                        selectedIndex = (offset.x / stepX).roundToInt().coerceIn(0, totalDays - 1)
                    }
                }
        ) {
            val stepX = if (totalDays > 1) size.width / (totalDays - 1) else 0f
            fun x(i: Int) = i * stepX
            fun y(minutes: Float) = size.height - (minutes / maxMinutes).coerceIn(0f, 1f) * size.height

            drawSeries(mainValues, colors.accent, ::x, ::y)
            comparisonValues?.let { drawSeries(it, NofonProfileBlue, ::x, ::y) }

            selected?.let { i ->
                drawLine(
                    color = colors.onSurfaceTertiary,
                    start = Offset(x(i), 0f),
                    end = Offset(x(i), size.height),
                    strokeWidth = 2f
                )
                mainValues.getOrNull(i)?.let { drawCircle(color = colors.onSurface, radius = 7f, center = Offset(x(i), y(it))) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDate(start), style = NofonTextStyles.small, color = colors.onSurfaceTertiary)
            Text(formatDate(today), style = NofonTextStyles.small, color = colors.onSurfaceTertiary)
        }
    }
}

private fun minutesByDate(points: List<DailyMeasurementPoint>): Map<LocalDate, Float> =
    // Un trou (UNAVAILABLE, ou aucune ligne du tout ce jour-là) reste un trou visuel —
    // jamais reconstitué en zéro trompeur (même principe NO_DATA que le reste de l'app).
    points
        .filter { it.dataQuality != "UNAVAILABLE" }
        .associate { LocalDate.parse(it.localDate) to it.measuredTimeSeconds / 60f }

private fun DrawScope.drawSeries(
    values: List<Float?>,
    color: Color,
    x: (Int) -> Float,
    y: (Float) -> Float
) {
    var runStart = -1
    values.forEachIndexed { i, minutes ->
        if (minutes != null) {
            if (runStart == -1) runStart = i
            if (i > runStart) {
                drawLine(
                    color = color,
                    start = Offset(x(i - 1), y(values[i - 1]!!)),
                    end = Offset(x(i), y(minutes)),
                    strokeWidth = 4f
                )
            }
            drawCircle(color = color, radius = 5f, center = Offset(x(i), y(minutes)))
        } else {
            runStart = -1
        }
    }
}

private fun formatMinutes(minutes: Float): String {
    val total = minutes.toInt()
    val hours = total / 60
    val mins = total % 60
    return if (hours > 0) "${hours}h${mins.toString().padStart(2, '0')}" else "${mins}min"
}

private fun formatDate(date: LocalDate): String = "%02d/%02d".format(date.dayOfMonth, date.monthValue)
