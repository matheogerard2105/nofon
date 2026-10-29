package com.nofon.core.tracking.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Types dont la présence donne le contexte écran/keyguard autour d'un package ciblé. */
private val CONTEXT_EVENT_TYPES = setOf(
    RawEventType.SCREEN_INTERACTIVE,
    RawEventType.SCREEN_NON_INTERACTIVE,
    RawEventType.KEYGUARD_SHOWN,
    RawEventType.KEYGUARD_HIDDEN,
    RawEventType.DEVICE_SHUTDOWN,
    RawEventType.DEVICE_STARTUP
)

/**
 * Formate une séquence brute d'`UsageEvents` pour diagnostic humain, avec l'état
 * écran/keyguard réellement en vigueur après chaque événement.
 *
 * Fonction pure, testable sur la JVM. Objectif de cette itération : comprendre — avec
 * des données réelles, pas des suppositions — pourquoi les sessions YouTube/Chrome
 * antérieures dans la journée ne sont pas reconstruites alors qu'une session récente
 * et propre l'est correctement (relevé du 2026-08-22 : 190,5 s réelles → ~3 min
 * reconstruites sur le test contrôlé, mais seulement 3 min YouTube / 0 min Chrome sur
 * l'ensemble du jour). Ne participe à aucun calcul de métrique ; outil de lecture,
 * jetable comme `ui/`.
 *
 * @param packageFilter si non nul, seules les lignes de ce package et les lignes de
 *   contexte écran/keyguard/redémarrage sont imprimées — mais l'état écran/keyguard
 *   continue d'être calculé sur la totalité du flux, pas seulement sur les lignes
 *   affichées, pour rester correct.
 */
fun formatRawEventLog(
    events: List<RawUsageEvent>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    packageFilter: String? = null
): String {
    val sorted = events.sortedBy { it.timestampMillis }
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    var screenInteractive = true
    var keyguardShown = false

    val sb = StringBuilder()
    if (packageFilter == null) {
        sb.appendLine("=== JOURNAL BRUT — ${sorted.size} événements ===")
    } else {
        val ownCount = sorted.count { it.packageName == packageFilter }
        val contextCount = sorted.count { it.type in CONTEXT_EVENT_TYPES }
        sb.appendLine("=== JOURNAL BRUT — $packageFilter ===")
        sb.appendLine(
            "$ownCount événement(s) propres au package · $contextCount événement(s) de " +
                "contexte écran/keyguard · ${sorted.size} événements au total sur la fenêtre"
        )
        if (ownCount == 0) {
            sb.appendLine(
                "AUCUN événement ACTIVITY_RESUMED/PAUSED/STOPPED pour ce package exact sur " +
                    "cette fenêtre. Si Bien-être numérique lui attribue du temps, vérifie s'il " +
                    "existe un autre package au nom proche dans le sélecteur d'applications " +
                    "(processus annexe, Custom Tab, etc.) — sinon la fenêtre interrogée ne " +
                    "couvre pas cet usage, ou Android ne l'a pas journalisé."
            )
        }
    }
    sb.appendLine(
        "%-13s  %-24s  %-38s  %-30s  %-6s  %-6s".format(
            "heure", "type (code brut)", "package", "classe", "écran", "keyguard"
        )
    )

    for (e in sorted) {
        when (e.type) {
            RawEventType.SCREEN_INTERACTIVE -> screenInteractive = true
            RawEventType.SCREEN_NON_INTERACTIVE -> screenInteractive = false
            RawEventType.KEYGUARD_SHOWN -> keyguardShown = true
            RawEventType.KEYGUARD_HIDDEN -> keyguardShown = false
            RawEventType.DEVICE_SHUTDOWN -> {
                screenInteractive = false
                keyguardShown = true
            }
            RawEventType.DEVICE_STARTUP -> keyguardShown = true
            else -> Unit
        }

        val included = packageFilter == null ||
            e.packageName == packageFilter ||
            e.type in CONTEXT_EVENT_TYPES
        if (!included) continue

        val time = fmt.format(Instant.ofEpochMilli(e.timestampMillis).atZone(zoneId))
        sb.appendLine(
            "%-13s  %-24s  %-38s  %-30s  %-6s  %-6s".format(
                time,
                "${e.type.name}(${e.rawType})",
                e.packageName ?: "—",
                e.className ?: "—",
                if (screenInteractive) "ON" else "OFF",
                if (keyguardShown) "SHOWN" else "HIDDEN"
            )
        )
    }

    return sb.toString()
}
