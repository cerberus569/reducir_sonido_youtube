package com.mauro.readucirsonido

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mauro.data.repository.VolumeRepositoryImpl
import com.mauro.domain.usecase.GetVolumeSettingsUseCase
import com.mauro.domain.usecase.SaveVolumeSettingsUseCase
import com.mauro.presentation.VolumeViewModel
import com.mauro.presentation.VolumeViewModelFactory
import com.mauro.presentation.ui.VolumeScreen
import com.mauro.readucirsonido.service.VolumeLimiterService
import com.mauro.readucirsonido.ui.theme.Reducir_sonido_youtubeTheme
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {

    private lateinit var audioManager: AudioManager
    private lateinit var volumeObserver: ContentObserver
    private lateinit var vm: VolumeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val repository = VolumeRepositoryImpl(applicationContext)
        val getUseCase = GetVolumeSettingsUseCase(repository)
        val saveUseCase = SaveVolumeSettingsUseCase(repository)
        val viewModelFactory = VolumeViewModelFactory(getUseCase, saveUseCase)

        // Observer que detecta cuando el usuario cambia el volumen
        // con los botones físicos del celular
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                vm.updateCurrentVolume(currentVol)
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver
        )

        setContent {
            Reducir_sonido_youtubeTheme {
                vm = viewModel(factory = viewModelFactory)
                val state by vm.settings.collectAsState()

                // Arrancar o detener el servicio según el switch
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

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(volumeObserver)
        super.onDestroy()
    }
}