package com.nofon.core.tracking.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deuxième itération du spike (2026-08-22), consacrée au premier relevé réel sur
 * Samsung/One UI : temps reconstruit 5 min contre 40 min Bien-être numérique, un
 * trou de sommeil de 180 min classé cause `UNKNOWN` au lieu de `LIKELY_IDLE_LOCKED`,
 * et 16 anomalies (dont de nombreux ORPHAN_END) sur 430 événements.
 *
 * Ces tests couvrent les deux correctifs apportés :
 * 1. le classement d'un trou suit l'état écran/keyguard EN VIGUEUR, pas le seul
 *    type de l'événement immédiatement adjacent ;
 * 2. deux passages au premier plan du même package séparés par un silence court et
 *    sans autre package résumé entre les deux sont recollés en une session continue.
 */
class SamsungReconstructionTest {

    private val reconstructor = SessionReconstructor()
    private val t0 = 1_755_000_000_000L
    private val second = 1_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    // ------------------------------------------------------- classement des trous

    @Test
    fun `un trou est classe verrouille meme si l evenement adjacent n a aucun rapport avec le verrouillage`() {
        // Reproduit le trou 05h19-08h19 du relevé réel : le téléphone est verrouillé
        // depuis longtemps, un événement sans rapport (ex. synchro en arrière-plan)
        // survient juste avant le silence, puis le silence long, puis le réveil.
        // Avant le correctif, seul le type du DERNIER événement (ici OTHER) était
        // regardé, et la cause retombait à UNKNOWN malgré le verrouillage réel.
        val events = listOf(
            RawUsageEvent(t0, RawEventType.KEYGUARD_SHOWN, null, 17, null),
            RawUsageEvent(t0 + 5 * minute, RawEventType.OTHER, "com.background.sync", 99, null),
            RawUsageEvent(t0 + 5 * minute + 4 * hour, RawEventType.KEYGUARD_HIDDEN, null, 18, null)
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 6 * hour)

        val gap = r.gaps.single()
        assertEquals(GapCause.LIKELY_IDLE_LOCKED, gap.cause)
        // Une cause identifiée ne doit pas être remontée comme anomalie à investiguer.
        assertTrue(r.anomalies.none { it.type == AnomalyType.EVENT_GAP })
    }

    @Test
    fun `un trou reste inconnu quand l ecran n a jamais ete signale eteint ou verrouille`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_PAUSED, "com.a", 2, null),
            RawUsageEvent(t0 + 4 * hour, RawEventType.ACTIVITY_RESUMED, "com.b", 1, null)
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 5 * hour)

        val gap = r.gaps.single()
        assertEquals(GapCause.UNKNOWN, gap.cause)
        assertTrue(r.anomalies.any { it.type == AnomalyType.EVENT_GAP })
    }

    // ------------------------------------------------- fusion des micro-coupures

    @Test
    fun `deux fragments du meme paquet separes par un silence court sont fusionnes`() {
        // Simule une micro-interruption système (geste de navigation, aperçu des
        // apps récentes) qui ferme puis rouvre la même app en quelques secondes,
        // sans qu'aucun autre paquet n'apparaisse entre les deux — le schéma évoqué
        // par le relevé réel où chaque app affichait « 0 min » individuellement bien
        // que le total du jour atteigne 5 min.
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.youtube", 1, null),
            RawUsageEvent(t0 + 118 * second, RawEventType.ACTIVITY_PAUSED, "com.youtube", 2, null),
            RawUsageEvent(t0 + 121 * second, RawEventType.ACTIVITY_RESUMED, "com.youtube", 1, null),
            RawUsageEvent(t0 + 300 * second, RawEventType.ACTIVITY_PAUSED, "com.youtube", 2, null)
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 400 * second)

        assertEquals(1, r.sessions.size)
        assertEquals(300 * second, r.sessions[0].durationMillis)
        assertEquals(EndCause.PAUSED, r.sessions[0].endCause)
    }

    @Test
    fun `un autre paquet reellement resume entre les deux empeche la fusion`() {
        // Si le lanceur (ou tout autre composant) obtient lui aussi un vrai RESUMED
        // entre les deux fragments, ce n'est plus un silence muet : les trois
        // sessions restent distinctes. Ce test documente explicitement la limite du
        // correctif — il ne masque pas le churn ORPHAN_END, seulement les silences.
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.youtube", 1, null),
            RawUsageEvent(t0 + 118 * second, RawEventType.ACTIVITY_PAUSED, "com.youtube", 2, null),
            RawUsageEvent(t0 + 119 * second, RawEventType.ACTIVITY_RESUMED, "com.sec.android.app.launcher", 1, null),
            RawUsageEvent(t0 + 120 * second, RawEventType.ACTIVITY_PAUSED, "com.sec.android.app.launcher", 2, null),
            RawUsageEvent(t0 + 121 * second, RawEventType.ACTIVITY_RESUMED, "com.youtube", 1, null),
            RawUsageEvent(t0 + 300 * second, RawEventType.ACTIVITY_PAUSED, "com.youtube", 2, null)
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 400 * second)

        assertEquals(3, r.sessions.size)
        assertEquals("com.youtube", r.sessions[0].packageName)
        assertEquals("com.sec.android.app.launcher", r.sessions[1].packageName)
        assertEquals("com.youtube", r.sessions[2].packageName)
    }

    @Test
    fun `la fusion ne s applique jamais a travers un ecran eteint meme bref`() {
        // Verrouiller puis déverrouiller très vite sur la même app reste une vraie
        // coupure : ce n'est pas du temps d'écran pendant l'intervalle.
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.chrome", 1, null),
            RawUsageEvent(t0 + 10 * second, RawEventType.SCREEN_NON_INTERACTIVE, null, 16, null),
            RawUsageEvent(t0 + 12 * second, RawEventType.SCREEN_INTERACTIVE, null, 15, null),
            RawUsageEvent(t0 + 12500, RawEventType.KEYGUARD_HIDDEN, null, 18, null),
            RawUsageEvent(t0 + 13 * second, RawEventType.ACTIVITY_RESUMED, "com.chrome", 1, null),
            RawUsageEvent(t0 + 20 * second, RawEventType.ACTIVITY_PAUSED, "com.chrome", 2, null)
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 60 * second)

        assertEquals(2, r.sessions.size)
        assertEquals(EndCause.SCREEN_OFF, r.sessions[0].endCause)
        assertEquals(10 * second, r.sessions[0].durationMillis)
        assertEquals(7 * second, r.sessions[1].durationMillis)
    }

    @Test
    fun `un silence trop long entre deux fragments du meme paquet n est pas fusionne`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.chrome", 1, null),
            RawUsageEvent(t0 + 10 * second, RawEventType.ACTIVITY_PAUSED, "com.chrome", 2, null),
            RawUsageEvent(t0 + 30 * minute, RawEventType.ACTIVITY_RESUMED, "com.chrome", 1, null),
            RawUsageEvent(t0 + 31 * minute, RawEventType.ACTIVITY_PAUSED, "com.chrome", 2, null)
        )
        val r = reconstructor.reconstruct(events, t0, t0 + 40 * minute)

        assertEquals(2, r.sessions.size)
    }
}
