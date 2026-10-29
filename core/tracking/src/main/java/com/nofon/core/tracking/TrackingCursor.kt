package com.nofon.core.tracking

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.trackingDataStore: DataStore<Preferences> by preferencesDataStore(name = "tracking_cursor")
private val LAST_PROCESSED_EVENT_MILLIS = longPreferencesKey("last_processed_event_millis")
private val HISTORY_BACKFILL_VERSION = intPreferencesKey("history_backfill_version")

/**
 * Position du curseur d'événements déjà traités par [com.nofon.core.tracking.work.TrackingSyncWorker].
 * Une seule valeur : aucun besoin de Room pour ça (`ARCHITECTURE-2.md` §2.4 —
 * « rattrapage systématique depuis le dernier curseur persistant »).
 *
 * `null` signifie « jamais synchronisé » : l'appelant doit alors choisir un point de
 * départ (voir `TrackingSyncWorker`), jamais supposer que 0 est correct.
 */
@Singleton
class TrackingCursor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun read(): Long? = context.trackingDataStore.data.first()[LAST_PROCESSED_EVENT_MILLIS]

    suspend fun advance(toMillis: Long) {
        context.trackingDataStore.edit { it[LAST_PROCESSED_EVENT_MILLIS] = toMillis }
    }

    /**
     * Version du dernier rattrapage d'historique local exécuté par
     * [com.nofon.core.tracking.work.TrackingSyncWorker] (ex. recalcul après correction
     * d'un bug de reconstruction). `0` si aucun rattrapage n'a encore eu lieu.
     */
    suspend fun readBackfillVersion(): Int =
        context.trackingDataStore.data.first()[HISTORY_BACKFILL_VERSION] ?: 0

    suspend fun writeBackfillVersion(version: Int) {
        context.trackingDataStore.edit { it[HISTORY_BACKFILL_VERSION] = version }
    }
}
