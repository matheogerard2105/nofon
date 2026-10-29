package com.nofon.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nofon.core.tracking.work.TrackingScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NofonApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var trackingScheduler: TrackingScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Planifie la collecte de fond (15 min) et lance une synchro immédiate — sans
        // ça, une vérification manuelle après octroi de la permission attendrait
        // jusqu'à 15 min avant le premier résultat.
        trackingScheduler.schedulePeriodic()
        trackingScheduler.syncNow()
    }
}
