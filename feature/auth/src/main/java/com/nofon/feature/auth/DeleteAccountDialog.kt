package com.nofon.feature.auth

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.nofon.core.designsystem.NofonTheme

/**
 * TODO-29 (PRODUCT_TODOS-2.md) : confirmation seule, aucune annulation pendant le délai
 * de grâce. Déplacé de `feature/profile` vers `feature/auth` (refonte du profil,
 * 2026-09-04) — vit désormais dans l'écran Paramètres, aux côtés de la déconnexion.
 */
@Composable
fun DeleteAccountDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = NofonTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer ton compte ?") },
        text = {
            Text(
                "Ton profil sera immédiatement caché des autres utilisateurs, puis " +
                    "définitivement effacé sous 30 jours. Cette action est irréversible."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Supprimer", color = colors.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
