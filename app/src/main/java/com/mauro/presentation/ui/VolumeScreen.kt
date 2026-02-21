package com.mauro.presentation.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mauro.presentation.VolumeViewModel

val AquaGreen = Color(0xFF00BFA5)

@Composable
fun VolumeScreen(viewModel: VolumeViewModel) {
    val state by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Mitigador de Volumen",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            "Volumen Máximo Permitido: ${state.maxVolume}",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(12.dp))

        Slider(
            value = state.maxVolume.toFloat(),
            onValueChange = {
                viewModel.updateSettings(
                    min = 0,
                    max = it.toInt(),
                    active = state.isActive
                )
            },
            valueRange = 0f..15f,
            steps = 14,
            colors = SliderDefaults.colors(
                thumbColor = AquaGreen,
                activeTrackColor = AquaGreen,
                inactiveTrackColor = AquaGreen.copy(alpha = 0.3f)
            )
        )

        Spacer(modifier = Modifier.height(48.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Servicio Activo", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.isActive,
                onCheckedChange = {
                    viewModel.updateSettings(
                        min = 0,
                        max = state.maxVolume,
                        active = it
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AquaGreen,
                    checkedTrackColor = AquaGreen.copy(alpha = 0.5f)
                )
            )
        }
    }
}