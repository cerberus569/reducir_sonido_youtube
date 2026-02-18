package com.mauro.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mauro.domain.model.VolumeSettings
import com.mauro.domain.repository.VolumeRepository
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class VolumeRepositoryImpl(private val context: Context) : VolumeRepository {
    private val MIN_KEY = intPreferencesKey("min_vol")
    private val MAX_KEY = intPreferencesKey("max_vol")
    private val ACTIVE_KEY = booleanPreferencesKey("is_active")

    override fun getSettings() = context.dataStore.data.map { p ->
        VolumeSettings(p[MIN_KEY] ?: 2, p[MAX_KEY] ?: 8, p[ACTIVE_KEY] ?: false)
    }

    override suspend fun saveSettings(settings: VolumeSettings) {
        context.dataStore.edit { p ->
            p[MIN_KEY] = settings.minVolume
            p[MAX_KEY] = settings.maxVolume
            p[ACTIVE_KEY] = settings.isActive
        }
    }
}