package com.nofon.feature.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nofon.core.designsystem.NofonTheme

@Composable
fun EditHandleDialog(currentHandle: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var handle by remember { mutableStateOf(currentHandle) }
    val colors = NofonTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changer de pseudo") },
        text = {
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = it },
                placeholder = { Text("Nouveau pseudo") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    cursorColor = colors.accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (handle.isNotBlank()) onSubmit(handle) }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
