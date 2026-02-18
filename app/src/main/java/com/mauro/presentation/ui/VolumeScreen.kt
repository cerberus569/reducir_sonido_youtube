package com.mauro.presentation.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mauro.presentation.VolumeViewModel

@Composable
fun VolumeScreen(viewModel: VolumeViewModel) {
    val state by viewModel.settings.collectAsState()

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Mitigador de Volumen Nocturno", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(24.dp))

        Text("Volumen Máximo Permitido: ${state.maxVolume}")
        Slider(
            value = state.maxVolume.toFloat(),
            onValueChange = { viewModel.updateSettings(state.minVolume, it.toInt(), state.isActive) },
            valueRange = 0f..15f,
            steps = 14
        )

        Text("Volumen Mínimo: ${state.minVolume}")
        Slider(
            value = state.minVolume.toFloat(),
            onValueChange = { viewModel.updateSettings(it.toInt(), state.maxVolume, state.isActive) },
            valueRange = 0f..15f,
            steps = 14
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Servicio Activo")
            Switch(
                checked = state.isActive,
                onCheckedChange = { viewModel.updateSettings(state.minVolume, state.maxVolume, it) }
            )
        }
    }
}