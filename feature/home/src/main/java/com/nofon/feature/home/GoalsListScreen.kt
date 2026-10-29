package com.nofon.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.NofonCard
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.network.Goal

/** TODO-32 : self uniquement, ouvert depuis le pourcentage de réussite du profil. */
@Composable
fun GoalsListScreen(onOpenHistory: (String) -> Unit, viewModel: GoalsListViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.load() }
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    val colors = NofonTheme.colors

    LazyColumn(
        Modifier.fillMaxSize().background(colors.background).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(goals, key = { it.id }) { goal -> GoalRow(goal, onClick = { onOpenHistory(goal.id) }) }
        item { EndOfListNote(if (goals.isEmpty()) "Aucun objectif pour l'instant." else "Fin de la liste.") }
    }
}

@Composable
private fun GoalRow(goal: Goal, onClick: () -> Unit) {
    val colors = NofonTheme.colors
    NofonCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            if (goal.kind == "STAKED") "Objectif payant" else "Objectif gratuit",
            style = NofonTextStyles.bodyEmphasis,
            color = colors.onSurface
        )
        Text(
            "${goal.startDate} → ${goal.endDate} · ${statusLabel(goal.status)}",
            style = NofonTextStyles.small,
            color = colors.onSurfaceVariant
        )
    }
}

private fun statusLabel(status: String): String = when (status) {
    "ACTIVE" -> "en cours"
    "COMPLETED" -> "terminé"
    "FAILED" -> "échoué"
    else -> status.lowercase()
}
