package com.nofon.feature.home

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.goalRecapDataStore: DataStore<Preferences> by preferencesDataStore(name = "goal_recap_ack")
private val LAST_ACKNOWLEDGED_GOAL_ID = stringPreferencesKey("last_acknowledged_goal_id")

/**
 * Mémorise localement le dernier objectif payant dont le récapitulatif de fin
 * (PRODUCT_SPEC-2.md §13) a été vu. `goals` reste immuable côté serveur — cet état
 * est purement d'affichage, aucune conséquence financière n'en dépend.
 */
@Singleton
class GoalRecapAcknowledgementStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun isAcknowledged(goalId: String): Boolean =
        context.goalRecapDataStore.data.first()[LAST_ACKNOWLEDGED_GOAL_ID] == goalId

    suspend fun acknowledge(goalId: String) {
        context.goalRecapDataStore.edit { it[LAST_ACKNOWLEDGED_GOAL_ID] = goalId }
    }
}
