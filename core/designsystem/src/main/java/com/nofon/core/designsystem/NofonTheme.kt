package com.nofon.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Tokens de couleur du handoff de design (`design_handoff_nofon_accueil/README.md`) —
 * ne correspondent pas 1:1 aux rôles sémantiques `ColorScheme` de Material3 (ex. texte
 * "tertiaire" propre à Nofon, distinct de `onSurfaceVariant`), d'où ce jeu de tokens
 * séparé plutôt que de forcer la maquette dans le découpage Material3 standard.
 */
data class NofonColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onSurfaceTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val info: Color,
    val success: Color,
    val alert: Color,
    val alertSurface: Color,
    val error: Color,
    val divider: Color,
    val navBarBackground: Color,
    val navInactive: Color,
    val dayMissed: Color,
    val dayProvisional: Color,
    val dayUpcoming: Color
)

/**
 * Thème sombre uniquement pour l'instant : la maquette ne définit aucune variante
 * claire, ne pas en inventer une (voir plan de refonte visuelle).
 */
private val NofonDarkColors = NofonColors(
    background = Color(0xFF070908),
    surface = Color(0xFF101413),
    surfaceVariant = Color(0xFF0D1110),
    onSurface = Color(0xFFE6EBE8),
    onSurfaceVariant = Color(0xFF7E8985),
    onSurfaceTertiary = Color(0xFF5F6764),
    accent = Color(0xFF6FE3B4),
    onAccent = Color(0xFF07110D),
    // Nouvel objectif lancé (fil social, demande explicite) — seul usage du bleu dans
    // la palette, distinct de l'accent (vert, réservé au succès/à l'action principale).
    info = Color(0xFF5B8DEF),
    success = Color(0xFF66BB6A),
    alert = Color(0xFFC08A45),
    alertSurface = Color(0xFF20180E),
    error = Color(0xFFC0574C),
    divider = Color(0x14FFFFFF), // rgba(255,255,255,.08)
    navBarBackground = Color(0xFF0B0F0E),
    navInactive = Color(0xFF6E7773),
    dayMissed = Color(0xFF454B48),
    dayProvisional = Color(0xFF2F3A36),
    dayUpcoming = Color(0xFF1C2220)
)

private val LocalNofonColors = staticCompositionLocalOf { NofonDarkColors }

/** Point d'accès aux tokens Nofon dans les composables, ex. `NofonTheme.colors.accent`. */
object NofonTheme {
    val colors: NofonColors
        @Composable get() = LocalNofonColors.current
}

@Composable
fun NofonTheme(content: @Composable () -> Unit) {
    val tokens = NofonDarkColors
    val materialColorScheme = darkColorScheme(
        primary = tokens.accent,
        onPrimary = tokens.onAccent,
        background = tokens.background,
        onBackground = tokens.onSurface,
        surface = tokens.surface,
        onSurface = tokens.onSurface,
        surfaceVariant = tokens.surfaceVariant,
        onSurfaceVariant = tokens.onSurfaceVariant,
        error = tokens.error
    )
    CompositionLocalProvider(LocalNofonColors provides tokens) {
        MaterialTheme(colorScheme = materialColorScheme, content = content)
    }
}
