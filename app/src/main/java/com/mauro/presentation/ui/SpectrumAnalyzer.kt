package com.mauro.presentation.ui

import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun SpectrumAnalyzer(
    modifier: Modifier = Modifier,
    peakThreshold: Float = 0.75f,
    serviceFftFlow: StateFlow<ByteArray>? = null
) {
    val bandCount = 32
    var fftData by remember { mutableStateOf(FloatArray(bandCount) { 0f }) }
    var hasPermission by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("Iniciando...") }
    val peaks = remember { FloatArray(bandCount) { 0f } }
    val prevData = remember { FloatArray(bandCount) { 0f } }

    fun processBytes(fft: ByteArray): FloatArray {
        val newData = FloatArray(bandCount)
        if (fft.isEmpty()) return newData
        val step = maxOf(1, (fft.size / 2) / bandCount)
        for (i in 0 until bandCount) {
            val start = i * step
            var magnitude = 0f
            var count = 0
            for (j in start until minOf(start + step, fft.size / 2)) {
                val idx = j * 2
                if (idx + 1 < fft.size) {
                    val real = fft[idx].toFloat()
                    val imag = fft[idx + 1].toFloat()
                    magnitude += Math.sqrt(
                        (real * real + imag * imag).toDouble()
                    ).toFloat()
                    count++
                }
            }
            if (count > 0) {
                val avg = magnitude / count
                val raw = (Math.log10((avg + 1).toDouble()) /
                        Math.log10(50.0)).toFloat().coerceIn(0f, 1f)
                newData[i] = raw * 0.7f + prevData[i] * 0.3f
            }
        }
        return newData
    }

    fun updatePeaks(newData: FloatArray) {
        for (i in 0 until bandCount) {
            prevData[i] = newData[i]
            if (newData[i] > peaks[i]) peaks[i] = newData[i]
            else peaks[i] = (peaks[i] - 0.004f).coerceAtLeast(0f)
        }
    }

    if (serviceFftFlow != null) {
        // Servicio activo: leer FFT del servicio, NO crear Visualizer propio
        val fftBytes by serviceFftFlow.collectAsState()
        LaunchedEffect(fftBytes) {
            if (fftBytes.isEmpty()) return@LaunchedEffect
            hasPermission = true
            val newData = processBytes(fftBytes)
            updatePeaks(newData)
            fftData = newData.copyOf()
        }
    } else {
        // Servicio apagado: crear Visualizer propio solo para visualización
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                var visualizer: Visualizer? = null
                try {
                    visualizer = Visualizer(0)
                    val maxSize = Visualizer.getCaptureSizeRange()[1]
                    visualizer.captureSize = maxSize
                    visualizer.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                    visualizer.enabled = true
                    withContext(Dispatchers.Main) { hasPermission = true }

                    while (isActive) {
                        val fft = ByteArray(maxSize)
                        val waveform = ByteArray(maxSize)
                        val fftResult = visualizer.getFft(fft)
                        val waveResult = visualizer.getWaveForm(waveform)
                        val newData = FloatArray(bandCount)

                        if (fftResult == Visualizer.SUCCESS) {
                            val processed = processBytes(fft)
                            processed.copyInto(newData)
                        } else if (waveResult == Visualizer.SUCCESS) {
                            val step = maxOf(1, waveform.size / bandCount)
                            for (i in 0 until bandCount) {
                                var sum = 0f
                                for (j in 0 until step) {
                                    val idx = i * step + j
                                    if (idx < waveform.size) {
                                        sum += Math.abs(
                                            (waveform[idx].toInt() and 0xFF) - 128
                                        ).toFloat()
                                    }
                                }
                                val raw = ((sum / step) / 48f).coerceIn(0f, 1f)
                                newData[i] = raw * 0.7f + prevData[i] * 0.3f
                            }
                        }

                        updatePeaks(newData)
                        withContext(Dispatchers.Main) {
                            fftData = newData.copyOf()
                        }
                        delay(30L)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        hasPermission = false
                        statusMsg = "Error: ${e.javaClass.simpleName}"
                    }
                } finally {
                    visualizer?.enabled = false
                    visualizer?.release()
                }
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Analizador de Espectro",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FrequencyLabel("GRAVES", Color(0xFFFF5252))
                Spacer(modifier = Modifier.width(12.dp))
                FrequencyLabel("MEDIOS", Color(0xFFFFD740))
                Spacer(modifier = Modifier.width(12.dp))
                FrequencyLabel("AGUDOS", AquaGreen)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FrequencyLabel("LÍMITE", Color(0xFFFF1744))
                Spacer(modifier = Modifier.width(8.dp))
                Text("— línea roja punteada", color = Color.Gray, fontSize = 10.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (!hasPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(statusMsg, color = Color(0xFFFF5252), fontSize = 12.sp)
                }
            } else {
                Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val totalBarWidth = canvasWidth / bandCount
                    val barWidth = totalBarWidth * 0.75f
                    val limitY = canvasHeight - (peakThreshold * canvasHeight)

                    for (i in 0 until bandCount) {
                        val x = i * totalBarWidth
                        val barHeight = fftData[i] * canvasHeight
                        val peakY = canvasHeight - (peaks[i] * canvasHeight)

                        val color = when {
                            i < bandCount / 3 -> lerp(
                                Color(0xFFFF5252), Color(0xFFFFD740),
                                i.toFloat() / (bandCount / 3f)
                            )
                            i < (bandCount * 2) / 3 -> lerp(
                                Color(0xFFFFD740), AquaGreen,
                                (i - bandCount / 3f) / (bandCount / 3f)
                            )
                            else -> AquaGreen
                        }

                        if (barHeight > 0f) {
                            val barTop = canvasHeight - barHeight
                            if (barTop < limitY) {
                                drawRect(
                                    color = color.copy(alpha = 0.9f),
                                    topLeft = Offset(x, limitY),
                                    size = Size(barWidth, canvasHeight - limitY)
                                )
                                drawRect(
                                    color = Color(0xFFFF1744).copy(alpha = 0.9f),
                                    topLeft = Offset(x, barTop),
                                    size = Size(barWidth, limitY - barTop)
                                )
                            } else {
                                drawRect(
                                    color = color.copy(alpha = 0.9f),
                                    topLeft = Offset(x, barTop),
                                    size = Size(barWidth, barHeight)
                                )
                            }
                        }

                        if (barHeight > 4f) {
                            drawRect(
                                color = Color.White.copy(alpha = 0.25f),
                                topLeft = Offset(x, canvasHeight - barHeight),
                                size = Size(barWidth, 4f)
                            )
                        }

                        if (peaks[i] > 0.02f) {
                            drawRect(
                                color = Color.White.copy(alpha = 0.8f),
                                topLeft = Offset(x, peakY - 2f),
                                size = Size(barWidth, 3f)
                            )
                        }
                    }

                    drawLine(
                        color = Color(0xFFFF1744),
                        start = Offset(0f, limitY),
                        end = Offset(canvasWidth, limitY),
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("20 Hz", color = Color.Gray, fontSize = 10.sp)
                    Text("500 Hz", color = Color.Gray, fontSize = 10.sp)
                    Text("4 kHz", color = Color.Gray, fontSize = 10.sp)
                    Text("20 kHz", color = Color.Gray, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun FrequencyLabel(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}