package com.mauro.readucirsonido

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mauro.data.repository.VolumeRepositoryImpl
import com.mauro.domain.usecase.GetVolumeSettingsUseCase
import com.mauro.domain.usecase.SaveVolumeSettingsUseCase
import com.mauro.presentation.VolumeViewModel
import com.mauro.presentation.VolumeViewModelFactory
import com.mauro.presentation.ui.VolumeScreen
import com.mauro.readucirsonido.service.VolumeLimiterService
import com.mauro.readucirsonido.ui.theme.Reducir_sonido_youtubeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = VolumeRepositoryImpl(applicationContext)
        val getUseCase = GetVolumeSettingsUseCase(repository)
        val saveUseCase = SaveVolumeSettingsUseCase(repository)
        val viewModelFactory = VolumeViewModelFactory(getUseCase, saveUseCase)

        setContent {
            Reducir_sonido_youtubeTheme {
                val vm: VolumeViewModel = viewModel(factory = viewModelFactory)

                // Iniciar el servicio cuando el switch está activo
                val state by vm.settings.collectAsState()
                if (state.isActive) {
                    val intent = Intent(this, VolumeLimiterService::class.java).apply {
                        putExtra("MAX_VOLUME", state.maxVolume)
                    }
                    startService(intent)
                } else {
                    stopService(Intent(this, VolumeLimiterService::class.java))
                }

                VolumeScreen(viewModel = vm)
            }
        }
    }
}