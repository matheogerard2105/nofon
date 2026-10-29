package com.nofon.core.tracking.domain

/**
 * Repère chaque changement d'état écran/keyguard dans un flux TRIÉ d'événements, pour
 * pouvoir répondre à « l'appareil était-il verrouillé/éteint à cet instant ? » à un
 * coût logarithmique plutôt que de rejouer tout le flux à chaque question.
 *
 * Règle IDENTIQUE à celle de `SessionReconstructor.reconstruct` (`!screenInteractive
 * || keyguardShown`) — c'est le point important. Avant ce module, `MetricsCalculator
 * .probeHistory` menait sa propre analyse de trous, indépendante et sans aucune
 * connaissance de l'état écran/keyguard, ce qui produisait des verdicts incohérents
 * sur la même donnée : le rapport du jour du 2026-08-22 reconnaissait correctement le
 * trou nocturne 05:19-08:19 comme `LIKELY_IDLE_LOCKED`, tandis que la sonde
 * d'historique remontait la même journée comme un trou de 669 min « inexploitable »,
 * sans savoir que le téléphone était simplement verrouillé. Utiliser cette même règle
 * aux deux endroits élimine la divergence par construction.
 */
data class LockStateTransition(
    val atMillis: Long,
    val screenInteractive: Boolean,
    val keyguardShown: Boolean
) {
    val isLocked: Boolean get() = !screenInteractive || keyguardShown
}

/** Construit la chronologie des changements d'état à partir d'un flux TRIÉ. */
fun buildLockStateTimeline(sortedEvents: List<RawUsageEvent>): List<LockStateTransition> {
    var screenInteractive = true
    var keyguardShown = false
    val timeline = mutableListOf(LockStateTransition(Long.MIN_VALUE, screenInteractive, keyguardShown))

    for (e in sortedEvents) {
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
        val last = timeline.last()
        if (screenInteractive != last.screenInteractive || keyguardShown != last.keyguardShown) {
            timeline += LockStateTransition(e.timestampMillis, screenInteractive, keyguardShown)
        }
    }
    return timeline
}

/** État verrouillé/écran-éteint en vigueur juste avant [atMillis] (borne incluse). */
fun isLockedAt(timeline: List<LockStateTransition>, atMillis: Long): Boolean {
    var lo = 0
    var hi = timeline.size - 1
    var result = timeline[0]
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (timeline[mid].atMillis <= atMillis) {
            result = timeline[mid]
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return result.isLocked
}
