package com.nofon.core.tracking.domain

/**
 * Reconstruit les sessions de premier plan à partir d'un flux d'événements bruts.
 *
 * Fonction pure : aucune dépendance Android, aucun effet de bord. C'est le composant
 * le plus critique du spike, et celui qui migrera tel quel dans Nofon.
 *
 * Le piège principal traité ici (ARCHITECTURE §2.2) : une application au premier plan
 * quand l'écran s'éteint ne produit pas toujours d'ACTIVITY_PAUSED. Sans clôture
 * explicite sur SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN / DEVICE_SHUTDOWN, une nuit
 * de sommeil est comptée comme du temps d'écran.
 */
class SessionReconstructor(private val config: Config = Config()) {

    data class Config(
        /**
         * Deux passages au premier plan du MÊME package non séparés par un autre
         * package et distants de moins de ce seuil comptent comme une seule session
         * continue (et un seul lancement), pas comme deux fragments avec un trou muet
         * entre les deux.
         *
         * Absorbe les micro-interruptions système (geste de navigation, aperçu des
         * apps récentes, tiroir de notifications, clavier) que certains constructeurs
         * — Samsung/One UI en particulier — intercalent entre deux RESUMED du même
         * package sans que l'app quitte réellement l'écran. Observé sur le premier
         * relevé réel (2026-08-22) : temps reconstruit 5 min contre 40 min Bien-être
         * numérique, alors que chaque app listée individuellement affichait "0 min" —
         * signe de fragmentation en de nombreux segments sous la minute plutôt que de
         * vraies coupures. Valeur de départ à affiner avec le journal brut (`RawEventLog`).
         *
         * Ne s'applique JAMAIS à une clôture par écran éteint / verrouillage / arrêt
         * (`SCREEN_OFF`, `KEYGUARD`, `SHUTDOWN`) : ces frontières restent des coupures
         * dures, y compris pour un verrouillage très bref.
         */
        val sessionContinuityMillis: Long = 5_000L,
        /** Au-delà, une session unique est suspecte (fin d'événement probablement manquée). */
        val suspiciousSessionMillis: Long = 4 * 3_600_000L,
        /** Au-delà, l'écart entre deux événements est signalé comme trou. */
        val gapThresholdMillis: Long = 3 * 3_600_000L,
        /** Sessions plus courtes que cela : ignorées (transitions système). */
        val minSessionMillis: Long = 0L
    )

    /**
     * @param lookbackEvents événements STRICTEMENT antérieurs à [windowStartMillis]
     *   (idéalement les 24 h précédentes, cf. `SpikeViewModel`), utilisés UNIQUEMENT
     *   pour déterminer l'état réel à la frontière du jour : quel package était encore
     *   au premier plan à `windowStartMillis`, et l'état écran/keyguard en vigueur.
     *   Aucun événement de `lookbackEvents` ne produit jamais de session, d'anomalie,
     *   de trou ni d'incrément de compteur — seul l'état qu'il détermine est "porté"
     *   dans la fenêtre du jour. C'est ce qui distingue cette reconstruction d'une
     *   supposition arbitraire sur un PAUSED orphelin en tout début de journée : l'état
     *   à la frontière est reconstitué à partir d'événements réels, jamais deviné.
     */
    fun reconstruct(
        events: List<RawUsageEvent>,
        windowStartMillis: Long,
        windowEndMillis: Long,
        lookbackEvents: List<RawUsageEvent> = emptyList()
    ): ReconstructionResult {

        val anomalies = mutableListOf<Anomaly>()
        val sessions = mutableListOf<Session>()
        val gaps = mutableListOf<DataGap>()

        val combined = lookbackEvents + events

        // Android renvoie normalement les événements triés, mais on ne s'y fie pas.
        var outOfOrder = false
        for (i in 1 until combined.size) {
            if (combined[i].timestampMillis < combined[i - 1].timestampMillis) {
                outOfOrder = true
                break
            }
        }
        if (outOfOrder) {
            anomalies += Anomaly(
                AnomalyType.OUT_OF_ORDER,
                combined.firstOrNull()?.timestampMillis ?: windowStartMillis,
                "Le flux renvoyé par Android n'était pas trié. Tri appliqué avant reconstruction."
            )
        }
        // Toujours retrié (pas seulement si `outOfOrder`) sur (horodatage, priorité) : un
        // déverrouillage direct dans une app fait souvent arriver KEYGUARD_HIDDEN et
        // l'ACTIVITY_RESUMED qui suit au MÊME horodatage, dans un ordre non garanti côté
        // flux Android. Si le RESUMED est vu avant le KEYGUARD_HIDDEN correspondant, la
        // garde `RESUMED_WHILE_LOCKED` le rejette à tort — bug réel observé le 2026-09-07
        // (Horloge/chronomètre ouvert en déverrouillant directement dedans, jamais compté
        // ensuite). Un événement d'état écran/keyguard doit toujours être appliqué avant
        // un événement d'activité au même horodatage, jamais l'inverse : c'est l'état qui
        // cause la transition d'activité, pas le contraire.
        val sorted = combined.sortedWith(compareBy({ it.timestampMillis }, { eventPriority(it.type) }))

        var openPackage: String? = null
        // Activity actuellement résumée au sein d'openPackage. Distingue une VRAIE
        // répétition de RESUMED (même package, même Activity) d'une transition interne
        // normale (même package, Activity différente — ex. ChromeLauncherActivity puis
        // CustomTabActivity), et permet de reconnaître qu'un PAUSED/STOPPED tardif
        // concernant une Activity déjà remplacée ne doit PAS clore la session active.
        // Bug confirmé le 2026-08-22 (relevé Chrome réel) : un suivi au seul niveau
        // package laissait un STOPPED tardif de l'ancienne Activity fermer à tort la
        // nouvelle, perdant ~12 min 40 s sur ~12 min 42 s d'usage réel.
        var openClassName: String? = null
        var openStart = 0L

        var unlockCount = 0
        var screenWakeCount = 0
        var rawResumeCount = 0
        var appLaunchCount = 0

        val shutdowns = mutableListOf<Long>()
        val startups = mutableListOf<Long>()

        var lastForegroundPackage: String? = null
        val lastSeenByPackage = mutableMapOf<String, Long>()

        var previousEventMillis: Long? = null
        var previousEventType: RawEventType? = null

        // État courant écran/keyguard, mis à jour au fil du flux (lookback compris) et
        // valable jusqu'au prochain événement qui le change — pas seulement au regard
        // du seul événement immédiatement adjacent à un trou. Hypothèse de démarrage :
        // fenêtre ouverte sur un appareil déverrouillé (cohérent avec le fait que la
        // requête elle-même nécessite un appareil déverrouillé, cf. AndroidUsageSource),
        // utilisée seulement s'il n'existe aucun lookback pour la contredire.
        var screenInteractive = true
        var keyguardShown = false

        // Un déverrouillage direct dans une app peut envoyer ACTIVITY_RESUMED quelques
        // millisecondes AVANT le KEYGUARD_HIDDEN qui le cause (pas seulement au même
        // horodatage à la seconde près — confirmé le 2026-09-07 : le tri par priorité
        // seul ne suffisait pas, l'écart réel dépasse parfois la résolution du tri).
        // Un tel RESUMED n'est donc jamais rejeté immédiatement : il est mis en attente,
        // et confirmé rétroactivement si KEYGUARD_HIDDEN arrive dans les
        // `sessionContinuityMillis` qui suivent, sans qu'aucun autre événement ne
        // l'invalide entre-temps (écran éteint, reverrouillage, ou une autre app
        // réellement résumée). Sinon, il est définitivement écarté (ghost resume confirmé).
        var pendingLockedPackage: String? = null
        var pendingLockedClassName: String? = null
        var pendingLockedStart = 0L
        var pendingLockedRecording = false

        // Fermeture d'une session. Les événements DÉCLENCHEURS antérieurs à
        // windowStartMillis (lookback) mettent à jour l'état "porté" (openPackage,
        // lastSeenByPackage) mais ne produisent jamais de Session ni d'anomalie : ce
        // sont des événements d'hier, pas d'aujourd'hui. Une session PORTÉE depuis le
        // lookback (openStart < windowStartMillis) et fermée aujourd'hui est tronquée à
        // windowStartMillis pour son décompte — seule la portion d'aujourd'hui compte
        // dans le temps d'écran du jour — mais la durée RÉELLE (non tronquée) reste
        // utilisée pour la détection de session suspecte.
        fun closeOpenSession(atMillis: Long, cause: EndCause) {
            val pkg = openPackage ?: return
            val start = openStart
            val end = atMillis.coerceAtLeast(start).coerceAtMost(windowEndMillis)
            lastSeenByPackage[pkg] = end
            openPackage = null
            openClassName = null

            if (atMillis < windowStartMillis) return // clôture entièrement antérieure au jour

            val duration = end - start
            val recordedStart = start.coerceAtLeast(windowStartMillis)
            val recordedDuration = end - recordedStart
            if (recordedDuration >= config.minSessionMillis) {
                sessions += Session(pkg, recordedStart, end, cause)
            }
            if (duration > config.suspiciousSessionMillis) {
                anomalies += Anomaly(
                    AnomalyType.SUSPICIOUS_DURATION,
                    start,
                    "Session de ${duration / 60_000} min sur $pkg (clôture : $cause)."
                )
            }
        }

        fun openSession(pkg: String, className: String?, atMillis: Long, recording: Boolean) {
            val lastSeen = lastSeenByPackage[pkg]
            val isNewLaunch = lastForegroundPackage != pkg ||
                lastSeen == null ||
                (atMillis - lastSeen) > config.sessionContinuityMillis
            if (isNewLaunch && recording) appLaunchCount++
            openPackage = pkg
            openClassName = className
            openStart = atMillis
            lastForegroundPackage = pkg
        }

        // Écarte définitivement une attente non confirmée (ghost resume) : écran éteint,
        // reverrouillage, ou une autre app réellement résumée entre-temps.
        fun discardPendingLocked() {
            val pkg = pendingLockedPackage ?: return
            if (pendingLockedRecording) {
                anomalies += Anomaly(
                    AnomalyType.RESUMED_WHILE_LOCKED, pendingLockedStart,
                    "ACTIVITY_RESUMED sur $pkg alors que l'écran est verrouillé/éteint — ignoré."
                )
            }
            pendingLockedPackage = null
        }

        for (event in sorted) {
            val ts = event.timestampMillis
            val recording = ts >= windowStartMillis

            // Détection des trous, avec tentative d'attribution de cause. La cause
            // "verrouillé" s'appuie sur l'état écran/keyguard EN VIGUEUR pendant le
            // trou (screenInteractive/keyguardShown, mis à jour au fil du flux), pas
            // sur le seul type du dernier événement adjacent : un événement sans
            // rapport (ex. une synchronisation en arrière-plan) juste avant un long
            // silence nocturne ne doit pas faire retomber un verrouillage réel en
            // cause "inconnue". Un silence cohérent avec un téléphone verrouillé est
            // normal et ne doit pas être remonté comme une anomalie. Un trou entièrement
            // antérieur au jour (les deux bornes en lookback) n'est pas remonté ; un
            // trou à cheval sur minuit l'est, car il explique une partie du jour.
            previousEventMillis?.let { prev ->
                val delta = ts - prev
                if (delta > config.gapThresholdMillis && recording) {
                    val cause = when {
                        previousEventType == RawEventType.DEVICE_SHUTDOWN ||
                            event.type == RawEventType.DEVICE_STARTUP -> GapCause.DEVICE_OFF
                        !screenInteractive || keyguardShown -> GapCause.LIKELY_IDLE_LOCKED
                        else -> GapCause.UNKNOWN
                    }
                    gaps += DataGap(prev, ts, cause)
                    if (cause == GapCause.UNKNOWN) {
                        anomalies += Anomaly(
                            AnomalyType.EVENT_GAP,
                            prev,
                            "Aucun événement pendant ${delta / 60_000} min, cause non identifiable."
                        )
                    }
                }
            }

            when (event.type) {
                RawEventType.ACTIVITY_RESUMED -> {
                    if (recording) rawResumeCount++
                    val pkg = event.packageName
                    if (pkg == null) {
                        if (recording) {
                            anomalies += Anomaly(
                                AnomalyType.MISSING_PACKAGE, ts,
                                "ACTIVITY_RESUMED sans nom de package."
                            )
                        }
                    } else if (openPackage == pkg) {
                        // Même package déjà au premier plan : soit une vraie répétition
                        // (même Activity, sans fin intermédiaire — anomalie), soit une
                        // transition interne normale vers une autre Activity du même
                        // package (ex. Chrome ouvrant un Custom Tab) — la session
                        // continue sans interruption, seule l'Activity active change.
                        if (event.className == openClassName) {
                            if (recording) {
                                anomalies += Anomaly(
                                    AnomalyType.DUPLICATE_RESUME, ts,
                                    "RESUMED répété sur $pkg/${event.className} sans fin intermédiaire."
                                )
                            }
                        } else {
                            openClassName = event.className
                        }
                    } else {
                        if (openPackage != null) closeOpenSession(ts, EndCause.REPLACED)

                        // Un réveil d'écran sans déverrouillage (notification, lever du
                        // téléphone) peut faire réapparaître dans le flux l'app qui était au
                        // premier plan avant le verrouillage, sans aucune action réelle de
                        // l'utilisateur. Ne jamais ouvrir de session dans cet état directement :
                        // sinon le temps d'écran gonfle à chaque réveil d'écran alors que les
                        // déverrouillages (KEYGUARD_HIDDEN, comptés indépendamment) restent
                        // corrects — exactement la divergence observée en pratique. Mais un
                        // déverrouillage direct DANS l'app produit le même état à cet instant
                        // (KEYGUARD_HIDDEN pas encore traité) : on met donc en attente plutôt
                        // que de rejeter tout de suite (cf. commentaire sur `pendingLockedPackage`).
                        if (!screenInteractive || keyguardShown) {
                            discardPendingLocked()
                            pendingLockedPackage = pkg
                            pendingLockedClassName = event.className
                            pendingLockedStart = ts
                            pendingLockedRecording = recording
                        } else {
                            discardPendingLocked()
                            openSession(pkg, event.className, ts, recording)
                        }
                    }
                }

                RawEventType.ACTIVITY_PAUSED, RawEventType.ACTIVITY_STOPPED -> {
                    val pkg = event.packageName
                    // Une fin ne concernant pas l'Activity actuellement active (mais une
                    // Activity antérieure du MÊME package, déjà remplacée) est tardive
                    // et bénigne : elle ne doit surtout pas clore la session active. On
                    // reste tolérant si la classe est inconnue d'un côté ou de l'autre
                    // (certains événements système ne la fournissent pas) : dans le
                    // doute, on clôt plutôt que de risquer de ne jamais fermer la session.
                    val matchesActiveActivity = openClassName == null ||
                        event.className == null ||
                        event.className == openClassName
                    if (pkg != null && openPackage == pkg && matchesActiveActivity) {
                        val cause = if (event.type == RawEventType.ACTIVITY_PAUSED)
                            EndCause.PAUSED else EndCause.STOPPED
                        closeOpenSession(ts, cause)
                    } else if (pkg != null && openPackage == pkg && recording) {
                        anomalies += Anomaly(
                            AnomalyType.STALE_ACTIVITY_END, ts,
                            "Fin tardive de $pkg/${event.className} reçue alors que " +
                                "$pkg/$openClassName est l'Activity active — ignorée (bénigne)."
                        )
                    } else if (pkg != null && openPackage != null && recording) {
                        anomalies += Anomaly(
                            AnomalyType.ORPHAN_END, ts,
                            "Fin reçue pour $pkg alors que $openPackage était au premier plan."
                        )
                    }
                }

                RawEventType.SCREEN_NON_INTERACTIVE -> {
                    closeOpenSession(ts, EndCause.SCREEN_OFF)
                    discardPendingLocked()
                    screenInteractive = false
                }

                RawEventType.KEYGUARD_SHOWN -> {
                    closeOpenSession(ts, EndCause.KEYGUARD)
                    discardPendingLocked()
                    keyguardShown = true
                }

                RawEventType.DEVICE_SHUTDOWN -> {
                    if (recording) shutdowns += ts
                    closeOpenSession(ts, EndCause.SHUTDOWN)
                    discardPendingLocked()
                    screenInteractive = false
                    keyguardShown = true
                }

                RawEventType.DEVICE_STARTUP -> {
                    if (recording) startups += ts
                    if (openPackage != null) {
                        if (recording) {
                            anomalies += Anomaly(
                                AnomalyType.SESSION_ACROSS_STARTUP, ts,
                                "Session $openPackage encore ouverte au démarrage de l'appareil."
                            )
                        }
                        closeOpenSession(ts, EndCause.SHUTDOWN)
                    }
                    discardPendingLocked()
                    // Un DEVICE_STARTUP implique que le keyguard reprend son état par défaut :
                    // verrouillé jusqu'à preuve du contraire (KEYGUARD_HIDDEN).
                    keyguardShown = true
                }

                RawEventType.KEYGUARD_HIDDEN -> {
                    if (recording) unlockCount++
                    keyguardShown = false
                    val pendingPkg = pendingLockedPackage
                    if (pendingPkg != null && ts - pendingLockedStart <= config.sessionContinuityMillis) {
                        openSession(pendingPkg, pendingLockedClassName, pendingLockedStart, pendingLockedRecording)
                        pendingLockedPackage = null
                    }
                }

                RawEventType.SCREEN_INTERACTIVE -> {
                    if (recording) screenWakeCount++
                    screenInteractive = true
                }

                RawEventType.OTHER -> Unit
            }

            previousEventMillis = ts
            previousEventType = event.type
        }

        // Une attente encore non résolue à la fin du flux n'a jamais été confirmée : ghost resume.
        discardPendingLocked()

        if (openPackage != null) {
            val pkg = openPackage
            anomalies += Anomaly(
                AnomalyType.UNCLOSED_AT_WINDOW_END, windowEndMillis,
                "Session $pkg encore ouverte à la fin de la fenêtre. Durée tronquée, valeur incertaine."
            )
            closeOpenSession(windowEndMillis, EndCause.WINDOW_END)
        }

        return ReconstructionResult(
            sessions = mergeContinuedSessions(sessions, config.sessionContinuityMillis),
            anomalies = anomalies,
            gaps = gaps,
            unlockCount = unlockCount,
            screenWakeCount = screenWakeCount,
            rawResumeCount = rawResumeCount,
            appLaunchCount = appLaunchCount,
            shutdownEvents = shutdowns,
            startupEvents = startups,
            eventCount = sorted.count { it.timestampMillis >= windowStartMillis }
        )
    }

    /**
     * Recolle deux sessions ADJACENTES du même package séparées par un silence court
     * (aucune autre app résumée entre les deux, cf. `Config.sessionContinuityMillis`)
     * en une seule session continue.
     *
     * Ne s'applique jamais quand la première session s'est terminée par un écran
     * éteint, un verrouillage ou un arrêt de l'appareil : ce sont des coupures
     * réelles, même brèves, jamais de simples artefacts d'interface système.
     */
    private fun mergeContinuedSessions(sessions: List<Session>, continuityMillis: Long): List<Session> {
        if (sessions.isEmpty()) return sessions
        val hardBoundaries = setOf(EndCause.SCREEN_OFF, EndCause.KEYGUARD, EndCause.SHUTDOWN)

        val merged = mutableListOf<Session>()
        var current = sessions.first()
        for (i in 1 until sessions.size) {
            val next = sessions[i]
            val gap = next.startMillis - current.endMillis
            val bridgeable = next.packageName == current.packageName &&
                current.endCause !in hardBoundaries &&
                gap in 0..continuityMillis
            current = if (bridgeable) {
                current.copy(endMillis = next.endMillis, endCause = next.endCause)
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }
}

/**
 * Priorité de tri à horodatage égal : les événements d'état écran/keyguard (0) sont
 * toujours appliqués avant les événements d'activité (1). Voir le commentaire sur `sorted`
 * dans `reconstruct` — sans ça, un KEYGUARD_HIDDEN et l'ACTIVITY_RESUMED qu'il déclenche,
 * arrivés au même horodatage, peuvent être vus dans l'ordre inverse.
 */
private fun eventPriority(type: RawEventType): Int = when (type) {
    RawEventType.SCREEN_INTERACTIVE,
    RawEventType.SCREEN_NON_INTERACTIVE,
    RawEventType.KEYGUARD_SHOWN,
    RawEventType.KEYGUARD_HIDDEN,
    RawEventType.DEVICE_SHUTDOWN,
    RawEventType.DEVICE_STARTUP -> 0
    else -> 1
}
