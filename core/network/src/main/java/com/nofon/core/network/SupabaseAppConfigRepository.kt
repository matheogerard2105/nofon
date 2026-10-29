package com.nofon.core.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseAppConfigRepository @Inject constructor(
    private val client: SupabaseClient
) : AppConfigRepository {

    override suspend fun fetchAll(): Result<List<AppConfigEntry>> = runCatching {
        client.from("app_config").select().decodeList<AppConfigEntry>()
    }
}
