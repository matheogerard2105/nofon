package com.nofon.feature.group

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nofon.core.designsystem.Avatar
import com.nofon.core.designsystem.NofonTheme

private val GROUP_AVATAR_SIZE = 64.dp

/**
 * Même mécanique que `feature.profile.ProfileAvatar` (bouton photo, recadrage/upload
 * gérés par `GroupRepository.uploadGroupAvatar`) — dupliqué plutôt que partagé via
 * `core:designsystem` pour ne pas ajouter les dépendances activity-compose/icons-extended
 * à ce module par ailleurs volontairement minimal.
 */
@Composable
fun GroupAvatar(
    avatarUrl: String?,
    editable: Boolean,
    onImagePicked: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NofonTheme.colors
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    Box(
        modifier.then(
            if (editable) {
                Modifier.clickable {
                    launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            } else {
                Modifier
            }
        ),
        contentAlignment = Alignment.Center
    ) {
        Avatar(url = avatarUrl, size = GROUP_AVATAR_SIZE)
        if (editable) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(colors.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Changer le logo", tint = colors.onAccent, modifier = Modifier.size(13.dp))
            }
        }
    }
}
