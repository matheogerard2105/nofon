package com.nofon.feature.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.Avatar
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme
import com.nofon.core.designsystem.capitalizedHandle

/**
 * Suppression : par l'auteur du commentaire OU l'auteur du feed_event (règle exacte
 * d'ARCHITECTURE-2.md §8, appliquée aussi en RLS côté serveur — ce bouton n'est qu'un
 * raccourci UI, la vraie garantie reste `comments_delete_own_or_event_author`).
 */
@Composable
fun CommentsDialog(
    feedEventId: String,
    feedEventAuthorId: String,
    onDismiss: () -> Unit,
    viewModel: CommentsViewModel = hiltViewModel()
) {
    LaunchedEffect(feedEventId) { viewModel.open(feedEventId) }
    val comments by viewModel.comments.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val currentUserId = viewModel.currentUserId
    val colors = NofonTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Commentaires", style = NofonTextStyles.title, color = colors.onSurface) },
        text = {
            Column {
                if (comments.isEmpty()) {
                    EndOfListNote("Aucun commentaire pour l'instant.")
                } else {
                    LazyColumn(
                        Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(comments, key = { it.id }) { comment ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Avatar(url = comment.authorAvatarUrl, size = 28.dp, modifier = Modifier.padding(top = 2.dp))
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(comment.authorHandle.capitalizedHandle(), style = NofonTextStyles.small, color = colors.onSurfaceVariant)
                                    Text(comment.body, style = NofonTextStyles.body, color = colors.onSurface)
                                }
                                if (comment.authorId == currentUserId || feedEventAuthorId == currentUserId) {
                                    IconButton(onClick = { viewModel.delete(comment.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Ajouter un commentaire", style = NofonTextStyles.body) },
                        textStyle = NofonTextStyles.body,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.onSurface,
                            unfocusedTextColor = colors.onSurface,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.divider,
                            cursorColor = colors.accent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.post(draft); draft = "" }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer", tint = colors.accent)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fermer", style = NofonTextStyles.bodyEmphasis, color = colors.accent)
            }
        }
    )
}
