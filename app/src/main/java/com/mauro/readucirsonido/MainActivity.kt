package com.mauro.readucirsonido



import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.lifecycle.viewmodel.compose.viewModel
import com.mauro.data.repository.VolumeRepositoryImpl
import com.mauro.domain.usecase.GetVolumeSettingsUseCase
import com.mauro.domain.usecase.SaveVolumeSettingsUseCase
import com.mauro.presentation.VolumeViewModel
import com.mauro.presentation.VolumeViewModelFactory
import com.mauro.presentation.ui.VolumeScreen // Asegúrate de que este import coincida
import com.mauro.readucirsonido.ui.theme.Reducir_sonido_youtubeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Instanciar dependencias manualmente
        val repository = VolumeRepositoryImpl(applicationContext)
        val getUseCase = GetVolumeSettingsUseCase(repository)
        val saveUseCase = SaveVolumeSettingsUseCase(repository)

        // 2. Crear el Factory para el ViewModel
        val viewModelFactory = VolumeViewModelFactory(getUseCase, saveUseCase)

        setContent {
            Reducir_sonido_youtubeTheme {
                // 3. Llamar a tu pantalla real
                val vm: VolumeViewModel = viewModel(factory = viewModelFactory)
                VolumeScreen(viewModel = vm)
            }
        }
    }
}