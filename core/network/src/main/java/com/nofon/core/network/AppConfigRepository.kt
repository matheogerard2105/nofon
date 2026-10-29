package com.nofon.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Une ligne de `app_config` (Supabase) — table clé/valeur en lecture seule (`0001_init.sql`). */
@Serializable
data class AppConfigEntry(
    val key: String,
    val value: JsonElement,
    val version: Int
)

/**
 * Rafraîchit le cache local `app_config_cache` (`:core:database`, posé en Phase 2, jamais
 * rempli jusqu'ici). Le client ne modifie jamais `app_config` — lecture seule
 * (ARCHITECTURE-2.md §7). L'écriture dans le cache Room reste à l'appelant : ce module
 * ne dépend pas de `:core:database`.
 */
interface AppConfigRepository {
    suspend fun fetchAll(): Result<List<AppConfigEntry>>
}
