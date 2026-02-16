package com.mauro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VolumeViewModel(
    private val getUseCase: GetVolumeSettingsUseCase,
    private val saveUseCase: SaveVolumeSettingsUseCase
) : ViewModel() {

    val settings = getUseCase().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        VolumeSettings(2, 8, false)
    )

    fun updateSettings(min: Int, max: Int, active: Boolean) {
        viewModelScope.launch {
            saveUseCase(VolumeSettings(min, max, active))
        }
    }
}