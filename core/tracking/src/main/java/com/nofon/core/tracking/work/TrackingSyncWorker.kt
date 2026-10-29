package com.nofon.core.tracking.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nofon.core.database.DailyAggregateDao
import com.nofon.core.database.DailyAggregateEntity
import com.nofon.core.database.DailyAppUsageEntity
import com.nofon.core.network.AuthRepository
import com.nofon.core.network.AuthState
import com.nofon.core.network.DebtRepository
import com.nofon.core.network.GoalRepository
import com.nofon.core.network.GroupRepository
import com.nofon.core.network.IntegrityAttestationRepository
import com.nofon.core.network.LiveDayMeasurement
import com.nofon.core.network.NewMeasurementBatch
import com.nofon.core.network.PaymentProvider
import com.nofon.core.tracking.BuildConfig
import com.nofon.core.tracking.TrackingProvider
import com.nofon.core.tracking.TrackingCursor
import com.nofon.core.tracking.domain.MetricsCalculator
import com.nofon.core.tracking.domain.Quality
import com.nofon.core.tracking.domain.SessionReconstructor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Industrialise le collecteur du spike (`ARCHITECTURE-2.md` §2.4) : relit
 * systématiquement le jour courant en entier (minuit local → maintenant), avec un
 * lookback de 24 h pour porter correctement une session à cheval sur minuit — la même
 * approche que celle validée à l'itération 3 du spike, plutôt qu'un découpage fin par
 * curseur qui rouvrirait les bugs de frontière déjà corrigés.
 *
 * `TrackingCursor` sert ici à savoir si la journée a changé depuis la dernière
 * synchro (auquel cas la veille est finalisée une dernière fois avant de reprendre
 * aujourd'hui), pas à borner la requête elle-même.
 *
 * Limitation assumée à cette phase : un trou de plusieurs jours sans ouverture de
 * l'app laisse les jours intermédiaires non finalisés (seuls "hier" et "aujourd'hui"
 * sont couverts). Aucune conséquence financière n'en dépend encore — ça deviendra
 * pertinent à partir de la Phase 4.
 */
@HiltWorker
class TrackingSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val trackingProvider: TrackingProvider,
    private val cursor: TrackingCursor,
    private val dailyAggregateDao: DailyAggregateDao,
    private val authRepository: AuthRepository,
    private val goalRepository: GoalRepository,
    private val debtRepository: DebtRepository,
    private val paymentProvider: PaymentProvider,
    private val groupRepository: GroupRepository,
    private val integrityAttestationRepository: IntegrityAttestationRepository
) : CoroutineWorker(context, params) {

    private val reconstructor = SessionReconstructor()
    private val metrics = MetricsCalculator()

    override suspend fun doWork(): Result {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val lastSync = cursor.read()
        val today = LocalDate.now(zone)

        if (lastSync != null) {
            val lastSyncDate = Instant.ofEpochMilli(lastSync).atZone(zone).toLocalDate()
            if (lastSyncDate.isBefore(today)) {
                aggregateDay(lastSyncDate, zone)
            }
        }

        val success = aggregateDay(today, zone)
        if (!success) return Result.retry()

        backfillLocalHistoryIfNeeded(zone)

        // Processus démarré à froid par WorkManager (app jamais ouverte de la journée) :
        // la session Supabase est restaurée depuis le stockage de façon asynchrone, donc
        // `authState` vaut encore `Initializing` à cet instant. Sans cette attente, toutes
        // les étapes serveur ci-dessous sortaient silencieusement (`?: return`) — c'est
        // pourquoi un utilisateur qui n'ouvrait pas l'app n'avait aucune donnée publiée.
        awaitAuthResolved()

        submitActiveGoalMeasurement()
        publishLiveToday()
        settlePendingDebtIfGoalCompleted()
        requestStaleGroupDaysFinalization()

        cursor.advance(now)
        return Result.success()
    }

    private suspend fun awaitAuthResolved() {
        withTimeoutOrNull(AUTH_RESTORE_TIMEOUT_MILLIS) {
            authRepository.authState.first { it !is AuthState.Initializing }
        } ?: Log.d(TAG, "auth still initializing after ${AUTH_RESTORE_TIMEOUT_MILLIS}ms, server steps skipped")
    }

    /**
     * Mesure provisoire de la journée en cours pour chaque objectif actif
     * (`0017_live_day_measurements.sql`) — ce qui alimente le point "aujourd'hui" du
     * graphique de groupe et du profil vu par les autres. Rejouée à chaque passage
     * (upsert), sans conséquence : le lot définitif reste soumis par [catchUpGoal] une
     * fois la journée close. Fuseau figé de l'objectif (TODO-03), comme le lot définitif.
     */
    private suspend fun publishLiveToday() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val goals = listOfNotNull(goalRepository.activeStakedGoal(userId).getOrNull()) +
            goalRepository.activeFreeGoals(userId).getOrDefault(emptyList())
        goals.forEach { goal ->
            val goalZone = runCatching { ZoneId.of(goal.timezone) }.getOrDefault(ZoneId.systemDefault())
            val today = LocalDate.now(goalZone)
            if (today.isBefore(LocalDate.parse(goal.startDate))) return@forEach
            val excludedPackages = goalRepository.excludedApps(goal.id).getOrDefault(emptyList()).toSet()
            // Journée en cours illisible (appareil pas encore déverrouillé depuis le
            // démarrage, permission retirée) : on garde la dernière valeur publiée
            // plutôt que d'écraser par un zéro.
            val measure = measureDay(goalZone, today, excludedPackages) ?: return@forEach
            val result = goalRepository.upsertLiveDayMeasurement(
                LiveDayMeasurement(
                    goalId = goal.id,
                    userId = userId,
                    localDate = today.toString(),
                    measuredTimeSeconds = measure.timeSeconds,
                    measuredOpens = measure.opens,
                    dataQuality = measure.quality,
                    updatedAt = Instant.now().toString()
                )
            )
            Log.d(TAG, "live day=$today goalId=${goal.id} result=$result")
        }
    }

    private data class DayMeasure(val timeSeconds: Int, val opens: Int, val quality: String)

    /**
     * Reconstruit [day] (minuit → minuit, borné à maintenant pour la journée en cours)
     * dans [goalZone]. `null` si les événements ne sont pas lisibles.
     */
    private suspend fun measureDay(goalZone: ZoneId, day: LocalDate, excludedPackages: Set<String>): DayMeasure? {
        val dayStart = day.atStartOfDay(goalZone).toInstant().toEpochMilli()
        val dayEnd = minOf(
            day.plusDays(1).atStartOfDay(goalZone).toInstant().toEpochMilli(),
            System.currentTimeMillis()
        )
        if (dayEnd <= dayStart) return null

        val dayOutcome = trackingProvider.queryEvents(dayStart, dayEnd)
        Log.d(TAG, "day=$day queryEvents outcome=${dayOutcome::class.simpleName}")
        val dayEvents = (dayOutcome as? TrackingProvider.QueryOutcome.Success)?.events ?: return null
        val lookbackEvents = (trackingProvider.queryEvents(dayStart - LOOKBACK_MILLIS, dayStart)
            as? TrackingProvider.QueryOutcome.Success)?.events.orEmpty()

        val reconstruction = reconstructor.reconstruct(dayEvents, dayStart, dayEnd, lookbackEvents)
        val metricsDay = metrics.computeDay(
            result = reconstruction,
            windowStartMillis = dayStart,
            windowEndMillis = dayEnd,
            localDateLabel = day.toString(),
            excludedPackages = excludedPackages
        )

        // Ouverture = déverrouillage (TODO-02), jamais un lancement d'app.
        val worstQuality = maxOf(metricsDay.nofonScreenTime.quality.ordinal, metricsDay.unlocks.quality.ordinal)
        return DayMeasure(
            timeSeconds = (metricsDay.nofonScreenTime.value / 1000).toInt(),
            opens = metricsDay.unlocks.value,
            quality = Quality.entries[worstQuality].name
        )
    }

    /**
     * Filet de sécurité TODO-22 (`0006_groups.sql`) : signale au serveur toute journée
     * encore `PROVISIONAL` d'un membre de groupe — le serveur revérifie lui-même le
     * délai (`resolve_group_day`, jamais une confiance dans l'horloge du client,
     * ARCHITECTURE-2.md §3) et ne finalise que si le délai est réellement dépassé.
     * Poster une demande trop tôt est donc sans risque, juste un no-op serveur.
     */
    private suspend fun requestStaleGroupDaysFinalization() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val membership = groupRepository.myMembership(userId).getOrNull() ?: return
        if (membership.status !in setOf("ACTIVE", "LEAVING")) return
        val provisionalDates = goalRepository.dailyResults(membership.goalId).getOrDefault(emptyList())
            .filter { it.status == "PROVISIONAL" }
            .map { it.localDate }
        provisionalDates.forEach { date ->
            groupRepository.requestFinalizationIfStale(membership.groupId, date)
        }
    }

    /**
     * Le serveur marque déjà `goals.status = COMPLETED` dans la même transaction que
     * l'évaluation du dernier lot de mesure de l'objectif (`evaluate_measurement_batch`,
     * `0003_money.sql`) — ce worker ne fait que constater cet état et poser la demande
     * de règlement simulé (PRODUCT_SPEC-2.md §5.3). Aucune écriture directe dans
     * `debts`/`ledger_entries` : seule l'insertion dans `debt_settlement_requests` est
     * autorisée côté client, idempotente côté serveur (`unique(debt_id)`).
     */
    private suspend fun settlePendingDebtIfGoalCompleted() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val goal = goalRepository.latestStakedGoal(userId).getOrNull() ?: return
        if (goal.status != "COMPLETED") return
        val debt = debtRepository.debtForGoal(goal.id).getOrNull() ?: return
        if (debt.status != "PENDING") return
        paymentProvider.settleDebt(debtId = debt.id, goalId = goal.id, userId = userId)
    }

    /**
     * Soumission automatisée pour l'objectif payant actif **et** chaque objectif
     * gratuit actif (TODO-24, tranché : option (a) — un aller-retour par objectif
     * gratuit à chaque synchro, `evaluate_measurement_batch` étant déjà neutre vis-à-vis
     * du type d'objectif côté serveur, `0003_money.sql`).
     */
    private suspend fun submitActiveGoalMeasurement() {
        val userId = (authRepository.authState.value as? AuthState.SignedIn)?.userId ?: return
        val stakedGoal = goalRepository.activeStakedGoal(userId).getOrNull()
        if (stakedGoal != null) catchUpGoal(stakedGoal.id, userId, stakedGoal.timezone, stakedGoal.startDate)

        val freeGoals = goalRepository.activeFreeGoals(userId).getOrDefault(emptyList())
        freeGoals.forEach { goal -> catchUpGoal(goal.id, userId, goal.timezone, goal.startDate) }
    }

    /**
     * Fuseau **figé de l'objectif** (TODO-03), jamais celui de l'appareil : un objectif
     * déjà lancé garde son fuseau quoi qu'il arrive. Rattrape tous les jours manquants
     * entre le début de l'objectif et hier — pas seulement "hier" (limitation de la
     * Phase 3, constatée en conditions réelles : un jour sans ouverture de l'app
     * laissait un trou définitif dans l'historique). Un seul aller-retour pour
     * connaître les jours déjà soumis (`dailyResults`), plutôt qu'une vérification par
     * jour (`hasBatchFor`).
     *
     * Ordre **strictement croissant** obligatoire : la série (`goals.current_streak_days`)
     * est calculée jour après jour côté serveur (`evaluate_measurement_batch`) — la
     * soumettre dans le désordre la corromprait.
     */
    private suspend fun catchUpGoal(goalId: String, userId: String, timezone: String, startDate: String) {
        val goalZone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        val yesterday = LocalDate.now(goalZone).minusDays(1)
        val goalStart = startDate.let(LocalDate::parse)
        if (yesterday.isBefore(goalStart)) return

        val alreadySubmitted = goalRepository.dailyResults(goalId).getOrDefault(emptyList())
            .map { it.localDate }.toSet()
        val excludedPackages = goalRepository.excludedApps(goalId).getOrDefault(emptyList()).toSet()
        Log.d(TAG, "catch-up goalId=$goalId range=$goalStart..$yesterday alreadySubmitted=$alreadySubmitted")

        var day = goalStart
        while (!day.isAfter(yesterday)) {
            if (day.toString() !in alreadySubmitted) {
                Log.d(TAG, "submitting missing day=$day")
                submitMeasurementForDay(goalId, userId, goalZone, day, excludedPackages)
            }
            day = day.plusDays(1)
        }
    }

    private suspend fun submitMeasurementForDay(
        goalId: String,
        userId: String,
        goalZone: ZoneId,
        day: LocalDate,
        excludedPackages: Set<String>
    ) {
        val measure = measureDay(goalZone, day, excludedPackages)
        val result = goalRepository.submitMeasurementBatch(
            NewMeasurementBatch(
                goalId = goalId,
                userId = userId,
                localDate = day.toString(),
                // Journée close, données indisponibles : politique TODO-06 (NO_DATA), aucune conséquence.
                measuredTimeSeconds = measure?.timeSeconds ?: 0,
                measuredOpens = measure?.opens ?: 0,
                dataQuality = measure?.quality ?: "UNAVAILABLE",
                integrityToken = requestIntegrityToken(goalId, day)
            )
        )
        Log.d(TAG, "day=$day submitted quality=${measure?.quality ?: "UNAVAILABLE"} result=$result")
    }

    /**
     * TODO-30 (PRODUCT_TODOS-2.md) : jeton capturé et transmis tel quel, jamais décodé
     * ici ni côté serveur pour l'instant (voir IntegrityAttestationRepository). Un échec
     * (émulateur, build non signé/non distribué par Play, Play Services absent) ne doit
     * jamais bloquer la soumission de la mesure elle-même — seule la donnée est perdue.
     */
    private suspend fun requestIntegrityToken(goalId: String, day: LocalDate): String? =
        integrityAttestationRepository
            .requestAttestationToken(applicationContext, requestHash = "$goalId:$day")
            .onFailure { Log.d(TAG, "integrity token unavailable for goalId=$goalId day=$day: ${it.message}") }
            .getOrNull()

    /**
     * Recalcule localement les [BACKFILL_DAYS] derniers jours avec la logique de
     * reconstruction courante, une seule fois par [CURRENT_BACKFILL_VERSION] (ex. après
     * correction d'un bug de comptage — cf. `RESUMED_WHILE_LOCKED`). Purement local
     * (`DailyAggregateEntity`/`DailyAppUsageEntity`, cache d'affichage) : aucune
     * conséquence sur `measurement_batches`, qui reste volontairement immuable côté
     * serveur (`ARCHITECTURE-2.md` §8) et n'est donc jamais retouché ici.
     *
     * Best-effort : un jour hors de la fenêtre de rétention d'`UsageStatsManager` (qui
     * varie selon le fabricant) échoue silencieusement sans bloquer ni le rattrapage des
     * autres jours ni la synchro normale — contrairement à `aggregateDay(today)`, dont
     * l'échec doit faire réessayer tout le worker.
     */
    private suspend fun backfillLocalHistoryIfNeeded(zone: ZoneId) {
        if (cursor.readBackfillVersion() >= CURRENT_BACKFILL_VERSION) return

        val today = LocalDate.now(zone)
        for (offset in 1..BACKFILL_DAYS) {
            aggregateDay(today.minusDays(offset.toLong()), zone)
        }
        cursor.writeBackfillVersion(CURRENT_BACKFILL_VERSION)
    }

    /** @return false si la mesure n'est pas exploitable maintenant (à réessayer), jamais un zéro silencieux. */
    private suspend fun aggregateDay(date: LocalDate, zone: ZoneId): Boolean {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = minOf(
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            System.currentTimeMillis()
        )
        if (dayEnd <= dayStart) return true // journée pas encore commencée : rien à faire

        val lookbackOutcome = trackingProvider.queryEvents(dayStart - LOOKBACK_MILLIS, dayStart)
        val dayOutcome = trackingProvider.queryEvents(dayStart, dayEnd)

        val dayEvents = when (dayOutcome) {
            is TrackingProvider.QueryOutcome.Success -> dayOutcome.events
            // queryEvents renvoie null si l'appareil n'a pas été déverrouillé depuis le
            // démarrage (ARCHITECTURE-2.md §2.2) : jamais un zéro, on réessaiera plus tard.
            TrackingProvider.QueryOutcome.NullReturned -> return false
            TrackingProvider.QueryOutcome.NoPermission -> return false
            is TrackingProvider.QueryOutcome.Failure -> return false
        }
        val lookbackEvents = (lookbackOutcome as? TrackingProvider.QueryOutcome.Success)?.events.orEmpty()

        val reconstruction = reconstructor.reconstruct(dayEvents, dayStart, dayEnd, lookbackEvents)
        val day = metrics.computeDay(
            result = reconstruction,
            windowStartMillis = dayStart,
            windowEndMillis = dayEnd,
            localDateLabel = date.toString(),
            excludedPackages = emptySet() // pas d'objectif à cette phase : aucune exception encore définie
        )

        dailyAggregateDao.upsertDay(
            DailyAggregateEntity(
                localDate = date.toString(),
                windowStartMillis = dayStart,
                windowEndMillis = dayEnd,
                nofonScreenMillis = day.nofonScreenTime.value,
                nofonScreenQuality = day.nofonScreenTime.quality.name,
                totalForegroundMillis = day.totalForegroundTime.value,
                totalForegroundQuality = day.totalForegroundTime.quality.name,
                unlocks = day.unlocks.value,
                unlocksQuality = day.unlocks.quality.name,
                appLaunches = day.appLaunches.value,
                appLaunchesQuality = day.appLaunches.quality.name,
                eventCount = day.eventCount,
                anomalyCount = day.anomalies.size,
                gapCount = day.gaps.size,
                source = BuildConfig.TRACKING_SOURCE,
                lastUpdatedMillis = System.currentTimeMillis()
            )
        )
        dailyAggregateDao.replaceAppUsageForDay(
            date.toString(),
            day.perApp.map {
                DailyAppUsageEntity(date.toString(), it.packageName, it.foregroundMillis, it.sessionCount, it.launchCount)
            }
        )
        return true
    }

    companion object {
        private const val TAG = "TrackingSyncWorker"
        private const val LOOKBACK_MILLIS = 24 * 3_600_000L
        private const val AUTH_RESTORE_TIMEOUT_MILLIS = 10_000L
        private const val BACKFILL_DAYS = 30
        /** Incrémenter pour redéclencher un rattrapage local (ex. nouvelle correction de reconstruction). */
        private const val CURRENT_BACKFILL_VERSION = 1
        const val UNIQUE_PERIODIC_WORK_NAME = "tracking_sync_periodic"
        const val UNIQUE_ONE_TIME_WORK_NAME = "tracking_sync_immediate"
    }
}
