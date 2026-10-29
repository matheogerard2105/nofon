package com.nofon.core.tracking

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SimulatedTrackingModule {

    @Binds
    @Singleton
    abstract fun bindTrackingProvider(impl: SimulatedProvider): TrackingProvider
}
