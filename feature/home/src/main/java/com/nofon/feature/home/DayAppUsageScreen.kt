package com.nofon.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.SectionLabel

/**
 * Détail app par app d'une journée — demande explicite pour diagnostiquer un écart de
 * temps d'écran (savoir de quelle(s) application(s) il vient). Donnée strictement
 * locale à l'appareil (voir `DayAppUsageViewModel`) : absente au-delà de la fenêtre de
 * rétention locale, jamais une valeur inventée pour la remplacer.
 */
@Composable
fun DayAppUsageScreen(localDate: String, viewModel: DayAppUsageViewModel = hiltViewModel()) {
    LaunchedEffect(localDate) { viewModel.load(localDate) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = NofonTheme.colors

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(localDate)
            val total = state?.totalMillis
            Text(
                if (total != null) fmtDuration(total) else "Donnée indisponible sur cet appareil",
                style = NofonTextStyles.amountMedium,
                color = colors.onSurface
            )
            val unlocks = state?.unlocks
            val quality = state?.quality
            if (unlocks != null || quality != null) {
                Text(
                    listOfNotNull(
                        unlocks?.let { "$it déverrouillages" },
                        quality?.let { qualityLabel(it) }
                    ).joinToString(" · "),
                    style = NofonTextStyles.small,
                    color = colors.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val apps = state?.apps.orEmpty()
            items(apps, key = { it.packageName }) { app -> AppUsageRow(app) }
            item {
                EndOfListNote(
                    when {
                        state == null -> "Chargement…"
                        state?.totalMillis == null -> "Cette journée est hors de la fenêtre de rétention locale (30 jours) — plus aucun détail disponible."
                        apps.isEmpty() -> "Aucune application mesurée ce jour-là."
                        else -> "Fin de la liste."
                    }
                )
            }
        }
    }
}

@Composable
private fun AppUsageRow(app: AppUsageRowUiState) {
    val colors = NofonTheme.colors
    NofonCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(app.label, style = NofonTextStyles.bodyEmphasis, color = colors.onSurface)
            Text(fmtDuration(app.foregroundMillis), style = NofonTextStyles.smallMono, color = colors.onSurface)
        }
        Text(
            "${app.sessionCount} session" + (if (app.sessionCount > 1) "s" else "") +
                " · ${app.launchCount} lancement" + (if (app.launchCount > 1) "s" else ""),
            style = NofonTextStyles.small,
            color = colors.onSurfaceTertiary
        )
    }
}

private fun qualityLabel(quality: String): String = when (quality) {
    "MEASURED" -> "mesuré"
    "RECONSTRUCTED" -> "reconstruit"
    "ESTIMATED" -> "estimé"
    "UNAVAILABLE" -> "indisponible"
    else -> quality.lowercase()
}
