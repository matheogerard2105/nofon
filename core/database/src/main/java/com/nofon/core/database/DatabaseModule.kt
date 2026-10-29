package com.nofon.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "nofon.db")
            // Acceptable à ce stade (Phase 3) : aucune donnée réelle en base avant
            // cette version. À remplacer par de vraies migrations dès que des
            // utilisateurs réels existent.
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideAppConfigDao(database: AppDatabase): AppConfigDao = database.appConfigDao()

    @Provides
    fun provideDailyAggregateDao(database: AppDatabase): DailyAggregateDao = database.dailyAggregateDao()
}
