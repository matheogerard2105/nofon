package com.nofon.core.network

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Auth)
        install(Postgrest)
        install(Storage)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: SupabaseAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindGoalRepository(impl: SupabaseGoalRepository): GoalRepository

    @Binds
    @Singleton
    abstract fun bindAppConfigRepository(impl: SupabaseAppConfigRepository): AppConfigRepository

    @Binds
    @Singleton
    abstract fun bindDebtRepository(impl: SupabaseDebtRepository): DebtRepository

    @Binds
    @Singleton
    abstract fun bindPaymentProvider(impl: SimulatedPaymentProvider): PaymentProvider

    @Binds
    @Singleton
    abstract fun bindExploitRepository(impl: SupabaseExploitRepository): ExploitRepository

    @Binds
    @Singleton
    abstract fun bindSocialGraphRepository(impl: SupabaseSocialGraphRepository): SocialGraphRepository

    @Binds
    @Singleton
    abstract fun bindFeedRepository(impl: SupabaseFeedRepository): FeedRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: SupabaseProfileRepository): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindNotificationRepository(impl: SupabaseNotificationRepository): NotificationRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: SupabaseGroupRepository): GroupRepository

    @Binds
    @Singleton
    abstract fun bindIntegrityAttestationRepository(
        impl: PlayIntegrityAttestationRepository
    ): IntegrityAttestationRepository
}
