package com.nofon.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nofon.core.designsystem.ExploitRarityTier
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.SectionLabel
import com.nofon.core.network.DailyMeasurementPoint

/**
 * Moyenne de déverrouillages sur la période sélectionnée du graphique (même liste de
 * points, pas de second appel réseau). Même cadre (`NofonCard`) et même mise en avant du
 * chiffre (`amountMedium`) que les autres pages — demande explicite ("le cadre petit ne
 * rend pas bien" / "les chiffres doivent être intégrés de manière plus stylée").
 */
@Composable
fun UnlocksAveragePanel(
    points: List<DailyMeasurementPoint>,
    comparisonPoints: List<DailyMeasurementPoint>?,
    comparisonLabel: String?,
    modifier: Modifier = Modifier
) {
    val colors = NofonTheme.colors
    val average = averageOpens(points)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Déverrouillages / jour")
        NofonCard {
            // Comparaison à côté, jamais en dessous (demande explicite) : ne doit
            // jamais rendre ce cadre plus haut que celui des exploits, à côté duquel
            // il est affiché.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                Text(average?.let { "%.1f".format(it) } ?: "—", style = NofonTextStyles.amountMedium, color = colors.accent)
                if (comparisonPoints != null && comparisonLabel != null) {
                    Text(
                        "$comparisonLabel : ${averageOpens(comparisonPoints)?.let { "%.1f".format(it) } ?: "—"}",
                        style = NofonTextStyles.small,
                        color = colors.onSurfaceTertiary
                    )
                }
            }
        }
    }
}

private fun averageOpens(points: List<DailyMeasurementPoint>): Double? {
    val usable = points.filter { it.dataQuality != "UNAVAILABLE" }
    if (usable.isEmpty()) return null
    return usable.map { it.measuredOpens }.average()
}

/**
 * Répartition des exploits débloqués par rareté — médailles colorées. Cliquable vers
 * l'onglet Exploits (demande explicite). Grille 2×2 en police compacte plutôt que 4
 * lignes empilées — demande explicite : même hauteur de cadre que Déverrouillages, à
 * côté duquel ce cadre est affiché. TODO-33 (PRODUCT_TODOS-2.md) : `counts`/
 * `comparisonCounts` indexés par `ExploitRarityTier.name`, recalculé en direct — même
 * taxonomie que l'écran Exploits.
 */
@Composable
fun ExploitRarityPanel(
    counts: Map<String, Int>,
    comparisonCounts: Map<String, Int>?,
    onOpenExploits: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = NofonTheme.colors

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Exploits par rareté")
        NofonCard(modifier = onOpenExploits?.let { Modifier.clickable(onClick = it) } ?: Modifier) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ExploitRarityTier.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        pair.forEach { tier ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(9.dp).background(tier.color, CircleShape))
                                Text(
                                    if (comparisonCounts != null) {
                                        "${counts[tier.name] ?: 0} (${comparisonCounts[tier.name] ?: 0})"
                                    } else {
                                        "${counts[tier.name] ?: 0}"
                                    },
                                    style = NofonTextStyles.small,
                                    color = colors.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
