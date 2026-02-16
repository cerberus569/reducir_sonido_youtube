package com.mauro.domain.usecase

class GetVolumeSettingsUseCase(private val repository: VolumeRepository) {
    operator fun invoke() = repository.getSettings()
}

class SaveVolumeSettingsUseCase(private val repository: VolumeRepository) {
    suspend operator fun invoke(settings: VolumeSettings) = repository.saveSettings(settings)
}