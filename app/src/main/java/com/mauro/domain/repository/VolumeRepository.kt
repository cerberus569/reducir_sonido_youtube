package com.mauro.domain.repository

import kotlinx.coroutines.flow.Flow

interface VolumeRepository {
    fun getSettings(): Flow<VolumeSettings>
    suspend fun saveSettings(settings: VolumeSettings)
}