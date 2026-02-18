package com.mauro.domain.usecase

import com.mauro.domain.model.VolumeSettings
import com.mauro.domain.repository.VolumeRepository

class GetVolumeSettingsUseCase(private val repository: VolumeRepository) {
    operator fun invoke() = repository.getSettings()
}

class SaveVolumeSettingsUseCase(private val repository: VolumeRepository) {
    suspend operator fun invoke(settings: VolumeSettings) = repository.saveSettings(settings)
}