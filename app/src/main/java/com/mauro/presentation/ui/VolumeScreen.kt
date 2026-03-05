package com.mauro.presentation.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mauro.presentation.VolumeViewModel
import com.mauro.readucirsonido.service.VolumeLimiterService

val AquaGreen = Color(0xFF00BFA5)
val DarkBg = Color(0xFF121212)
val CardBg = Color(0xFF1E1E1E)

@Composable
fun VolumeScreen(viewModel: VolumeViewModel) {
    val state by viewModel.settings.collectAsState()
    val currentVolume by viewModel.currentVolume.collectAsState()
    val context = LocalContext.current

    var peakThreshold by remember { mutableFloatStateOf(0.75f) }
    var boundService by remember { mutableStateOf<VolumeLimiterService?>(null) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Conectar al servicio para leer su FFT
    DisposableEffect(state.isActive) {
        if (state.isActive) {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    boundService = (binder as? VolumeLimiterService.LocalBinder)?.getService()
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    boundService = null
                }
            }
            val intent = Intent(context, VolumeLimiterService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            onDispose {
                try { context.unbindService(connection) } catch (e: Exception) {}
                boundService = null
            }
        } else {
            boundService = null
            onDispose {}
        }
    }

    // Enviar umbral al servicio cuando cambia
    LaunchedEffect(peakThreshold, state.isActive) {
        if (state.isActive) {
            val intent = Intent(context, VolumeLimiterService::class.java).apply {
                putExtra("PEAK_THRESHOLD", peakThreshold)
            }
            context.startService(intent)
        }
    }

    val animatedVolume by animateFloatAsState(
        targetValue = currentVolume.toFloat(),
        animationSpec = tween(durationMillis = 300),
        label = "volumeAnim"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Mitigador de Volumen",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (hasAudioPermission) {
            SpectrumAnalyzer(
                peakThreshold = peakThreshold,
                serviceFftFlow = boundService?.fftData
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Volumen actual
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Volumen Actual", color = Color.Gray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "$currentVolume / 15",
                    color = AquaGreen,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF333333))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedVolume / 15f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    currentVolume > state.maxVolume -> Color(0xFFFF5252)
                                    currentVolume < 3 -> Color(0xFFFFD740)
                                    else -> AquaGreen
                                }
                            )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    when {
                        currentVolume > state.maxVolume -> "⚠ Reduciendo volumen..."
                        currentVolume == 0 -> "🔇 Silenciado"
                        currentVolume <= 4 -> "🔈 Volumen bajo"
                        currentVolume <= 9 -> "🔉 Volumen medio"
                        else -> "🔊 Volumen alto"
                    },
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Volumen máximo
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Volumen Máximo", color = Color.White, fontWeight = FontWeight.Medium)
                    Text("${state.maxVolume}", color = AquaGreen, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = state.maxVolume.toFloat(),
                    onValueChange = {
                        viewModel.updateSettings(
                            min = 0, max = it.toInt(), active = state.isActive
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
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Límite de picos
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Límite de Picos", color = Color.White, fontWeight = FontWeight.Medium)
                    Text(
                        "${(peakThreshold * 100).toInt()}%",
                        color = Color(0xFFFF1744),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Superar: baja 10% · Por debajo: sube 5%",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = peakThreshold,
                    onValueChange = { peakThreshold = it },
                    valueRange = 0.3f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFF1744),
                        activeTrackColor = Color(0xFFFF1744),
                        inactiveTrackColor = Color(0xFFFF1744).copy(alpha = 0.3f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Switch servicio
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Servicio Activo", color = Color.White, fontWeight = FontWeight.Medium)
                    Text(
                        if (state.isActive) "Controlando volumen" else "Servicio detenido",
                        color = if (state.isActive) AquaGreen else Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = state.isActive,
                    onCheckedChange = {
                        viewModel.updateSettings(min = 0, max = state.maxVolume, active = it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AquaGreen,
                        checkedTrackColor = AquaGreen.copy(alpha = 0.5f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}