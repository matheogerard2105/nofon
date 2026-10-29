package com.nofon.core.tracking.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Planifie [TrackingSyncWorker] : une requête périodique (15 min, minimum système,
 * ARCHITECTURE-2.md §2.4) pour la collecte de fond, et une requête ponctuelle
 * immédiate pour ne pas attendre jusqu'à 15 min lors d'une vérification manuelle
 * (ouverture de l'app, octroi de la permission).
 */
@Singleton
class TrackingScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<TrackingSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TrackingSyncWorker.UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<TrackingSyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TrackingSyncWorker.UNIQUE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
