package com.nofon.feature.group

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.SectionLabel
import com.nofon.core.designsystem.capitalizedHandle
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Évolution "% de l'objectif avant dépassement" (demande explicite, ex. 30 min/60 =
 * 50 %) de chaque membre depuis la création du groupe, une courbe par membre dans sa
 * couleur. Ligne pointillée à 100 % : seuil de dépassement, pas juste un plafond visuel.
 * `todayPercentByUserId` (recalculé à chaque rafraîchissement de l'écran, jamais figé)
 * affiché à droite du tracé, pas seulement le dernier point de la courbe — le jour même
 * n'est pas forcément déjà résolu côté serveur.
 */
@Composable
fun GroupProgressSection(
    series: List<MemberProgressSeries>,
    members: List<MemberCardUiState>,
    todayPercentByUserId: Map<String, Float>,
    modifier: Modifier = Modifier
) {
    val colors = NofonTheme.colors
    val allDates = series.flatMap { s -> s.points.map { it.first } }
    val hasAnyPoint = series.any { s -> s.points.any { it.second != null } } || todayPercentByUserId.isNotEmpty()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Évolution")
        if (!hasAnyPoint) {
            Box(Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(10.dp))) {
                Text(
                    "Pas encore de données sur ce groupe.",
                    style = NofonTextStyles.small,
                    color = colors.onSurfaceTertiary,
                    modifier = Modifier.padding(12.dp)
                )
            }
            return@Column
        }

        val start = allDates.minOrNull() ?: LocalDate.now()
        val today = LocalDate.now()
        val totalDays = (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceAtLeast(1)
        val maxPercent = (series.flatMap { it.points.mapNotNull { p -> p.second } } + todayPercentByUserId.values)
            .maxOrNull()?.coerceAtLeast(100f) ?: 100f

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(
                Modifier
                    .weight(1f)
                    .height(160.dp)
                    .background(colors.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                val stepX = if (totalDays > 1) size.width / (totalDays - 1) else 0f
                fun x(i: Int) = i * stepX
                fun y(percent: Float) = size.height - (percent / maxPercent).coerceIn(0f, 1f) * size.height

                val thresholdY = y(100f)
                drawLine(
                    color = colors.onSurfaceTertiary,
                    start = Offset(0f, thresholdY),
                    end = Offset(size.width, thresholdY),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )

                series.forEach { s ->
                    val byDate = s.points.toMap()
                    val values = (0 until totalDays).map { offset -> byDate[start.plusDays(offset.toLong())] }
                    drawSeries(values, s.color, ::x, ::y)
                }
            }

            Column(
                Modifier.width(78.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                members.forEach { member ->
                    val pct = todayPercentByUserId[member.userId]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(7.dp).background(member.color, CircleShape))
                        Text(
                            pct?.let { "%.0f".format(it) + " %" } ?: "—",
                            style = NofonTextStyles.smallMono,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDate(start), style = NofonTextStyles.small, color = colors.onSurfaceTertiary)
            Text("Aujourd'hui", style = NofonTextStyles.small, color = colors.onSurfaceTertiary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            members.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(7.dp).background(member.color, CircleShape))
                    Text(member.handle.capitalizedHandle(), style = NofonTextStyles.small, color = colors.onSurfaceTertiary)
                }
            }
        }
    }
}

private fun DrawScope.drawSeries(
    values: List<Float?>,
    color: Color,
    x: (Int) -> Float,
    y: (Float) -> Float
) {
    var runStart = -1
    values.forEachIndexed { i, percent ->
        if (percent != null) {
            if (runStart == -1) runStart = i
            if (i > runStart) {
                drawLine(
                    color = color,
                    start = Offset(x(i - 1), y(values[i - 1]!!)),
                    end = Offset(x(i), y(percent)),
                    strokeWidth = 4f
                )
            }
            drawCircle(color = color, radius = 4f, center = Offset(x(i), y(percent)))
        } else {
            runStart = -1
        }
    }
}

private fun formatDate(date: LocalDate): String = "%02d/%02d".format(date.dayOfMonth, date.monthValue)
