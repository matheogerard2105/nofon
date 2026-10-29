package com.nofon.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Polices Google Fonts chargées via l'API "Downloadable Fonts" (`ui-text-google-fonts`),
 * fournisseur `com.google.android.gms.fonts` — aucune clé API, juste le tableau de
 * certificats standard Google (`res/values/font_certs.xml`, copié tel quel depuis
 * l'exemple officiel Android). Pas de fichiers de police embarqués dans l'app.
 */
private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private fun googleFontFamily(name: String, vararg weights: FontWeight) = FontFamily(
    weights.map { weight -> Font(googleFont = GoogleFont(name), fontProvider = googleFontProvider, weight = weight) }
)

/** Nom de marque uniquement ("NOFON" dans la barre du haut) — jamais pour du texte courant. */
val MichromaFamily = googleFontFamily("Michroma", FontWeight.Normal)

/** Texte d'interface — labels, corps, titres. */
val InstrumentSansFamily = googleFontFamily("Instrument Sans", FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)

/** Tout ce qui se compte ou se mesure : argent, durées, compteurs, dates. */
val IbmPlexMonoFamily = googleFontFamily("IBM Plex Mono", FontWeight.Normal, FontWeight.Medium)

/**
 * Styles nommés du handoff de design (`design_handoff_nofon_accueil/README.md`), utilisés
 * directement plutôt que forcés dans les slots fixes de `MaterialTheme.typography` — le
 * nuancier de la maquette ne correspond pas au découpage sémantique Material3 standard.
 */
object NofonTextStyles {
    val logo = TextStyle(fontFamily = MichromaFamily, fontSize = 13.sp, letterSpacing = 0.16.em)

    val title = TextStyle(
        fontFamily = InstrumentSansFamily,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em
    )

    val heading = TextStyle(fontFamily = InstrumentSansFamily, fontSize = 13.5.sp, fontWeight = FontWeight.Normal)

    val label = TextStyle(
        fontFamily = InstrumentSansFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.em
    )

    val body = TextStyle(fontFamily = InstrumentSansFamily, fontSize = 12.5.sp, lineHeight = 18.75.sp)

    val bodyEmphasis = TextStyle(fontFamily = InstrumentSansFamily, fontSize = 13.5.sp)

    val small = TextStyle(fontFamily = InstrumentSansFamily, fontSize = 11.sp)

    /** Montant/valeur dominante d'un écran (34sp — carte Accueil 1a) ou légèrement réduite (30sp — centre de l'anneau 1b). */
    fun amountLarge(size: TextUnit = 34.sp) = TextStyle(
        fontFamily = IbmPlexMonoFamily,
        fontSize = size,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.01).em
    )

    /** Montants secondaires (relevé 1c : caution sécurisée/en jeu/perdue). */
    val amountMedium = TextStyle(fontFamily = IbmPlexMonoFamily, fontSize = 22.sp, fontWeight = FontWeight.Medium)

    /** Métriques de la grille (temps d'écran, déverrouillages, série). */
    val metricDisplay = TextStyle(fontFamily = IbmPlexMonoFamily, fontSize = 19.sp, fontWeight = FontWeight.Normal)

    val smallMono = TextStyle(fontFamily = IbmPlexMonoFamily, fontSize = 11.5.sp)
}
