package com.nofon.feature.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nofon.core.designsystem.Avatar
import com.nofon.core.designsystem.EndOfListNote
import com.nofon.core.designsystem.NofonTextStyles
import com.nofon.core.designsystem.NofonTheme

@Composable
fun NotificationsScreen(
    onOpenProfile: (String) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val colors = NofonTheme.colors

    LazyColumn(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 18.dp, vertical = 8.dp)) {
        items(notifications, key = { it.id }) { notification ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .let { base ->
                        val actorId = notification.actorId
                        if (actorId != null) base.clickable { onOpenProfile(actorId) } else base
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box {
                    Avatar(url = notification.actorAvatarUrl, size = 40.dp)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .background(colors.surfaceVariant, CircleShape)
                            .border(2.dp, colors.background, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            notificationIcon(notification.type),
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
                Text(
                    notificationLabel(notification),
                    style = NofonTextStyles.body,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            EndOfListNote(if (notifications.isEmpty()) "Aucune notification pour l'instant." else "Fin des notifications.")
        }
    }
}

private fun notificationIcon(type: String): ImageVector = when (type) {
    "NEW_FOLLOWER" -> Icons.Default.PersonAdd
    "REACTION" -> Icons.Default.ThumbUp
    "COMMENT" -> Icons.Default.ChatBubbleOutline
    else -> Icons.Default.ThumbUp
}

private fun notificationLabel(notification: NotificationUiState): String {
    val handle = notification.actorHandle ?: "Quelqu'un"
    return when (notification.type) {
        "NEW_FOLLOWER" -> "$handle a commencé à te suivre"
        "REACTION" -> "$handle a réagi à ton contenu"
        "COMMENT" -> "$handle a commenté ton contenu"
        else -> "Nouvelle notification"
    }
}
