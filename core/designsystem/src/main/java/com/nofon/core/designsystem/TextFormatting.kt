package com.nofon.core.designsystem

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Première lettre en majuscule, le reste en minuscule (demande explicite) — affichage
 * seul, jamais la valeur stockée/éditée (`users.handle`). Partagé entre tous les
 * endroits où un pseudo apparaît : profil, abonnés/abonnements, recherche, fil,
 * commentaires.
 */
fun String.capitalizedHandle(): String =
    if (isEmpty()) this else this[0].uppercaseChar() + substring(1).lowercase()

private val relativeTimeDateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

/**
 * "il y a X min/h/j" pour un `timestamptz` Supabase (`created_at`), au-delà de 6 jours
 * bascule sur une date courte plutôt qu'un compteur de jours qui grandit sans fin.
 * `isoInstant` mal formé (jamais censé arriver, mais pas de valeur serveur digne de
 * confiance à 100 %) : chaîne vide plutôt qu'un crash d'affichage.
 */
fun relativeTimeLabel(isoInstant: String, nowMillis: Long = System.currentTimeMillis()): String {
    val then = runCatching { Instant.parse(isoInstant) }.getOrNull() ?: return ""
    val seconds = (nowMillis - then.toEpochMilli()) / 1000
    return when {
        seconds < 60 -> "à l'instant"
        seconds < 3_600 -> "il y a ${seconds / 60} min"
        seconds < 86_400 -> "il y a ${seconds / 3_600} h"
        seconds < 6 * 86_400 -> "il y a ${seconds / 86_400} j"
        else -> then.atZone(ZoneId.systemDefault()).format(relativeTimeDateFormatter)
    }
}
