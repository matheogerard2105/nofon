package com.nofon.core.tracking.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Troisième itération du spike (2026-08-22 après-midi) : le relevé réel montre que
 * les 16 minutes YouTube manquantes venaient presque intégralement d'une session
 * commencée la veille à 23:50 et terminée aujourd'hui à 00:15:53 — invisible pour
 * nous parce que notre fenêtre "aujourd'hui" commence à minuit et ne voit qu'un
 * ACTIVITY_PAUSED orphelin, sans le RESUMED d'avant minuit qui l'explique.
 *
 * `dayStart` = minuit local du jour testé. Les événements "lookback" sont tous
 * strictement antérieurs à `dayStart` ; les événements "du jour" sont à `dayStart` ou
 * après. Le principe du correctif : simuler l'état (package ouvert, écran, keyguard)
 * à partir du lookback pour savoir ce qui était réellement au premier plan à
 * `dayStart`, MAIS ne jamais compter, comme anomalie ou comme session, quoi que ce
 * soit dont l'événement déclencheur est antérieur à `dayStart` — seul l'état "porté"
 * à la frontière compte, jamais une supposition arbitraire sur un PAUSED orphelin.
 */
class MidnightBoundaryTest {

    private val reconstructor = SessionReconstructor()
    private val minute = 60_000L
    private val hour = 3_600_000L

    // Minuit local du jour testé (valeur arbitraire, alignée sur une frontière ronde).
    private val dayStart = 1_755_648_000_000L // 2026-08-20T00:00:00Z, pour l'exemple

    @Test
    fun `session commencee la veille a 23h50 et fermee a 00h15 compte exactement 15 min aujourd hui`() {
        val lookback = listOf(
            RawUsageEvent(dayStart - 10 * minute, RawEventType.ACTIVITY_RESUMED, "com.youtube", 1, null)
        )
        val today = listOf(
            RawUsageEvent(dayStart + 15 * minute, RawEventType.ACTIVITY_PAUSED, "com.youtube", 2, null)
        )
        val r = reconstructor.reconstruct(today, dayStart, dayStart + hour, lookbackEvents = lookback)

        val session = r.sessions.single()
        assertEquals("com.youtube", session.packageName)
        assertEquals(dayStart, session.startMillis)
        assertEquals(15 * minute, session.durationMillis)
        assertEquals(EndCause.PAUSED, session.endCause)
    }

    @Test
    fun `ecran eteint avant minuit ne laisse rien a compter apres minuit`() {
        val lookback = listOf(
            RawUsageEvent(dayStart - 20 * minute, RawEventType.ACTIVITY_RESUMED, "com.chrome", 1, null),
            RawUsageEvent(dayStart - 15 * minute, RawEventType.SCREEN_NON_INTERACTIVE, null, 16, null)
        )
        val today = listOf(
            RawUsageEvent(dayStart + 5 * minute, RawEventType.SCREEN_INTERACTIVE, null, 15, null)
        )
        val r = reconstructor.reconstruct(today, dayStart, dayStart + hour, lookbackEvents = lookback)

        assertTrue("aucune session ne doit apparaître : tout était déjà fermé avant minuit", r.sessions.isEmpty())
    }

    @Test
    fun `seule l app reellement active a minuit se poursuit apres un changement avant minuit`() {
        val lookback = listOf(
            RawUsageEvent(dayStart - 60 * minute, RawEventType.ACTIVITY_RESUMED, "com.a", 1, null),
            RawUsageEvent(dayStart - 10 * minute, RawEventType.ACTIVITY_RESUMED, "com.b", 1, null)
        )
        val today = listOf(
            RawUsageEvent(dayStart + 10 * minute, RawEventType.ACTIVITY_PAUSED, "com.b", 2, null)
        )
        val r = reconstructor.reconstruct(today, dayStart, dayStart + hour, lookbackEvents = lookback)

        val session = r.sessions.single()
        assertEquals("com.b", session.packageName)
        assertEquals(dayStart, session.startMillis)
        assertEquals(10 * minute, session.durationMillis)
        // com.a a été remplacé par com.b avant minuit : rien à son sujet aujourd'hui.
        assertTrue(r.sessions.none { it.packageName == "com.a" })
    }

    @Test
    fun `session a cheval sur minuit puis changement d activite interne du meme paquet`() {
        // Reproduit le motif Chrome réel : ChromeLauncherActivity -> CustomTabActivity
        // (même paquet, RESUMED sans fin intermédiaire -> transition interne, pas de
        // fragmentation), puis STOPPED tardif de l'ANCIENNE Activity (déjà remplacée) :
        // depuis le correctif Activity-aware, ce STOPPED est bénin et ne clôt PAS la
        // session — seul un vrai PAUSED de l'Activity active (CustomTabActivity) le fait.
        val lookback = listOf(
            RawUsageEvent(
                dayStart - 30 * minute, RawEventType.ACTIVITY_RESUMED,
                "com.android.chrome", 1, "com.google.android.apps.chrome.Main.ChromeLauncherActivity"
            )
        )
        val today = listOf(
            RawUsageEvent(
                dayStart + 5 * minute, RawEventType.ACTIVITY_RESUMED,
                "com.android.chrome", 1, "org.chromium.chrome.browser.customtabs.CustomTabActivity"
            ),
            RawUsageEvent(
                dayStart + 20 * minute, RawEventType.ACTIVITY_STOPPED,
                "com.android.chrome", 23, "com.google.android.apps.chrome.Main.ChromeLauncherActivity"
            ),
            RawUsageEvent(
                dayStart + 25 * minute, RawEventType.ACTIVITY_PAUSED,
                "com.android.chrome", 2, "org.chromium.chrome.browser.customtabs.CustomTabActivity"
            )
        )
        val r = reconstructor.reconstruct(today, dayStart, dayStart + hour, lookbackEvents = lookback)

        val session = r.sessions.single()
        assertEquals("com.android.chrome", session.packageName)
        assertEquals(dayStart, session.startMillis)
        // Le STOPPED tardif de ChromeLauncherActivity (+20 min) n'a pas coupé la
        // session : elle continue jusqu'au vrai PAUSED de CustomTabActivity (+25 min).
        assertEquals(25 * minute, session.durationMillis)
        assertEquals(EndCause.PAUSED, session.endCause)
        assertTrue(r.anomalies.any { it.type == AnomalyType.STALE_ACTIVITY_END })
        assertTrue(r.anomalies.none { it.type == AnomalyType.DUPLICATE_RESUME })
    }

    @Test
    fun `un evenement de fin entierement anterieur a minuit n emet aucune anomalie aujourd hui`() {
        // Le PAUSED de com.a se produit AVANT minuit : il ne doit générer ni session
        // ni anomalie dans le résultat du jour (seul l'état "porté" à la frontière compte).
        val lookback = listOf(
            RawUsageEvent(dayStart - 40 * minute, RawEventType.ACTIVITY_RESUMED, "com.a", 1, null),
            RawUsageEvent(dayStart - 35 * minute, RawEventType.ACTIVITY_PAUSED, "com.a", 2, null)
        )
        val today = listOf(
            RawUsageEvent(dayStart + 5 * minute, RawEventType.ACTIVITY_RESUMED, "com.b", 1, null),
            RawUsageEvent(dayStart + 10 * minute, RawEventType.ACTIVITY_PAUSED, "com.b", 2, null)
        )
        val r = reconstructor.reconstruct(today, dayStart, dayStart + hour, lookbackEvents = lookback)

        assertEquals(1, r.sessions.size)
        assertEquals("com.b", r.sessions[0].packageName)
        assertTrue(r.anomalies.isEmpty())
    }
}
