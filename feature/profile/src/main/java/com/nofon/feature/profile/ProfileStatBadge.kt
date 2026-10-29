package com.nofon.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme

/**
 * SANS cadre (demande explicite : ces stats d'identité se fondent dans le fond, à la
 * différence des cadres plus bas dans l'écran — Déverrouillages/Exploits) — logo en
 * haut comme référentiel visuel (même schéma que la répartition par rareté des
 * exploits : icône puis valeur puis libellé), plutôt que l'icône accolée au chiffre.
 * Toute la zone est le point de contact : navigue si `onValueClick` est fourni,
 * sinon affiche l'explication (abonnés/abonnements naviguent, les deux séries expliquent).
 */
@Composable
fun ProfileStatBadge(
    value: String,
    label: String,
    icon: ImageVector,
    tint: Color,
    explanation: String,
    onValueClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showInfo by remember { mutableStateOf(false) }
    val colors = NofonTheme.colors

    Column(
        modifier.fillMaxWidth().clickable(onClick = onValueClick ?: { showInfo = true }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(value, style = NofonTextStyles.metricDisplay, color = colors.onSurface)
        Text(label, style = NofonTextStyles.small, color = colors.onSurfaceVariant)
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            containerColor = colors.surface,
            title = { Text(label, style = NofonTextStyles.title, color = colors.onSurface) },
            text = { Text(explanation, style = NofonTextStyles.body, color = colors.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Compris", style = NofonTextStyles.bodyEmphasis, color = colors.accent)
                }
            }
        )
    }
}
