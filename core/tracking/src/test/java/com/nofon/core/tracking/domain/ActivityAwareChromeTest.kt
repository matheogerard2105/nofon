package com.nofon.core.tracking.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quatrième itération du spike (2026-08-22) : Chrome restait à 0 min reconstruit
 * alors que le journal brut réel permettait d'expliquer environ 12 min 40 s d'usage
 * continu. Séquence EXACTE observée sur le Samsung, avec les vrais noms de classe :
 *
 * ```
 * 09:19:39.510 RESUMED ChromeLauncherActivity
 * 09:19:40.662 PAUSED  ChromeLauncherActivity
 * 09:19:40.685 RESUMED CustomTabActivity
 * 09:19:44.268 STOPPED ChromeLauncherActivity   <- tardif, appartient à l'ANCIENNE Activity
 * 09:32:22.xxx SCREEN_NON_INTERACTIVE
 * 09:32:23.xxx PAUSED  CustomTabActivity
 * ```
 *
 * Bug confirmé par ce test AVANT correctif : le suivi ne connaissait que le package
 * (`openPackage`), pas l'Activity. Le STOPPED tardif de `ChromeLauncherActivity` à
 * 09:19:44.268 matchait `openPackage == "com.android.chrome"` et fermait à tort la
 * session — alors que `CustomTabActivity` était l'Activity réellement active depuis
 * 09:19:40.685. Résultat : ~3,6 s comptées au lieu d'environ 12 min 42 s.
 */
class ActivityAwareChromeTest {

    private val reconstructor = SessionReconstructor()

    companion object {
        private const val CHROME = "com.android.chrome"
        private const val LAUNCHER = "com.google.android.apps.chrome.Main.ChromeLauncherActivity"
        private const val CUSTOM_TAB = "org.chromium.chrome.browser.customtabs.CustomTabActivity"
    }

    // Instants exacts du relevé réel, en millisecondes depuis une origine arbitraire.
    private val t0 = 1_755_678_000_000L // sert de "09:19:39.510" pour ce test
    private fun at(hh: Int, mm: Int, ss: Int, ms: Int): Long {
        // Construit un timestamp relatif à t0 = 09:19:39.510, uniquement à partir d'un
        // offset en millisecondes pour rester lisible et éviter toute dépendance à un
        // fuseau horaire réel.
        val targetMillisOfDay = ((hh * 3600 + mm * 60 + ss) * 1000L) + ms
        val originMillisOfDay = ((9 * 3600 + 19 * 60 + 39) * 1000L) + 510
        return t0 + (targetMillisOfDay - originMillisOfDay)
    }

    @Test
    fun `sequence Chrome reelle reconstruit environ 12 min 42 s au lieu de quelques secondes`() {
        val events = listOf(
            RawUsageEvent(at(9, 19, 39, 510), RawEventType.ACTIVITY_RESUMED, CHROME, 1, LAUNCHER),
            RawUsageEvent(at(9, 19, 40, 662), RawEventType.ACTIVITY_PAUSED, CHROME, 2, LAUNCHER),
            RawUsageEvent(at(9, 19, 40, 685), RawEventType.ACTIVITY_RESUMED, CHROME, 1, CUSTOM_TAB),
            RawUsageEvent(at(9, 19, 44, 268), RawEventType.ACTIVITY_STOPPED, CHROME, 23, LAUNCHER),
            RawUsageEvent(at(9, 32, 22, 0), RawEventType.SCREEN_NON_INTERACTIVE, null, 16, null),
            RawUsageEvent(at(9, 32, 23, 0), RawEventType.ACTIVITY_PAUSED, CHROME, 2, CUSTOM_TAB)
        )

        val r = reconstructor.reconstruct(events, t0, at(10, 0, 0, 0))

        val chromeTotal = r.sessions.filter { it.packageName == CHROME }.sumOf { it.durationMillis }
        val expected = at(9, 32, 22, 0) - at(9, 19, 39, 510)

        // Tolérance de 2 s pour l'arithmétique des timestamps de test.
        assertTrue(
            "temps Chrome reconstruit ($chromeTotal ms) doit être proche de $expected ms",
            kotlin.math.abs(chromeTotal - expected) < 2_000L
        )
        // Preuve directe que le bug est corrigé : le total est de l'ordre de 12-13 min,
        // pas de quelques secondes.
        assertTrue(chromeTotal > 12 * 60_000L)
    }

    @Test
    fun `le STOPPED tardif de l ancienne Activity n est jamais lu comme une vraie anomalie orpheline`() {
        val events = listOf(
            RawUsageEvent(at(9, 19, 39, 510), RawEventType.ACTIVITY_RESUMED, CHROME, 1, LAUNCHER),
            RawUsageEvent(at(9, 19, 40, 662), RawEventType.ACTIVITY_PAUSED, CHROME, 2, LAUNCHER),
            RawUsageEvent(at(9, 19, 40, 685), RawEventType.ACTIVITY_RESUMED, CHROME, 1, CUSTOM_TAB),
            RawUsageEvent(at(9, 19, 44, 268), RawEventType.ACTIVITY_STOPPED, CHROME, 23, LAUNCHER),
            RawUsageEvent(at(9, 32, 23, 0), RawEventType.ACTIVITY_PAUSED, CHROME, 2, CUSTOM_TAB)
        )
        val r = reconstructor.reconstruct(events, t0, at(10, 0, 0, 0))

        assertTrue(r.anomalies.none { it.type == AnomalyType.ORPHAN_END })
        assertTrue(r.anomalies.any { it.type == AnomalyType.STALE_ACTIVITY_END })
    }
}
