package com.mauro.domain.repository

import com.mauro.domain.model.VolumeSettings
import kotlinx.coroutines.flow.Flow

interface VolumeRepository {
    fun getSettings(): Flow<VolumeSettings>
    suspend fun saveSettings(settings: VolumeSettings)
}