package com.nofon.core.tracking.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Couverture générale du suivi Activity-aware introduit pour corriger le bug Chrome
 * (STOPPED tardif d'une Activity remplacée fermant à tort la nouvelle). Complète
 * `ActivityAwareChromeTest`, qui rejoue la séquence réelle exacte, avec les cas
 * limites explicitement demandés : transition sans pause intermédiaire, fermeture
 * réelle (PAUSED / écran éteint), et changement de package.
 *
 * La métrique reste au niveau package : ces tests vérifient un cycle de vie
 * d'Activity correctement interprété, jamais un décompte séparé par Activity.
 */
class ActivityLifecycleTest {

    private val reconstructor = SessionReconstructor()
    private val t0 = 1_755_680_000_000L
    private val second = 1_000L
    private val minute = 60_000L

    @Test
    fun `transition A vers B du meme paquet sans pause intermediaire conserve la continuite`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.app", 1, "com.app.ActivityA"),
            // Pas de PAUSED entre les deux : RESUMED direct d'une autre Activity du même paquet.
            RawUsageEvent(t0 + 2 * minute, RawEventType.ACTIVITY_RESUMED, "com.app", 1, "com.app.ActivityB"),
            RawUsageEvent(t0 + 5 * minute, RawEventType.ACTIVITY_PAUSED, "com.app", 2, "com.app.ActivityB")
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 10 * minute)

        assertEquals(1, r.sessions.size)
        assertEquals(5 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.PAUSED, r.sessions[0].endCause)
        // Deux Activity DIFFÉRENTES : ce n'est ni une vraie répétition, ni une fin
        // orpheline — aucune anomalie à signaler pour cette transition normale.
        assertTrue(r.anomalies.none { it.type == AnomalyType.DUPLICATE_RESUME })
        assertTrue(r.anomalies.none { it.type == AnomalyType.ORPHAN_END })
        assertTrue(r.anomalies.none { it.type == AnomalyType.STALE_ACTIVITY_END })
    }

    @Test
    fun `STOPPED de l ancienne Activity apres RESUMED de la nouvelle ne ferme pas la nouvelle`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.app", 1, "com.app.ActivityA"),
            RawUsageEvent(t0 + 1 * second, RawEventType.ACTIVITY_RESUMED, "com.app", 1, "com.app.ActivityB"),
            // Fin tardive de A, reçue APRÈS que B soit devenue l'Activity active.
            RawUsageEvent(t0 + 4 * second, RawEventType.ACTIVITY_STOPPED, "com.app", 23, "com.app.ActivityA"),
            RawUsageEvent(t0 + 5 * minute, RawEventType.ACTIVITY_PAUSED, "com.app", 2, "com.app.ActivityB")
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 10 * minute)

        assertEquals(1, r.sessions.size)
        assertEquals(5 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.PAUSED, r.sessions[0].endCause)
        assertTrue(r.anomalies.any { it.type == AnomalyType.STALE_ACTIVITY_END })
    }

    @Test
    fun `un vrai PAUSED de l Activity active ferme bien la session`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.app", 1, "com.app.ActivityA"),
            RawUsageEvent(t0 + 3 * minute, RawEventType.ACTIVITY_PAUSED, "com.app", 2, "com.app.ActivityA")
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 10 * minute)

        assertEquals(1, r.sessions.size)
        assertEquals(3 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.PAUSED, r.sessions[0].endCause)
    }

    @Test
    fun `un ecran eteint ferme bien la session meme au milieu d une Activity connue`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.app", 1, "com.app.ActivityA"),
            RawUsageEvent(t0 + 3 * minute, RawEventType.SCREEN_NON_INTERACTIVE, null, 16, null)
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 10 * minute)

        assertEquals(1, r.sessions.size)
        assertEquals(3 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.SCREEN_OFF, r.sessions[0].endCause)
    }

    @Test
    fun `un changement vers un autre paquet ferme correctement le precedent malgre le suivi par Activity`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.a", 1, "com.a.MainActivity"),
            RawUsageEvent(t0 + 3 * minute, RawEventType.ACTIVITY_RESUMED, "com.b", 1, "com.b.MainActivity"),
            RawUsageEvent(t0 + 5 * minute, RawEventType.ACTIVITY_PAUSED, "com.b", 2, "com.b.MainActivity")
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 10 * minute)

        assertEquals(2, r.sessions.size)
        assertEquals("com.a", r.sessions[0].packageName)
        assertEquals(3 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.REPLACED, r.sessions[0].endCause)
        assertEquals("com.b", r.sessions[1].packageName)
        assertEquals(2 * minute, r.sessions[1].durationMillis)
    }

    @Test
    fun `une fin concernant un autre paquet entier reste une vraie fin orpheline`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.a", 1, "com.a.MainActivity"),
            RawUsageEvent(t0 + 1 * minute, RawEventType.ACTIVITY_PAUSED, "com.autre", 2, "com.autre.MainActivity"),
            RawUsageEvent(t0 + 3 * minute, RawEventType.ACTIVITY_PAUSED, "com.a", 2, "com.a.MainActivity")
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 10 * minute)

        assertEquals(1, r.sessions.size)
        assertEquals(3 * minute, r.sessions[0].durationMillis)
        assertTrue(r.anomalies.any { it.type == AnomalyType.ORPHAN_END })
        assertFalse(r.anomalies.any { it.type == AnomalyType.STALE_ACTIVITY_END })
    }
}
