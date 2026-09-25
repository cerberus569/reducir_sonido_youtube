package com.mauro.domain.util

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Convierte los bytes crudos de FFT (Android Visualizer) en [bandCount] bandas
 * normalizadas entre 0f y 1f, con escala logarítmica y suavizado temporal.
 *
 * IMPORTANTE: esta es la ÚNICA función que debe usarse tanto para dibujar las
 * barras del analizador de espectro (SpectrumAnalyzer) como para decidir si se
 * sube o baja el volumen (VolumeLimiterService).
 *
 * Antes existían DOS fórmulas distintas para lo mismo:
 *  - La UI usaba escala logarítmica: log10(avg+1) / log10(50)
 *  - El servicio usaba escala lineal: avg / 128
 *
 * Eso provocaba que lo que el usuario veía cruzar la línea roja en pantalla
 * NO coincidiera con lo que realmente disparaba (o no) el cambio de volumen:
 * la app parecía "no reaccionar" aunque las barras tocaran el límite, porque
 * por dentro se estaba evaluando un número completamente distinto.
 */
object SpectrumUtils {

    fun computeBands(
        fft: ByteArray,
        bandCount: Int,
        prevData: FloatArray,
        smoothing: Float = 0.3f
    ): FloatArray {
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
                    magnitude += sqrt(real * real + imag * imag)
                    count++
                }
            }
            if (count > 0) {
                val avg = magnitude / count
                val raw = (log10((avg + 1).toDouble()) / log10(50.0))
                    .toFloat().coerceIn(0f, 1f)
                newData[i] = raw * (1f - smoothing) + prevData[i] * smoothing
            }
        }
        return newData
    }
}
