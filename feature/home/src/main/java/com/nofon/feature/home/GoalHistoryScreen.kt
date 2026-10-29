package com.nofon.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.nofon.core.designsystem.DayStateChip
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.dayStateFor
import com.nofon.core.network.DailyResult

@Composable
fun GoalHistoryScreen(
    goalId: String,
    onOpenAppUsage: (String) -> Unit = {},
    viewModel: GoalHistoryViewModel = hiltViewModel()
) {
    LaunchedEffect(goalId) { viewModel.load(goalId) }
    val results by viewModel.results.collectAsStateWithLifecycle()
    val colors = NofonTheme.colors

    LazyColumn(
        Modifier.fillMaxSize().background(colors.background).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(results, key = { it.localDate }) { result ->
            DailyResultRow(result, onClick = { onOpenAppUsage(result.localDate) })
        }
        item { EndOfListNote("Fin de l'historique. Toucher un jour pour le détail app par app.") }
    }
}

@Composable
private fun DailyResultRow(result: DailyResult, onClick: () -> Unit) {
    val colors = NofonTheme.colors
    NofonCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(result.localDate, style = NofonTextStyles.smallMono, color = colors.onSurfaceVariant)
            DayStateChip(dayStateFor(result.status))
        }
        Text(fmtStatus(result.status), style = NofonTextStyles.bodyEmphasis, color = colors.onSurface)
        if (result.measuredTimeSeconds != null || result.measuredOpens != null) {
            Text(
                "Temps d'écran : ${(result.measuredTimeSeconds ?: 0) / 60} min · " +
                    "Déverrouillages : ${result.measuredOpens ?: 0}",
                style = NofonTextStyles.body,
                color = colors.onSurfaceVariant
            )
        }
    }
}
