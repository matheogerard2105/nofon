package com.nofon.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Une couleur par membre de groupe (demande explicite — chaque cadre, et sa courbe
 * dans le graphique d'évolution, dans une couleur qui lui est propre). 9 teintes
 * distinctes, une par place maximale dans un groupe (PRODUCT_SPEC-2.md §12.1).
 * Assignation par POSITION dans la liste des membres triée par `created_at` (ordre
 * d'arrivée) — déterministe et stable pour un groupe donné, jamais par hachage
 * (éviterait deux membres de couleur identique par collision).
 */
val GroupMemberPalette: List<Color> = listOf(
    Color(0xFF4FC3F7), // bleu clair
    Color(0xFFAED581), // vert clair
    Color(0xFFFFB74D), // orange
    Color(0xFFBA68C8), // violet
    Color(0xFF4DD0E1), // cyan
    Color(0xFFE57373), // rouge doux
    Color(0xFFFFD54F), // jaune
    Color(0xFF9575CD), // violet foncé
    Color(0xFF81C784)  // vert
)

fun groupMemberColor(indexInGroup: Int): Color =
    GroupMemberPalette[indexInGroup % GroupMemberPalette.size]
