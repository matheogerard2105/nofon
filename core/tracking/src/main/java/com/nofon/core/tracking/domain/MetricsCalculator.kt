package com.nofon.core.tracking.domain

/**
 * Agrège des sessions reconstruites en métriques journalières, et sonde la
 * profondeur d'historique réellement disponible.
 *
 * Pur, sans dépendance Android. Les frontières de journée sont fournies par
 * l'appelant : le domaine ne décide jamais lui-même d'un fuseau horaire
 * (PRODUCT_SPEC §3.5 — le fuseau est une donnée de l'objectif, pas du calcul).
 */
class MetricsCalculator {

    fun computeDay(
        result: ReconstructionResult,
        windowStartMillis: Long,
        windowEndMillis: Long,
        localDateLabel: String,
        excludedPackages: Set<String>,
        systemAggregates: Map<String, SystemAggregateSample> = emptyMap()
    ): DayMetrics {

        val clipped = result.sessions.mapNotNull { clip(it, windowStartMillis, windowEndMillis) }

        val byPackage = clipped.groupBy { it.packageName }

        val launchesByPackage = mutableMapOf<String, Int>()
        var previous: String? = null
        for (session in clipped.sortedBy { it.startMillis }) {
            if (session.packageName != previous) {
                launchesByPackage[session.packageName] =
                    (launchesByPackage[session.packageName] ?: 0) + 1
            }
            previous = session.packageName
        }

        val perApp = byPackage.map { (pkg, list) ->
            val sample = systemAggregates[pkg]
            AppUsage(
                packageName = pkg,
                foregroundMillis = list.sumOf { it.durationMillis },
                sessionCount = list.size,
                launchCount = launchesByPackage[pkg] ?: 0,
                systemAggregateMillis = sample?.bestForegroundMillis,
                systemSample = sample
            )
        }.sortedByDescending { it.foregroundMillis }

        val totalAll = clipped.sumOf { it.durationMillis }
        val totalNofon = clipped
            .filterNot { excludedPackages.contains(it.packageName) }
            .sumOf { it.durationMillis }

        // Une session tronquée en fin de fenêtre rend le total incertain.
        val truncated = result.anomalies.any { it.type == AnomalyType.UNCLOSED_AT_WINDOW_END }
        val suspicious = result.anomalies.any { it.type == AnomalyType.SUSPICIOUS_DURATION }
        val unknownGap = result.gaps.any { it.cause == GapCause.UNKNOWN }

        val timeQuality = when {
            result.eventCount == 0 -> Quality.UNAVAILABLE
            truncated || suspicious || unknownGap -> Quality.ESTIMATED
            else -> Quality.RECONSTRUCTED
        }
        val timeNote = buildList {
            if (truncated) add("session encore ouverte en fin de fenêtre")
            if (suspicious) add("au moins une session anormalement longue")
            if (unknownGap) add("trou de données sans cause identifiable")
        }.joinToString("; ").ifBlank { null }

        // Les déverrouillages sont comptés directement : aucune reconstruction.
        val unlockQuality = when {
            result.eventCount == 0 -> Quality.UNAVAILABLE
            result.unlockCount == 0 && result.screenWakeCount > 0 -> Quality.UNAVAILABLE
            else -> Quality.MEASURED
        }
        val unlockNote =
            if (result.unlockCount == 0 && result.screenWakeCount > 0)
                "Aucun KEYGUARD_HIDDEN alors que ${result.screenWakeCount} réveils d'écran " +
                    "ont été observés. Cet appareil n'émet probablement pas d'événement de " +
                    "verrouillage — vraisemblablement parce qu'aucun verrouillage sécurisé n'est actif."
            else null

        return DayMetrics(
            localDateLabel = localDateLabel,
            windowStartMillis = windowStartMillis,
            windowEndMillis = windowEndMillis,
            nofonScreenTime = Measure(totalNofon, timeQuality, timeNote),
            totalForegroundTime = Measure(totalAll, timeQuality, timeNote),
            unlocks = Measure(result.unlockCount, unlockQuality, unlockNote),
            appLaunches = Measure(
                result.appLaunchCount,
                if (result.eventCount == 0) Quality.UNAVAILABLE else Quality.RECONSTRUCTED,
                "Regroupement anti-rebond appliqué. Brut : ${result.rawResumeCount} ACTIVITY_RESUMED."
            ),
            perApp = perApp,
            anomalies = result.anomalies,
            gaps = result.gaps,
            eventCount = result.eventCount
        )
    }

    private fun clip(session: Session, start: Long, end: Long): Session? {
        val s = maxOf(session.startMillis, start)
        val e = minOf(session.endMillis, end)
        return if (e > s) session.copy(startMillis = s, endMillis = e) else null
    }

    /**
     * Sonde la profondeur d'historique. Alimente TODO-11.
     *
     * `dayWindows` doit contenir, pour chaque journée à évaluer, son étiquette et ses
     * bornes — calculées par l'appelant dans le fuseau voulu. `nowMillis` sert à ne
     * jamais juger une journée en cours ("aujourd'hui") sur des heures qui n'ont pas
     * encore eu lieu.
     *
     * Corrige l'incohérence constatée le 2026-08-22 entre le rapport du jour (qui
     * reconnaissait correctement le trou nocturne 05:19-08:19 comme verrouillage) et
     * cette sonde, qui remontait la même journée comme un trou de 669 min
     * « inexploitable » — pour deux raisons distinctes, désormais corrigées :
     * 1. cette sonde n'avait aucune connaissance de l'état écran/keyguard ; elle
     *    utilise maintenant la même règle que `SessionReconstructor` (`LockState.kt`) ;
     * 2. elle évaluait "aujourd'hui" jusqu'à 23:59:59 même quand l'heure actuelle
     *    n'avait pas encore atteint ce point — la plage d'éveil est maintenant bornée
     *    par `nowMillis` en plus de la fin théorique de journée.
     */
    fun probeHistory(
        events: List<RawUsageEvent>,
        requestedFromMillis: Long,
        dayWindows: List<Triple<String, Long, Long>>,
        nowMillis: Long,
        wakingStartOffsetMillis: Long = 8 * 3_600_000L,
        wakingEndOffsetMillis: Long = 23 * 3_600_000L,
        maxAcceptableGapMillis: Long = 4 * 3_600_000L,
        minEventsPerDay: Int = 20
    ): HistoryProbe {

        val sorted = events.sortedBy { it.timestampMillis }
        val lockTimeline = buildLockStateTimeline(sorted)

        val earliestByType = mutableMapOf<RawEventType, Long>()
        for (e in sorted) {
            val current = earliestByType[e.type]
            if (current == null || e.timestampMillis < current) {
                earliestByType[e.type] = e.timestampMillis
            }
        }

        val days = dayWindows.map { (label, dayStart, dayEnd) ->
            val inDay = sorted.filter { it.timestampMillis in dayStart..dayEnd }

            // On n'évalue la continuité que sur la plage d'éveil : une absence
            // d'événements à 4 h du matin est normale et ne doit pas disqualifier la
            // journée. Bornée aussi par `nowMillis` : une journée en cours n'est jamais
            // jugée sur des heures futures.
            val wakingStart = dayStart + wakingStartOffsetMillis
            val wakingEnd = minOf(dayStart + wakingEndOffsetMillis, dayEnd, nowMillis)

            if (wakingEnd <= wakingStart) {
                DayCompleteness(
                    localDateLabel = label,
                    eventCount = inDay.size,
                    largestGapMillis = 0L,
                    isComplete = false,
                    reason = "Plage d'éveil pas encore atteinte aujourd'hui"
                )
            } else {
                val waking = inDay.filter { it.timestampMillis in wakingStart..wakingEnd }

                // Segments à examiner : bord -> premier événement, événement -> événement
                // suivant, dernier événement -> bord. Chacun ne compte contre la
                // continuité que s'il n'est PAS intégralement expliqué par un état
                // verrouillé/écran éteint en vigueur à son début (même règle que
                // SessionReconstructor, cf. LockState.kt).
                val spanStarts = buildList {
                    add(wakingStart)
                    waking.forEach { add(it.timestampMillis) }
                }
                val spanEnds = buildList {
                    waking.forEach { add(it.timestampMillis) }
                    add(wakingEnd)
                }

                var largestUnexplainedGap = 0L
                for (i in spanStarts.indices) {
                    val duration = spanEnds[i] - spanStarts[i]
                    if (duration <= 0L) continue
                    val explained = isLockedAt(lockTimeline, spanStarts[i])
                    if (!explained && duration > largestUnexplainedGap) {
                        largestUnexplainedGap = duration
                    }
                }

                val enoughEvents = inDay.size >= minEventsPerDay
                val continuous = largestUnexplainedGap <= maxAcceptableGapMillis
                val complete = enoughEvents && continuous

                DayCompleteness(
                    localDateLabel = label,
                    eventCount = inDay.size,
                    largestGapMillis = largestUnexplainedGap,
                    isComplete = complete,
                    reason = when {
                        complete -> "Exploitable"
                        !enoughEvents && !continuous ->
                            "Trop peu d'événements (${inDay.size}) et trou inexpliqué de " +
                                "${largestUnexplainedGap / 60_000} min"
                        !enoughEvents -> "Trop peu d'événements (${inDay.size})"
                        else -> "Trou inexpliqué de ${largestUnexplainedGap / 60_000} min pendant la plage d'éveil"
                    }
                )
            }
        }

        return HistoryProbe(
            requestedFromMillis = requestedFromMillis,
            firstEventMillis = sorted.firstOrNull()?.timestampMillis,
            lastEventMillis = sorted.lastOrNull()?.timestampMillis,
            totalEvents = sorted.size,
            earliestByType = earliestByType,
            days = days
        )
    }
}
