package com.nofon.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Photo de profil — cadre carré à coins arrondis partout dans l'app (abonnés,
 * abonnements, fil, commentaires, écran Profil), jamais un cercle, jamais une taille
 * différente d'un endroit à l'autre pour un même contexte. `ContentScale.Crop` : l'image
 * uploadée est déjà recadrée en carré (`ProfileRepository.uploadAvatar`, `core/network`),
 * mais Crop protège aussi une ancienne valeur non carrée. `null` -> simple pastille
 * neutre, jamais d'icône de remplacement (garde ce module libre de toute dépendance aux
 * icônes étendues).
 */
@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier, cornerRadius: Dp = size / 5) {
    val colors = NofonTheme.colors
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = "Photo de profil",
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(cornerRadius)).background(colors.surfaceVariant)
        )
    } else {
        Box(modifier.size(size).clip(RoundedCornerShape(cornerRadius)).background(colors.surfaceVariant))
    }
}
