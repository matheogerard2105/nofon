package com.nofon.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache local de `app_config` (Supabase), exposée en lecture seule au client
 * (ARCHITECTURE-2.md §7). Ce cache permet de lire les seuils déjà connus (taille de
 * groupe, plafonds de caution...) hors ligne ou avant le premier rafraîchissement
 * réseau ; il n'est jamais la source de vérité, seulement une copie locale.
 */
@Entity(tableName = "app_config_cache")
data class AppConfigCacheEntity(
    @PrimaryKey val key: String,
    val value: String,
    val fetchedAtMillis: Long
)
