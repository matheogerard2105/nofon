package com.nofon.core.tracking

import android.content.Intent
import com.nofon.core.tracking.domain.PermissionState
import com.nofon.core.tracking.domain.RawUsageEvent

/**
 * Accès aux données d'usage brutes, abstrait pour permettre deux implémentations
 * sélectionnées par variante de build Gradle (`ARCHITECTURE-2.md` §9) — jamais un
 * booléen à l'exécution, pour que le code simulé ne soit jamais compilé dans le
 * binaire de production :
 * - flavor `real` → `AndroidUsageStatsProvider` (`UsageStatsManager`)
 * - flavor `simulated` → `SimulatedProvider` (flux synthétique, pour développer et
 *   tester sans permission ni vraies données)
 */
interface TrackingProvider {

    sealed interface QueryOutcome {
        data class Success(val events: List<RawUsageEvent>) : QueryOutcome

        /** `queryEvents` a renvoyé null. Depuis Android 11, cela arrive si l'appareil n'est pas déverrouillé. */
        data object NullReturned : QueryOutcome

        data object NoPermission : QueryOutcome
        data class Failure(val message: String) : QueryOutcome
    }

    fun permissionState(): PermissionState

    fun hasUsageAccess(): Boolean

    /**
     * L'accès aux statistiques d'usage ne se demande pas par une boîte de dialogue :
     * il faut envoyer l'utilisateur dans un écran système.
     */
    fun usageAccessSettingsIntent(): Intent

    fun queryEvents(beginMillis: Long, endMillis: Long): QueryOutcome

    /**
     * Applications lançables, via le bloc `<queries>` du manifeste.
     * QUERY_ALL_PACKAGES n'est volontairement pas utilisé (ARCHITECTURE-2.md §2.3).
     * Capacité posée pour le sélecteur d'applications exception de la Phase 4 — aucune
     * UI ne l'exploite encore à cette phase.
     */
    fun launchableApps(): List<InstalledApp>

    fun labelFor(packageName: String): String

    data class InstalledApp(val packageName: String, val label: String)
}
