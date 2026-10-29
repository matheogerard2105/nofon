package com.nofon.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.NofonSecondaryButton
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.capitalizedHandle

/**
 * `userId = null` : onglet Profil (soi-même). `userId` renseigné : profil public d'un
 * tiers, atteint via `profile/{userId}` (recherche, fil, listes d'abonnés) — voir
 * `NofonNavHost.kt`. Contenu limité aux champs publics de PRODUCT_SPEC-2.md §10.1 (TODO-18,
 * amendé 2026-09-03/04 pour le temps d'écran et les déverrouillages quotidiens — TODO-32
 * pour ce qui reste privé). Refonte de la mise en page le 2026-09-04 : avatar modifiable,
 * stats avec info-bulles, comparaison sur le profil d'un tiers.
 */
@Composable
fun ProfileScreen(
    userId: String? = null,
    onOpenFollowers: (String) -> Unit = {},
    onOpenFollowing: (String) -> Unit = {},
    onOpenGoalsList: () -> Unit = {},
    onOpenExploits: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    LaunchedEffect(userId) { viewModel.load(userId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val screenTimeRange by viewModel.screenTimeRange.collectAsStateWithLifecycle()
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val comparisonMeasurements by viewModel.comparisonMeasurements.collectAsStateWithLifecycle()
    val exploitCounts by viewModel.exploitCounts.collectAsStateWithLifecycle()
    val comparisonExploitCounts by viewModel.comparisonExploitCounts.collectAsStateWithLifecycle()
    val comparisonLabel by viewModel.comparisonLabel.collectAsStateWithLifecycle()
    var showEditHandleDialog by remember { mutableStateOf(false) }
    val current = state
    val colors = NofonTheme.colors
    val context = LocalContext.current

    if (current == null) {
        Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
            Text("Chargement…", style = NofonTextStyles.body, color = colors.onSurfaceVariant)
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        actionError?.let {
            Text(it, color = colors.error, style = NofonTextStyles.small)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileStatBadge(
                    value = "${current.profile.followersCount}",
                    label = "Abonnés",
                    icon = Icons.Default.Groups,
                    tint = colors.success,
                    explanation = "Les personnes qui te suivent.",
                    onValueClick = { onOpenFollowers(current.profile.userId) }
                )
                ProfileStatBadge(
                    value = "${current.profile.followingCount}",
                    label = "Abonnements",
                    icon = Icons.Default.Groups,
                    tint = NofonProfileBlue,
                    explanation = "Les personnes que tu suis.",
                    onValueClick = { onOpenFollowing(current.profile.userId) }
                )
            }
            ProfileAvatar(
                avatarUrl = current.profile.avatarUrl,
                editable = current.isSelf,
                onImagePicked = { uri -> viewModel.updateAvatar(context, uri) }
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileStatBadge(
                    value = "${current.profile.bestStreakDays}",
                    label = "Série max",
                    icon = Icons.Default.EmojiEvents,
                    tint = colors.accent,
                    explanation = "Le record personnel de jours consécutifs, tous objectifs confondus."
                )
                ProfileStatBadge(
                    value = "${current.profile.currentStreakDays ?: 0}",
                    label = "Série en cours",
                    icon = Icons.Default.LocalFireDepartment,
                    tint = colors.accent,
                    explanation = "Le nombre de jours consécutifs où l'objectif en cours a été respecté."
                )
            }
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(current.profile.handle.capitalizedHandle(), style = NofonTextStyles.title, color = colors.onSurface)
                if (current.isSelf) {
                    TextButton(onClick = { showEditHandleDialog = true }) { Text("Modifier", color = colors.accent) }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GoalsSummaryCard(
                label = "Objectifs",
                value = "${current.profile.activeGoalsCount}",
                caption = "${current.profile.completedGoalsCount} terminés",
                onClick = if (current.isSelf) onOpenGoalsList else null,
                modifier = Modifier.weight(1f)
            )
            GoalsSummaryCard(
                label = "Réussite",
                value = current.profile.successRatePercent?.let { "$it %" } ?: "—",
                caption = null,
                onClick = if (current.isSelf) onOpenGoalsList else null,
                modifier = Modifier.weight(1f)
            )
        }

        ScreenTimeSection(
            range = screenTimeRange,
            points = measurements,
            comparisonPoints = comparisonMeasurements,
            comparisonLabel = comparisonLabel,
            onSelectRange = viewModel::selectScreenTimeRange
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UnlocksAveragePanel(
                points = measurements,
                comparisonPoints = comparisonMeasurements,
                comparisonLabel = comparisonLabel,
                modifier = Modifier.weight(1f)
            )
            ExploitRarityPanel(
                counts = exploitCounts,
                comparisonCounts = comparisonExploitCounts,
                onOpenExploits = if (current.isSelf) onOpenExploits else null,
                modifier = Modifier.weight(1f)
            )
        }

        if (!current.isSelf) {
            NofonSecondaryButton(
                text = if (current.isFollowing) "Ne plus suivre" else "Suivre",
                onClick = viewModel::toggleFollow
            )
        }
    }

    if (showEditHandleDialog) {
        EditHandleDialog(
            currentHandle = current.profile.handle,
            onDismiss = { showEditHandleDialog = false },
            onSubmit = { newHandle ->
                viewModel.updateHandle(newHandle)
                showEditHandleDialog = false
            }
        )
    }
}

/**
 * Objectifs/taux de réussite affichés synthétiquement (demande explicite) — SANS cadre,
 * fondu dans le fond comme les autres stats d'identité (les cadres sont réservés à
 * Déverrouillages/Exploits, plus bas). Cliquable vers le détail (`onOpenGoalsList`)
 * seulement sur son propre profil (`onClick == null` sur le profil d'un tiers :
 * historique privé, TODO-32).
 */
@Composable
private fun GoalsSummaryCard(label: String, value: String, caption: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = NofonTheme.colors
    Column(
        modifier.fillMaxWidth().then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = NofonTextStyles.small, color = colors.onSurfaceVariant)
        Text(value, style = NofonTextStyles.metricDisplay, color = colors.onSurface)
        caption?.let { Text(it, style = NofonTextStyles.small, color = colors.onSurfaceTertiary) }
    }
}
