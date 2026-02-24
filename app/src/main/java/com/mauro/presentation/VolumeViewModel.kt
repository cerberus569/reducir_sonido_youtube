package com.mauro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mauro.domain.model.VolumeSettings
import com.mauro.domain.usecase.GetVolumeSettingsUseCase
import com.mauro.domain.usecase.SaveVolumeSettingsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VolumeViewModel(
    getUseCase: GetVolumeSettingsUseCase,
    private val saveUseCase: SaveVolumeSettingsUseCase
) : ViewModel() {

    val settings = getUseCase().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        VolumeSettings(minVolume = 2, maxVolume = 8, isActive = false)
    )

    // Estado en tiempo real del volumen actual del celular
    val currentVolume = MutableStateFlow(0)

    fun updateSettings(min: Int, max: Int, active: Boolean) {
        viewModelScope.launch {
            saveUseCase(VolumeSettings(minVolume = min, maxVolume = max, isActive = active))
        }
    }

    fun updateCurrentVolume(volume: Int) {
        currentVolume.update { volume }
    }
}

class VolumeViewModelFactory(
    private val getUseCase: GetVolumeSettingsUseCase,
    private val saveUseCase: SaveVolumeSettingsUseCase
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VolumeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VolumeViewModel(getUseCase, saveUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}