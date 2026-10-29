package com.nofon.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme

/**
 * TODO-29 (PRODUCT_TODOS-2.md) : suppression de compte déplacée de l'écran Profil ici
 * (refonte du profil, 2026-09-04) — la déconnexion, autrefois un bouton temporaire dans
 * la barre du haut (`NofonNavHost.kt`), y vit désormais aussi.
 */
@Composable
fun SettingsScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val colors = NofonTheme.colors
    val error by viewModel.accountDeletionError.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(colors.background).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        error?.let { Text(it, color = colors.error, style = NofonTextStyles.small) }

        OutlinedButton(
            onClick = viewModel::signOut,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant)
        ) { Text("Se déconnecter") }

        OutlinedButton(
            onClick = { showDeleteDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error)
        ) { Text("Supprimer mon compte") }
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                viewModel.deleteAccount()
                showDeleteDialog = false
            }
        )
    }
}
