package com.nofon.core.tracking.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests de la reconstruction de sessions sur flux synthétiques.
 *
 * C'est le seul endroit du spike où une régression est réellement grave : toute la
 * mesure de Nofon repose sur ces règles.
 */
class SessionReconstructorTest {

    private val reconstructor = SessionReconstructor()

    private val t0 = 1_755_000_000_000L // instant arbitraire
    private val minute = 60_000L
    private val hour = 3_600_000L

    private fun ev(offsetMinutes: Long, type: RawEventType, pkg: String? = null) =
        RawUsageEvent(t0 + offsetMinutes * minute, type, pkg, 0)

    private fun run(events: List<RawUsageEvent>, windowMinutes: Long = 600) =
        reconstructor.reconstruct(events, t0, t0 + windowMinutes * minute)

    // ------------------------------------------------------------ cas nominaux

    @Test
    fun `session normale ouverte puis fermee`() {
        val r = run(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(40, RawEventType.ACTIVITY_PAUSED, "com.a")
            )
        )
        assertEquals(1, r.sessions.size)
        assertEquals(30 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.PAUSED, r.sessions[0].endCause)
        assertTrue(r.anomalies.isEmpty())
    }

    @Test
    fun `changement d application sans evenement de fin`() {
        val r = run(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(25, RawEventType.ACTIVITY_RESUMED, "com.b"),
                ev(45, RawEventType.ACTIVITY_PAUSED, "com.b")
            )
        )
        assertEquals(2, r.sessions.size)
        assertEquals("com.a", r.sessions[0].packageName)
        assertEquals(15 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.REPLACED, r.sessions[0].endCause)
        assertEquals(20 * minute, r.sessions[1].durationMillis)
    }

    // ------------------------------------------- clôture par écran / verrouillage

    @Test
    fun `extinction de l ecran ferme la session ouverte`() {
        val r = run(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(30, RawEventType.SCREEN_NON_INTERACTIVE)
            )
        )
        assertEquals(1, r.sessions.size)
        assertEquals(20 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.SCREEN_OFF, r.sessions[0].endCause)
    }

    @Test
    fun `verrouillage pendant une session la ferme`() {
        val r = run(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(22, RawEventType.KEYGUARD_SHOWN)
            )
        )
        assertEquals(EndCause.KEYGUARD, r.sessions[0].endCause)
        assertEquals(12 * minute, r.sessions[0].durationMillis)
    }

    @Test
    fun `une nuit sans evenement de fin ne compte pas comme temps d ecran`() {
        // Le bug classique : app au premier plan, écran éteint, aucun ACTIVITY_PAUSED,
        // puis reprise 8 heures plus tard.
        val r = reconstructor.reconstruct(
            listOf(
                ev(0, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(5, RawEventType.SCREEN_NON_INTERACTIVE),
                ev(8 * 60, RawEventType.SCREEN_INTERACTIVE),
                ev(8 * 60 + 1, RawEventType.KEYGUARD_HIDDEN),
                ev(8 * 60 + 2, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(8 * 60 + 12, RawEventType.ACTIVITY_PAUSED, "com.a")
            ),
            t0, t0 + 10 * hour
        )
        val total = r.sessions.sumOf { it.durationMillis }
        assertEquals(15 * minute, total) // 5 min avant l'extinction + 10 min après
        assertEquals(1, r.unlockCount)
    }

    // ------------------------------------------------------ session non terminée

    @Test
    fun `session sans fin est tronquee a la fenetre et signalee`() {
        val r = run(listOf(ev(10, RawEventType.ACTIVITY_RESUMED, "com.a")), windowMinutes = 60)
        assertEquals(1, r.sessions.size)
        assertEquals(50 * minute, r.sessions[0].durationMillis)
        assertEquals(EndCause.WINDOW_END, r.sessions[0].endCause)
        assertTrue(r.anomalies.any { it.type == AnomalyType.UNCLOSED_AT_WINDOW_END })
    }

    @Test
    fun `session anormalement longue est signalee`() {
        val r = reconstructor.reconstruct(
            listOf(
                ev(0, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(6 * 60, RawEventType.ACTIVITY_PAUSED, "com.a")
            ),
            t0, t0 + 7 * hour
        )
        assertTrue(r.anomalies.any { it.type == AnomalyType.SUSPICIOUS_DURATION })
    }

    // ---------------------------------------------------------- cas dégradés

    @Test
    fun `evenements dupliques ne creent pas de session fantome`() {
        val r = run(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(12, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(30, RawEventType.ACTIVITY_PAUSED, "com.a")
            )
        )
        assertEquals(1, r.sessions.size)
        assertEquals(20 * minute, r.sessions[0].durationMillis)
        assertTrue(r.anomalies.any { it.type == AnomalyType.DUPLICATE_RESUME })
    }

    @Test
    fun `fin orpheline est signalee sans corrompre la session en cours`() {
        val r = run(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(15, RawEventType.ACTIVITY_PAUSED, "com.autre"),
                ev(30, RawEventType.ACTIVITY_PAUSED, "com.a")
            )
        )
        assertEquals(1, r.sessions.size)
        assertEquals(20 * minute, r.sessions[0].durationMillis)
        assertTrue(r.anomalies.any { it.type == AnomalyType.ORPHAN_END })
    }

    @Test
    fun `flux non trie est trie et signale`() {
        val r = run(
            listOf(
                ev(30, RawEventType.ACTIVITY_PAUSED, "com.a"),
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a")
            )
        )
        assertEquals(1, r.sessions.size)
        assertEquals(20 * minute, r.sessions[0].durationMillis)
        assertTrue(r.anomalies.any { it.type == AnomalyType.OUT_OF_ORDER })
    }

    @Test
    fun `resume sans package est signale et ignore`() {
        val r = run(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, null),
                ev(20, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(30, RawEventType.ACTIVITY_PAUSED, "com.a")
            )
        )
        assertEquals(1, r.sessions.size)
        assertTrue(r.anomalies.any { it.type == AnomalyType.MISSING_PACKAGE })
    }

    // ------------------------------------------------------- extinction / trous

    @Test
    fun `extinction de l appareil ferme la session et est tracee`() {
        val r = reconstructor.reconstruct(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(20, RawEventType.DEVICE_SHUTDOWN),
                ev(5 * 60, RawEventType.DEVICE_STARTUP)
            ),
            t0, t0 + 6 * hour
        )
        assertEquals(1, r.sessions.size)
        assertEquals(EndCause.SHUTDOWN, r.sessions[0].endCause)
        assertEquals(1, r.shutdownEvents.size)
        assertEquals(1, r.startupEvents.size)
        assertTrue(r.gaps.any { it.cause == GapCause.DEVICE_OFF })
    }

    @Test
    fun `trou apres verrouillage est attribue a l inactivite`() {
        val r = reconstructor.reconstruct(
            listOf(
                ev(10, RawEventType.KEYGUARD_SHOWN),
                ev(6 * 60, RawEventType.KEYGUARD_HIDDEN)
            ),
            t0, t0 + 7 * hour
        )
        assertTrue(r.gaps.any { it.cause == GapCause.LIKELY_IDLE_LOCKED })
    }

    @Test
    fun `un reveil d ecran sans deverrouillage ne rouvre pas la session precedente`() {
        val r = reconstructor.reconstruct(
            listOf(
                ev(10, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(20, RawEventType.KEYGUARD_SHOWN),
                ev(20, RawEventType.SCREEN_NON_INTERACTIVE),
                // réveil d'écran sans déverrouillage : l'app affichée avant le
                // verrouillage réapparaît dans le flux sans action réelle
                ev(60, RawEventType.SCREEN_INTERACTIVE),
                ev(61, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(65, RawEventType.SCREEN_NON_INTERACTIVE),
                ev(6 * 60, RawEventType.SCREEN_INTERACTIVE),
                ev(6 * 60 + 1, RawEventType.KEYGUARD_HIDDEN),
                ev(6 * 60 + 2, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(6 * 60 + 10, RawEventType.ACTIVITY_PAUSED, "com.a")
            ),
            t0, t0 + 7 * hour
        )
        // Seule la session avant verrouillage et celle après déverrouillage réel comptent.
        assertEquals(2, r.sessions.size)
        assertEquals(10 * minute, r.sessions[0].durationMillis)
        assertEquals(8 * minute, r.sessions[1].durationMillis)
        assertTrue(r.anomalies.any { it.type == AnomalyType.RESUMED_WHILE_LOCKED })
        assertEquals(1, r.unlockCount)
    }

    @Test
    fun `deverrouillage direct dans une app au meme horodatage que le resume est bien compte`() {
        // KEYGUARD_HIDDEN et l'ACTIVITY_RESUMED qu'il déclenche arrivent au MÊME
        // horodatage, avec le RESUMED placé AVANT dans le flux (ordre non garanti côté
        // Android) — bug réel observé le 2026-09-07 (Horloge/chronomètre ouvert en
        // déverrouillant directement dedans) : sans tri par priorité, la garde
        // RESUMED_WHILE_LOCKED voit encore keyguardShown=true et rejette la session.
        val r = reconstructor.reconstruct(
            listOf(
                ev(20, RawEventType.KEYGUARD_SHOWN),
                ev(20, RawEventType.SCREEN_NON_INTERACTIVE),
                ev(30, RawEventType.SCREEN_INTERACTIVE),
                ev(30, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(30, RawEventType.KEYGUARD_HIDDEN),
                ev(40, RawEventType.ACTIVITY_PAUSED, "com.a")
            ),
            t0, t0 + 7 * hour
        )
        assertEquals(1, r.sessions.size)
        assertEquals(10 * minute, r.sessions[0].durationMillis)
        assertTrue(r.anomalies.none { it.type == AnomalyType.RESUMED_WHILE_LOCKED })
        assertEquals(1, r.unlockCount)
    }

    @Test
    fun `deverrouillage direct dans une app avec quelques ms d ecart avant KEYGUARD_HIDDEN est confirme`() {
        // Cas réel observé le 2026-09-07 (Horloge/chronomètre) : l'écart entre le RESUMED
        // et le KEYGUARD_HIDDEN qu'il déclenche n'est pas forcément nul, juste petit —
        // un simple tri par priorité à horodatage égal ne suffit pas à lui seul.
        val r = reconstructor.reconstruct(
            listOf(
                RawUsageEvent(t0 + 20_000, RawEventType.KEYGUARD_SHOWN, null, 0),
                RawUsageEvent(t0 + 20_000, RawEventType.SCREEN_NON_INTERACTIVE, null, 0),
                RawUsageEvent(t0 + 30_000, RawEventType.SCREEN_INTERACTIVE, null, 0),
                RawUsageEvent(t0 + 30_050, RawEventType.ACTIVITY_RESUMED, "com.a", 0),
                RawUsageEvent(t0 + 30_800, RawEventType.KEYGUARD_HIDDEN, null, 0),
                RawUsageEvent(t0 + 40_000, RawEventType.ACTIVITY_PAUSED, "com.a", 0)
            ),
            t0, t0 + 7 * hour
        )
        assertEquals(1, r.sessions.size)
        assertEquals(40_000L - 30_050L, r.sessions[0].durationMillis)
        assertTrue(r.anomalies.none { it.type == AnomalyType.RESUMED_WHILE_LOCKED })
        assertEquals(1, r.unlockCount)
    }

    @Test
    fun `ghost resume jamais confirme par un deverrouillage reste ignore`() {
        // Même écart temporel que le test précédent, mais l'écran se rééteint AVANT tout
        // KEYGUARD_HIDDEN : ce n'était bien qu'un réveil sans déverrouillage.
        val r = reconstructor.reconstruct(
            listOf(
                RawUsageEvent(t0 + 20_000, RawEventType.KEYGUARD_SHOWN, null, 0),
                RawUsageEvent(t0 + 20_000, RawEventType.SCREEN_NON_INTERACTIVE, null, 0),
                RawUsageEvent(t0 + 30_000, RawEventType.SCREEN_INTERACTIVE, null, 0),
                RawUsageEvent(t0 + 30_050, RawEventType.ACTIVITY_RESUMED, "com.a", 0),
                RawUsageEvent(t0 + 30_800, RawEventType.SCREEN_NON_INTERACTIVE, null, 0)
            ),
            t0, t0 + 7 * hour
        )
        assertEquals(0, r.sessions.size)
        assertTrue(r.anomalies.any { it.type == AnomalyType.RESUMED_WHILE_LOCKED })
        assertEquals(0, r.unlockCount)
    }

    @Test
    fun `trou sans cause identifiable est signale comme anomalie`() {
        val r = reconstructor.reconstruct(
            listOf(
                ev(10, RawEventType.ACTIVITY_PAUSED, "com.a"),
                ev(6 * 60, RawEventType.ACTIVITY_RESUMED, "com.b")
            ),
            t0, t0 + 7 * hour
        )
        assertTrue(r.gaps.any { it.cause == GapCause.UNKNOWN })
        assertTrue(r.anomalies.any { it.type == AnomalyType.EVENT_GAP })
    }

    // ------------------------------------------------ déverrouillages et lancements

    @Test
    fun `les deverrouillages comptent les KEYGUARD_HIDDEN et pas les reveils d ecran`() {
        val r = run(
            listOf(
                ev(1, RawEventType.SCREEN_INTERACTIVE),
                ev(2, RawEventType.KEYGUARD_HIDDEN),
                ev(20, RawEventType.SCREEN_INTERACTIVE), // réveil sans déverrouillage
                ev(40, RawEventType.SCREEN_INTERACTIVE),
                ev(41, RawEventType.KEYGUARD_HIDDEN)
            )
        )
        assertEquals(2, r.unlockCount)
        assertEquals(3, r.screenWakeCount)
    }

    @Test
    fun `le rebond ne compte pas deux lancements pour un aller-retour immediat`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.a", 0),
            RawUsageEvent(t0 + 10_000, RawEventType.ACTIVITY_PAUSED, "com.a", 0),
            // reprise 1 s plus tard : sous le seuil anti-rebond de 3 s
            RawUsageEvent(t0 + 11_000, RawEventType.ACTIVITY_RESUMED, "com.a", 0),
            RawUsageEvent(t0 + 20_000, RawEventType.ACTIVITY_PAUSED, "com.a", 0)
        )
        val r = reconstructor.reconstruct(events, t0, t0 + hour)
        assertEquals(2, r.rawResumeCount)
        assertEquals(1, r.appLaunchCount)
    }

    @Test
    fun `une reprise apres un long intervalle compte comme nouveau lancement`() {
        val r = run(
            listOf(
                ev(0, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(5, RawEventType.ACTIVITY_PAUSED, "com.a"),
                ev(60, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(65, RawEventType.ACTIVITY_PAUSED, "com.a")
            )
        )
        assertEquals(2, r.appLaunchCount)
    }

    // ------------------------------------------------- exceptions et minuit

    @Test
    fun `les applications exception sont exclues du temps mais pas des deverrouillages`() {
        val r = run(
            listOf(
                ev(1, RawEventType.KEYGUARD_HIDDEN),
                ev(2, RawEventType.ACTIVITY_RESUMED, "com.maps"),
                ev(32, RawEventType.ACTIVITY_RESUMED, "com.social"),
                ev(52, RawEventType.ACTIVITY_PAUSED, "com.social")
            )
        )
        val metrics = MetricsCalculator().computeDay(
            result = r,
            windowStartMillis = t0,
            windowEndMillis = t0 + 600 * minute,
            localDateLabel = "2026-08-21",
            excludedPackages = setOf("com.maps")
        )
        assertEquals(20 * minute, metrics.nofonScreenTime.value)
        assertEquals(50 * minute, metrics.totalForegroundTime.value)
        // Le déverrouillage reste compté même s'il a mené à une application exception.
        assertEquals(1, metrics.unlocks.value)
    }

    @Test
    fun `une session a cheval sur minuit est decoupee par la fenetre`() {
        val dayEnd = t0 + 600 * minute
        val r = reconstructor.reconstruct(
            listOf(
                ev(590, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(620, RawEventType.ACTIVITY_PAUSED, "com.a")
            ),
            t0, dayEnd
        )
        val metrics = MetricsCalculator().computeDay(
            result = r,
            windowStartMillis = t0,
            windowEndMillis = dayEnd,
            localDateLabel = "2026-08-21",
            excludedPackages = emptySet()
        )
        // Seules les 10 minutes situées avant la frontière comptent pour cette journée.
        assertEquals(10 * minute, metrics.totalForegroundTime.value)
    }

    @Test
    fun `une journee sans evenement est marquee indisponible et non pas zero`() {
        val r = run(emptyList())
        val metrics = MetricsCalculator().computeDay(
            result = r,
            windowStartMillis = t0,
            windowEndMillis = t0 + 600 * minute,
            localDateLabel = "2026-08-21",
            excludedPackages = emptySet()
        )
        assertEquals(Quality.UNAVAILABLE, metrics.nofonScreenTime.quality)
        assertEquals(Quality.UNAVAILABLE, metrics.unlocks.quality)
    }

    @Test
    fun `absence de KEYGUARD_HIDDEN malgre des reveils rend le compteur indisponible`() {
        // Cas d'un téléphone sans verrouillage sécurisé.
        val r = run(
            listOf(
                ev(1, RawEventType.SCREEN_INTERACTIVE),
                ev(2, RawEventType.ACTIVITY_RESUMED, "com.a"),
                ev(20, RawEventType.ACTIVITY_PAUSED, "com.a"),
                ev(21, RawEventType.SCREEN_INTERACTIVE)
            )
        )
        val metrics = MetricsCalculator().computeDay(
            result = r,
            windowStartMillis = t0,
            windowEndMillis = t0 + 600 * minute,
            localDateLabel = "2026-08-21",
            excludedPackages = emptySet()
        )
        assertEquals(Quality.UNAVAILABLE, metrics.unlocks.quality)
        assertTrue(metrics.unlocks.note!!.contains("KEYGUARD_HIDDEN"))
    }

    @Test
    fun `une session tronquee degrade la qualite en estime`() {
        val r = run(listOf(ev(10, RawEventType.ACTIVITY_RESUMED, "com.a")), windowMinutes = 60)
        val metrics = MetricsCalculator().computeDay(
            result = r,
            windowStartMillis = t0,
            windowEndMillis = t0 + 60 * minute,
            localDateLabel = "2026-08-21",
            excludedPackages = emptySet()
        )
        assertEquals(Quality.ESTIMATED, metrics.nofonScreenTime.quality)
        assertFalse(metrics.nofonScreenTime.note.isNullOrBlank())
    }
}
