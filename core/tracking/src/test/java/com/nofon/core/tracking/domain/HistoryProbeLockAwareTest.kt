package com.nofon.core.tracking.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cinquième itération du spike (2026-08-22) : le rapport du jour reconnaissait
 * correctement le trou nocturne 05:19-08:19 comme `LIKELY_IDLE_LOCKED`, mais la
 * section « historique » de la même sonde classait AUJOURD'HUI comme
 * « INEXPLOITABLE — trou de 669 min pendant la plage d'éveil » : deux verdicts
 * incohérents sur la même donnée, parce que `probeHistory` ignorait complètement
 * l'état écran/keyguard, et parce qu'elle jugeait des heures d'« aujourd'hui » qui
 * n'avaient pas encore eu lieu au moment du calcul.
 *
 * Ces deux causes sont distinctes et testées séparément.
 */
class HistoryProbeLockAwareTest {

    private val metrics = MetricsCalculator()
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    // Minuit local d'un jour PASSÉ et COMPLET (pas "aujourd'hui").
    private val dayStart = 1_755_500_000_000L

    @Test
    fun `un long verrouillage reel pendant la plage d eveil ne rend pas la journee inexploitable`() {
        // Journée par ailleurs bien pourvue en événements (matin et soir), mais avec
        // une longue sieste/verrouillage de 5 h en milieu de journée — dépassant le
        // seuil de 4 h utilisé pour juger la continuité. Le tout encadré par
        // KEYGUARD_SHOWN/KEYGUARD_HIDDEN : un verrouillage réel, pas un problème de
        // mesure.
        val morningEvents = (0 until 15).map { i ->
            RawUsageEvent(dayStart + 9 * hour + i * minute, RawEventType.ACTIVITY_RESUMED, "com.a", 1, null)
        }
        val eveningEvents = (0 until 15).map { i ->
            RawUsageEvent(dayStart + 19 * hour + i * minute, RawEventType.ACTIVITY_RESUMED, "com.a", 1, null)
        }
        val lockEvents = listOf(
            RawUsageEvent(dayStart + 13 * hour, RawEventType.KEYGUARD_SHOWN, null, 17, null),
            RawUsageEvent(dayStart + 18 * hour, RawEventType.KEYGUARD_HIDDEN, null, 18, null)
        )
        val events = morningEvents + lockEvents + eveningEvents

        val probe = metrics.probeHistory(
            events = events,
            requestedFromMillis = dayStart,
            dayWindows = listOf(Triple("2026-08-18", dayStart, dayStart + day - 1)),
            nowMillis = dayStart + day + hour // la journée testée est déjà terminée
        )

        val result = probe.days.single()
        assertTrue(
            "la journée doit être exploitable malgré le verrouillage de 5 h : ${result.reason}",
            result.isComplete
        )
    }

    @Test
    fun `une journee en cours n est jamais jugee sur des heures pas encore arrivees`() {
        // "Aujourd'hui" : la journée nominale va jusqu'à 23:59:59, mais il n'est que
        // 11h20 au moment du calcul. Les heures 11h20-23h59 n'ont, par construction,
        // aucun événement — ce n'est pas un trou de données, la journée n'est
        // simplement pas terminée.
        val todayStart = dayStart + 2 * day
        val now = todayStart + 11 * hour + 20 * minute

        val morningEvents = (0 until 25).map { i ->
            RawUsageEvent(todayStart + 9 * hour + i * minute, RawEventType.ACTIVITY_RESUMED, "com.a", 1, null)
        }

        val probe = metrics.probeHistory(
            events = morningEvents,
            requestedFromMillis = todayStart,
            dayWindows = listOf(Triple("2026-08-20", todayStart, todayStart + day - 1)),
            nowMillis = now
        )

        val result = probe.days.single()
        assertTrue(
            "les heures futures d'aujourd'hui ne doivent pas compter comme un trou : ${result.reason}",
            result.isComplete
        )
    }
}
