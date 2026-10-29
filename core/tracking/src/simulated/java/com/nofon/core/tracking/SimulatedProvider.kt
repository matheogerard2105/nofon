package com.nofon.core.tracking

import android.content.Intent
import com.nofon.core.tracking.domain.PermissionState
import com.nofon.core.tracking.domain.RawEventType
import com.nofon.core.tracking.domain.RawUsageEvent
import javax.inject.Inject
import kotlin.random.Random

/**
 * Implémentation simulée de [TrackingProvider] : génère un flux d'événements
 * synthétique mais réaliste (déverrouillages, sessions d'app, écran éteint) au lieu
 * d'interroger `UsageStatsManager`. Sert à développer et tester tout le pipeline en
 * aval (reconstruction, agrégation, `WorkManager`) sans permission ni appareil réel.
 *
 * Compilée uniquement dans le flavor `simulated` — jamais dans le binaire de
 * production (`ARCHITECTURE-2.md` §9).
 *
 * Déterministe par fenêtre demandée (graine dérivée de `beginMillis`/`endMillis`) :
 * deux appels sur la même fenêtre produisent le même flux.
 */
class SimulatedProvider @Inject constructor() : TrackingProvider {

    override fun permissionState(): PermissionState = PermissionState(
        usageAccessGranted = true,
        checkedAtMillis = System.currentTimeMillis()
    )

    override fun hasUsageAccess(): Boolean = true

    override fun usageAccessSettingsIntent(): Intent = Intent()

    override fun queryEvents(beginMillis: Long, endMillis: Long): TrackingProvider.QueryOutcome =
        TrackingProvider.QueryOutcome.Success(generateEvents(beginMillis, endMillis))

    override fun launchableApps(): List<TrackingProvider.InstalledApp> =
        SIMULATED_APPS.map { (pkg, label) -> TrackingProvider.InstalledApp(pkg, label) }

    override fun labelFor(packageName: String): String =
        SIMULATED_APPS.firstOrNull { it.first == packageName }?.second ?: packageName

    private fun generateEvents(beginMillis: Long, endMillis: Long): List<RawUsageEvent> {
        if (endMillis <= beginMillis) return emptyList()
        val random = Random(beginMillis xor endMillis)
        val events = mutableListOf<RawUsageEvent>()
        var cursor = beginMillis

        while (cursor < endMillis) {
            // Silence avant le prochain déverrouillage : 5 à 90 minutes.
            cursor += (5 + random.nextInt(86)) * MINUTE
            if (cursor >= endMillis) break

            events += RawUsageEvent(cursor, RawEventType.SCREEN_INTERACTIVE, null, 15)
            events += RawUsageEvent(cursor, RawEventType.KEYGUARD_HIDDEN, null, 18)

            val sessionCount = 1 + random.nextInt(3)
            repeat(sessionCount) {
                if (cursor >= endMillis) return@repeat
                val (pkg, _) = SIMULATED_APPS[random.nextInt(SIMULATED_APPS.size)]
                val durationMillis = (30 + random.nextInt(600)) * 1_000L
                events += RawUsageEvent(cursor, RawEventType.ACTIVITY_RESUMED, pkg, 1)
                cursor = (cursor + durationMillis).coerceAtMost(endMillis)
                events += RawUsageEvent(cursor, RawEventType.ACTIVITY_PAUSED, pkg, 2)
            }

            events += RawUsageEvent(cursor, RawEventType.KEYGUARD_SHOWN, null, 17)
            events += RawUsageEvent(cursor, RawEventType.SCREEN_NON_INTERACTIVE, null, 16)
        }

        return events.filter { it.timestampMillis in beginMillis until endMillis }
    }

    private companion object {
        const val MINUTE = 60_000L
        val SIMULATED_APPS = listOf(
            "com.nofon.simulated.chat" to "Chat (simulé)",
            "com.nofon.simulated.video" to "Vidéo (simulée)",
            "com.nofon.simulated.news" to "Actualités (simulées)"
        )
    }
}
