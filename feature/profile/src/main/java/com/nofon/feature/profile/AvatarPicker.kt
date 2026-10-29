package com.nofon.feature.profile

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

private val AVATAR_SIZE = 96.dp

/**
 * Cadre carré à coins arrondis (demande explicite, rendu par `Avatar` — `core/designsystem`,
 * partagé avec toutes les autres apparitions du pseudo dans l'app) — modifiable
 * seulement sur son propre profil (`editable`). L'upload lui-même (recadrage carré,
 * redimensionnement, compression, écriture `users.avatar_url`) est géré par
 * `ProfileRepository.uploadAvatar` — ce composable ne fait que déclencher le sélecteur
 * de photos système et transmettre l'URI choisie.
 */
@Composable
fun ProfileAvatar(
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
        Avatar(url = avatarUrl, size = AVATAR_SIZE)
        if (editable) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Changer la photo", tint = colors.onAccent, modifier = Modifier.size(16.dp))
            }
        }
    }
}
