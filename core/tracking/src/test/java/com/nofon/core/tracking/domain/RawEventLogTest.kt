package com.nofon.core.tracking.domain

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du journal brut ajouté pour diagnostiquer les ORPHAN_END observés sur
 * Samsung/One UI (relevé réel du 2026-08-22) : timestamp, type, package, classe,
 * état écran/keyguard en vigueur après chaque événement.
 */
class RawEventLogTest {

    private val zone = ZoneId.of("UTC")
    private val t0 = 1_755_000_000_000L
    private val second = 1_000L

    @Test
    fun `le journal inclut timestamp type package classe et etat ecran-keyguard`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.a", 1, "com.a.MainActivity"),
            RawUsageEvent(t0 + second, RawEventType.SCREEN_NON_INTERACTIVE, null, 16, null)
        )
        val log = formatRawEventLog(events, zone)

        assertTrue(log.contains("com.a"))
        assertTrue(log.contains("com.a.MainActivity"))
        assertTrue(log.contains("ACTIVITY_RESUMED(1)"))
        assertTrue(log.contains("SCREEN_NON_INTERACTIVE(16)"))
        // Après le premier événement (RESUMED), l'écran est toujours considéré allumé.
        assertTrue(log.lines().any { it.contains("com.a") && it.contains("ON") })
        // Après SCREEN_NON_INTERACTIVE, l'état passe à OFF sur la ligne correspondante.
        assertTrue(log.lines().any { it.contains("SCREEN_NON_INTERACTIVE") && it.contains("OFF") })
    }

    @Test
    fun `le journal trie les evenements meme si le flux d entree ne l est pas`() {
        val events = listOf(
            RawUsageEvent(t0 + 10 * second, RawEventType.ACTIVITY_PAUSED, "com.b", 2, null),
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.a", 1, null)
        )
        val log = formatRawEventLog(events, zone)
        val indexA = log.indexOf("com.a")
        val indexB = log.indexOf("com.b")
        assertTrue("com.a (plus ancien) doit apparaître avant com.b", indexA in 0 until indexB)
    }

    @Test
    fun `l etat keyguard persiste au travers d evenements sans rapport`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.KEYGUARD_SHOWN, null, 17, null),
            // Un événement sans rapport avec le verrouillage (ex. synchro en arrière-plan)
            // ne doit pas faire perdre l'information "toujours verrouillé".
            RawUsageEvent(t0 + second, RawEventType.OTHER, "com.background.sync", 99, null)
        )
        val log = formatRawEventLog(events, zone)
        val syncLine = log.lines().first { it.contains("com.background.sync") }
        assertTrue(syncLine.contains("SHOWN"))
    }

    @Test
    fun `l entete annonce le nombre d evenements`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.a", 1, null),
            RawUsageEvent(t0 + second, RawEventType.ACTIVITY_PAUSED, "com.a", 2, null)
        )
        val log = formatRawEventLog(events, zone)
        assertEquals("=== JOURNAL BRUT — 2 événements ===", log.lines().first())
    }

    // ------------------------------------------- filtre par package (diagnostic ciblé)

    @Test
    fun `le filtre par package n affiche que ses evenements et le contexte ecran-keyguard`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.youtube", 1, null),
            RawUsageEvent(t0 + second, RawEventType.ACTIVITY_RESUMED, "com.chrome", 1, null),
            RawUsageEvent(t0 + 2 * second, RawEventType.SCREEN_NON_INTERACTIVE, null, 16, null),
            RawUsageEvent(t0 + 3 * second, RawEventType.ACTIVITY_PAUSED, "com.chrome", 2, null)
        )
        val log = formatRawEventLog(events, zone, packageFilter = "com.youtube")

        assertTrue(log.contains("com.youtube"))
        // Le contexte écran/keyguard reste visible même s'il ne concerne pas le package ciblé.
        assertTrue(log.contains("SCREEN_NON_INTERACTIVE"))
        // Les événements d'un AUTRE package sont exclus de l'affichage.
        assertTrue(log.lines().none { it.contains("com.chrome") })
    }

    @Test
    fun `le filtre par package signale explicitement l absence totale d evenements`() {
        val events = listOf(
            RawUsageEvent(t0, RawEventType.ACTIVITY_RESUMED, "com.chrome", 1, null)
        )
        val log = formatRawEventLog(events, zone, packageFilter = "com.youtube")

        assertTrue(log.contains("AUCUN événement"))
        assertTrue(log.contains("0 événement(s) propres au package"))
    }

    @Test
    fun `le filtre par package calcule l etat ecran-keyguard sur la totalite du flux`() {
        // Le keyguard passe SHOWN via un événement lié à un AUTRE package (aucun
        // package sur un événement système), puis youtube reprend : la ligne youtube
        // doit refléter l'état verrouillé même si les événements intermédiaires
        // n'appartiennent pas au package filtré.
        val events = listOf(
            RawUsageEvent(t0, RawEventType.KEYGUARD_SHOWN, null, 17, null),
            RawUsageEvent(t0 + second, RawEventType.ACTIVITY_RESUMED, "com.youtube", 1, null)
        )
        val log = formatRawEventLog(events, zone, packageFilter = "com.youtube")
        // .first{contains("com.youtube")} attraperait la ligne d'en-tête (qui répète le
        // nom du package) : on cible spécifiquement la ligne d'événement RESUMED.
        val youtubeLine = log.lines().first { it.contains("ACTIVITY_RESUMED") }
        assertTrue(youtubeLine.contains("com.youtube"))
        assertTrue(youtubeLine.contains("SHOWN"))
    }
}
