package com.nofon.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Coins 14dp, fond `surface`, bordure 1dp `divider` — carte de base réutilisée sur tous les écrans. */
@Composable
fun NofonCard(
    modifier: Modifier = Modifier,
    variant: Boolean = false,
    content: ColumnScopeContent
) {
    val colors = NofonTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(if (variant) colors.surfaceVariant else colors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, colors.divider, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

private typealias ColumnScopeContent = @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit

@Composable
fun NofonPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = NofonTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(if (enabled) colors.accent else colors.divider, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.onAccent, style = NofonTextStyles.bodyEmphasis)
    }
}

@Composable
fun NofonSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = NofonTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.accent, style = NofonTextStyles.bodyEmphasis)
    }
}

/** Label majuscule discret en tête de section ("OBJECTIF EN COURS", "AUJOURD'HUI"...). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = NofonTextStyles.label,
        color = NofonTheme.colors.onSurfaceVariant
    )
}

enum class DayState { SUCCESS, MISSED, PROVISIONAL, UPCOMING }

/** Mapping unique statut serveur -> état visuel, réutilisé par l'historique et la bande "7 derniers jours". */
fun dayStateFor(status: String?): DayState = when (status) {
    "SUCCESS" -> DayState.SUCCESS
    "FAILED", "NO_DATA" -> DayState.MISSED
    "PROVISIONAL", "PENDING" -> DayState.PROVISIONAL
    else -> DayState.UPCOMING
}

@Composable
fun dayStateColor(state: DayState): Color = when (state) {
    DayState.SUCCESS -> NofonTheme.colors.accent
    DayState.MISSED -> NofonTheme.colors.dayMissed
    DayState.PROVISIONAL -> NofonTheme.colors.dayProvisional
    DayState.UPCOMING -> NofonTheme.colors.dayUpcoming
}

/** Petit badge coloré pour un état de journée (historique, grille 30 jours, bande 7 jours). */
@Composable
fun DayStateChip(state: DayState, modifier: Modifier = Modifier) {
    val color = dayStateColor(state)
    Box(
        modifier
            .size(width = 20.dp, height = 22.dp)
            .background(color, RoundedCornerShape(2.dp))
            .then(
                if (state == DayState.PROVISIONAL) {
                    Modifier.border(1.dp, NofonTheme.colors.onSurfaceTertiary, RoundedCornerShape(2.dp))
                } else {
                    Modifier
                }
            )
    )
}

/** Badge d'état texte (réussi/manqué/en cours/provisoire/donnée indisponible) — panneau "États d'une journée" du handoff. */
@Composable
fun StatusBadge(label: String, state: DayState, modifier: Modifier = Modifier) {
    val colors = NofonTheme.colors
    val (bg, fg) = when (state) {
        DayState.SUCCESS -> colors.accent.copy(alpha = 0.14f) to colors.accent
        DayState.MISSED -> colors.dayUpcoming to colors.onSurfaceVariant
        DayState.PROVISIONAL -> colors.dayUpcoming to colors.onSurfaceVariant
        DayState.UPCOMING -> colors.dayUpcoming to colors.navInactive
    }
    Box(
        modifier
            .background(bg, RoundedCornerShape(6.dp))
            .then(
                if (state == DayState.PROVISIONAL) {
                    Modifier.border(1.dp, colors.onSurfaceTertiary, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(label, color = fg, style = NofonTextStyles.small)
    }
}

/**
 * Fin explicite d'un écran à liste — jamais de défilement infini (PRODUCT_SPEC-2.md §1,
 * `design_handoff_nofon_accueil/README.md`). Un texte contextuel par écran, pas un
 * message générique.
 */
@Composable
fun EndOfListNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        textAlign = TextAlign.Center,
        style = NofonTextStyles.small,
        color = NofonTheme.colors.onSurfaceTertiary
    )
}

/** Bannière d'alerte (permission manquante, prélèvement échoué) — ambre, jamais rouge (réservé aux pannes techniques). */
@Composable
fun AlertBanner(title: String, subtitle: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val colors = NofonTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.alertSurface, RoundedCornerShape(10.dp))
            .border(1.dp, colors.alert.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.padding(top = 6.dp).size(6.dp).background(colors.alert, RoundedCornerShape(50)))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = NofonTextStyles.bodyEmphasis, color = Color(0xFFE8DECD))
            subtitle?.let { Text(it, style = NofonTextStyles.body, color = Color(0xFFE8DECD)) }
            if (actionLabel != null && onAction != null) {
                Text(
                    "$actionLabel →",
                    style = NofonTextStyles.bodyEmphasis,
                    color = colors.alert,
                    modifier = Modifier.clickable(onClick = onAction)
                )
            }
        }
    }
}

/**
 * Pastille pilule pour une action de réaction/commentaire sur une publication (fil
 * social) — remplace l'icône+texte nu de Material par défaut, pour rester dans le
 * langage visuel de l'app (mêmes teintes que `StatusBadge`/`DayStateChip`).
 */
@Composable
fun EngagementChip(
    icon: ImageVector,
    count: Long,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val colors = NofonTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) colors.accent.copy(alpha = 0.14f) else colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) colors.accent else colors.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        Text("$count", style = NofonTextStyles.small, color = if (active) colors.accent else colors.onSurfaceVariant)
    }
}

/**
 * Confirmation avant une action (demande explicite : "des messages de confirmation
 * dans le même style graphique que le reste, pour toute action") — même habillage que
 * les autres `AlertDialog` de l'app (fond/coins/typo). `destructive` colore le bouton de
 * confirmation en rouge (`error`) plutôt qu'accent, pour les actions qui retirent
 * quelqu'un/quelque chose (exclure, quitter, dissoudre, refuser).
 */
@Composable
fun NofonConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Annuler"
) {
    val colors = NofonTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(16.dp),
        title = { Text(title, style = NofonTextStyles.title, color = colors.onSurface) },
        text = { Text(message, style = NofonTextStyles.body, color = colors.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, style = NofonTextStyles.bodyEmphasis, color = if (destructive) colors.error else colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel, color = colors.onSurfaceVariant) }
        }
    )
}

@Composable
fun NofonScreenBackground(modifier: Modifier = Modifier, content: ColumnScopeContent) {
    Column(
        modifier
            .fillMaxWidth()
            .background(NofonTheme.colors.background),
        content = content
    )
}
