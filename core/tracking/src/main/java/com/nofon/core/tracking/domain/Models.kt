package com.nofon.core.tracking.domain

/**
 * Modèles du domaine de mesure.
 *
 * Aucune dépendance Android ici : ce fichier et ceux du même package doivent rester
 * purs et testables sur la JVM. C'est la seule partie du spike destinée à migrer
 * dans `:core:tracking` de Nofon.
 */

/** Type d'événement système, découplé des constantes `UsageEvents.Event`. */
enum class RawEventType {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    ACTIVITY_STOPPED,
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP,
    OTHER
}

data class RawUsageEvent(
    val timestampMillis: Long,
    val type: RawEventType,
    val packageName: String?,
    /** Code brut renvoyé par Android, conservé pour le diagnostic. */
    val rawType: Int,
    /**
     * Classe/activité concernée, quand Android la fournit (souvent absente sur les
     * événements système). En dernière position avec valeur par défaut pour ne pas
     * casser les constructions positionnelles existantes (tests notamment).
     */
    val className: String? = null
)

/** Pourquoi une session de premier plan s'est terminée. Sert au diagnostic. */
enum class EndCause {
    /** Événement de fin explicite pour le même package. */
    PAUSED,
    STOPPED,

    /** Une autre application est passée au premier plan sans événement de fin. */
    REPLACED,

    /** Écran éteint / verrouillé / appareil arrêté alors que la session était ouverte. */
    SCREEN_OFF,
    KEYGUARD,
    SHUTDOWN,

    /** Aucune fin trouvée avant la fin de la fenêtre d'analyse. */
    WINDOW_END
}

data class Session(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long,
    val endCause: EndCause
) {
    val durationMillis: Long get() = endMillis - startMillis
}

enum class AnomalyType {
    /** Deux ACTIVITY_RESUMED consécutifs pour le MÊME package ET la MÊME Activity, sans fin intermédiaire. */
    DUPLICATE_RESUME,

    /**
     * Fin tardive (PAUSED/STOPPED) reçue pour une Activity du même package qui n'est
     * plus l'Activity active — ex. STOPPED de `ChromeLauncherActivity` reçu après le
     * RESUMED de `CustomTabActivity`. Bénigne et attendue : ignorée sans effet sur la
     * session en cours. Distincte de `ORPHAN_END`, qui reste réservée aux fins reçues
     * pour un package entièrement différent de celui au premier plan.
     */
    STALE_ACTIVITY_END,

    /** Événement de fin reçu pour un package DIFFÉRENT de celui au premier plan — inexplicable au niveau package. */
    ORPHAN_END,

    /** Événements non triés dans le flux renvoyé par Android. */
    OUT_OF_ORDER,

    /** Session ouverte à la fin de la fenêtre : durée tronquée, valeur incertaine. */
    UNCLOSED_AT_WINDOW_END,

    /** Session anormalement longue : suspicion de fin d'événement manquante. */
    SUSPICIOUS_DURATION,

    /** Une session était ouverte au moment d'un DEVICE_STARTUP : incohérent. */
    SESSION_ACROSS_STARTUP,

    /** Écart inhabituellement long entre deux événements consécutifs. */
    EVENT_GAP,

    /** ACTIVITY_RESUMED sans nom de package. */
    MISSING_PACKAGE,

    /**
     * ACTIVITY_RESUMED reçu alors que l'écran est éteint ou le keyguard affiché.
     * Observé en pratique : un réveil d'écran (notification, lever du téléphone) sans
     * déverrouillage peut faire réapparaître l'app qui était au premier plan avant le
     * verrouillage dans le flux `UsageEvents`, sans action réelle de l'utilisateur.
     * Ignoré — ne doit compter ni comme session de premier plan ni comme lancement,
     * sous peine de gonfler le temps d'écran sans que les déverrouillages (mesurés
     * séparément via KEYGUARD_HIDDEN) ne bougent d'autant.
     */
    RESUMED_WHILE_LOCKED
}

data class Anomaly(
    val type: AnomalyType,
    val atMillis: Long,
    val detail: String
)

/** Trou temporel dans le flux d'événements, avec sa cause si elle est identifiable. */
enum class GapCause {
    /** DEVICE_SHUTDOWN puis DEVICE_STARTUP encadrent le trou : extinction propre. */
    DEVICE_OFF,

    /** Le trou commence par un verrouillage et finit par un déverrouillage. */
    LIKELY_IDLE_LOCKED,

    /** Aucune cause identifiable dans le flux. */
    UNKNOWN
}

data class DataGap(
    val startMillis: Long,
    val endMillis: Long,
    val cause: GapCause
) {
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * Qualité d'une valeur affichée. Reprend le vocabulaire imposé par PRODUCT_SPEC §14 :
 * une valeur estimée ne doit jamais être présentée comme une valeur mesurée.
 */
enum class Quality {
    /** Compté directement à partir d'événements système. */
    MEASURED,

    /** Dérivé d'événements mesurés par reconstruction de sessions. */
    RECONSTRUCTED,

    /** Comporte une part d'inférence ou de troncature. */
    ESTIMATED,

    /** Donnée absente ou insuffisante pour être affichée. */
    UNAVAILABLE
}

data class Measure<T>(val value: T, val quality: Quality, val note: String? = null)

/**
 * Comparatif des différentes sources officielles disponibles via `UsageStatsManager`
 * pour un package sur une fenêtre donnée. Aucune de ces valeurs n'est une source de
 * décision (ARCHITECTURE §2.1) — uniquement du diagnostic, pour comprendre où et
 * pourquoi notre reconstruction diverge de Bien-être numérique.
 */
data class SystemAggregateSample(
    /**
     * `queryUsageStats(INTERVAL_DAILY, begin, end)`, `totalTimeInForeground` sommé.
     * Conservée pour mémoire du piège : les bornes des buckets journaliers internes
     * ne coïncident pas forcément avec notre fenêtre [begin, end), et sommer plusieurs
     * buckets qui la chevauchent peut compter du temps hors fenêtre (surestimation).
     */
    val dailyBucketForegroundMillis: Long,
    /**
     * `queryUsageStats(INTERVAL_BEST, begin, end)`, `totalTimeInForeground`. Intervalle
     * conçu par Android pour épouser au mieux une plage arbitraire — normalement la
     * comparaison la plus honnête avec notre fenêtre du jour.
     */
    val bestForegroundMillis: Long,
    /** `queryAndAggregateUsageStats(begin, end)`, `totalTimeInForeground`. */
    val aggregatedRangeForegroundMillis: Long,
    /**
     * `totalTimeVisible` (API 29+, toujours disponible ici — minSdk 29). Inclut le temps
     * où l'app est visible sans être forcément « resumed » (multi-fenêtre, PIP, aperçus
     * transitoires). Sert à tester l'hypothèse que Bien-être numérique compte le temps
     * visible plutôt que le temps strictement au premier plan.
     */
    val bestVisibleMillis: Long
)

data class AppUsage(
    val packageName: String,
    val foregroundMillis: Long,
    val sessionCount: Int,
    val launchCount: Int,
    /** `SystemAggregateSample.bestForegroundMillis` (INTERVAL_BEST), pour comparaison rapide. */
    val systemAggregateMillis: Long?,
    /** Comparatif complet des sources système pour ce package. */
    val systemSample: SystemAggregateSample? = null
)

data class ReconstructionResult(
    val sessions: List<Session>,
    val anomalies: List<Anomaly>,
    val gaps: List<DataGap>,
    /** KEYGUARD_HIDDEN — définition Nofon d'une « ouverture » (PRODUCT_SPEC §3.1). */
    val unlockCount: Int,
    /** SCREEN_INTERACTIVE — diagnostic : vérifie que KEYGUARD_* est bien émis. */
    val screenWakeCount: Int,
    /** Nombre brut d'ACTIVITY_RESUMED. */
    val rawResumeCount: Int,
    /** Lancements d'applications après regroupement anti-rebond. */
    val appLaunchCount: Int,
    val shutdownEvents: List<Long>,
    val startupEvents: List<Long>,
    val eventCount: Int
)

data class DayMetrics(
    val localDateLabel: String,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    /** Temps d'écran Nofon : sessions hors applications exception. */
    val nofonScreenTime: Measure<Long>,
    /** Temps de premier plan toutes applications confondues. */
    val totalForegroundTime: Measure<Long>,
    val unlocks: Measure<Int>,
    val appLaunches: Measure<Int>,
    val perApp: List<AppUsage>,
    val anomalies: List<Anomaly>,
    val gaps: List<DataGap>,
    val eventCount: Int
)

/** Résultat de la sonde d'historique. Alimente directement TODO-11. */
data class HistoryProbe(
    val requestedFromMillis: Long,
    val firstEventMillis: Long?,
    val lastEventMillis: Long?,
    val totalEvents: Int,
    /** Horodatage du plus ancien événement observé, par type. */
    val earliestByType: Map<RawEventType, Long>,
    val days: List<DayCompleteness>
) {
    val depthDays: Int
        get() = firstEventMillis?.let {
            ((lastEventMillis ?: it) - it) / 86_400_000L
        }?.toInt() ?: 0

    val completeDayCount: Int get() = days.count { it.isComplete }

    /** PRODUCT_SPEC §7.3 : une baseline exige au moins 3 journées suffisamment complètes. */
    fun baselineFeasible(minDays: Int = 3): Boolean = completeDayCount >= minDays
}

data class DayCompleteness(
    val localDateLabel: String,
    val eventCount: Int,
    /**
     * Plus grand trou de la plage d'éveil NON expliqué par un état verrouillé/écran
     * éteint (cf. `LockState.kt`). Un long silence pendant que le téléphone est
     * effectivement verrouillé n'est pas compté ici, contrairement à un silence sans
     * explication.
     */
    val largestGapMillis: Long,
    val isComplete: Boolean,
    val reason: String
)

data class PermissionState(
    val usageAccessGranted: Boolean,
    val checkedAtMillis: Long
)
