package com.mauro.readucirsonido.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.mauro.data.repository.VolumeRepositoryImpl
import com.mauro.domain.usecase.GetVolumeSettingsUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

class VolumeLimiterService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VolumeLimiterService = this@VolumeLimiterService
    }

    private val binder = LocalBinder()
    private lateinit var audioManager: AudioManager
    private var maxLimit = 8
    private var peakThreshold = 0.75f
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private val lastVolumeAction = AtomicLong(0L)
    private val cooldownMs = 2000L
    private val energyHistory = ArrayDeque<Float>(10)

    // StateFlow público para que la UI lea el FFT
    private val _fftData = MutableStateFlow(ByteArray(0))
    val fftData: StateFlow<ByteArray> = _fftData

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol > maxLimit) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxLimit, 0)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1, createNotification())
        }

        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, observer
        )

        scope.launch {
            try {
                val repository = VolumeRepositoryImpl(applicationContext)
                val getUseCase = GetVolumeSettingsUseCase(repository)
                val settings = getUseCase().first()
                maxLimit = settings.maxVolume
            } catch (e: Exception) { }

            delay(300L)
            withContext(Dispatchers.Main) {
                initVisualizer()
            }
        }
    }

    private fun initVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null

            val vis = Visualizer(0)
            vis.captureSize = Visualizer.getCaptureSizeRange()[1]
            vis.scalingMode = Visualizer.SCALING_MODE_NORMALIZED

            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    v: Visualizer, waveform: ByteArray, samplingRate: Int
                ) {}

                override fun onFftDataCapture(
                    v: Visualizer, fft: ByteArray, samplingRate: Int
                ) {
                    // Publicar FFT para que la UI lo lea
                    _fftData.value = fft.copyOf()
                    processFft(fft)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)

            vis.enabled = true
            visualizer = vis
        } catch (e: Exception) { }
    }

    private fun processFft(fft: ByteArray) {
        if (fft.isEmpty()) return

        val bandCount = 32
        val step = maxOf(1, (fft.size / 2) / bandCount)
        val bands = FloatArray(bandCount)

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
                bands[i] = (Math.log10((avg + 1).toDouble()) /
                        Math.log10(50.0)).toFloat().coerceIn(0f, 1f)
            }
        }

        // Cuántas bandas superan el umbral
        val bandsAbove = bands.count { it > peakThreshold }
        val bandsAbovePct = bandsAbove.toFloat() / bandCount  // 0.0 a 1.0

        // Cuántas bandas tienen audio presente (> ruido de fondo)
        val bandsWithAudio = bands.count { it > 0.02f }
        val bandsNearLimit = bands.count { it >= peakThreshold * 0.75f }

        // Guardar energía máxima en historial para detectar silencio
        val maxBand = bands.max()
        if (energyHistory.size >= 10) energyHistory.removeFirst()
        energyHistory.addLast(maxBand)
        if (energyHistory.size < 3) return

        val now = System.currentTimeMillis()
        if (now - lastVolumeAction.get() < cooldownMs) return

        // Bajar: 30% o más de bandas superan el umbral
        val shouldReduce = bandsAbovePct >= 0.30f

        // Subir: hay audio presente Y menos del 75% de bandas con audio están cerca del límite
        val audioPresent = bandsWithAudio >= 3
        val shouldIncrease = audioPresent &&
                (bandsNearLimit.toFloat() / bandCount) < 0.75f * 0.30f

        if (shouldReduce || shouldIncrease) {
            lastVolumeAction.set(now)
            mainHandler.post {
                val maxStream = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                if (shouldReduce) {
                    val reduction = ((maxStream * 0.10f).toInt()).coerceAtLeast(1)
                    val newVol = (currentVol - reduction).coerceAtLeast(0)
                    if (newVol != currentVol) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        updateNotification("↓ Reducido (${"%.0f".format(bandsAbovePct * 100)}% bandas sobre límite): $newVol/$maxStream")
                    }
                } else if (shouldIncrease) {
                    val increase = ((maxStream * 0.10f).toInt()).coerceAtLeast(1)
                    val newVol = (currentVol + increase).coerceAtMost(maxStream)
                    if (newVol != currentVol) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        updateNotification("↑ Aumentado: $newVol/$maxStream")
                    }
                }
            }
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(1, createNotificationWithText(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra("MAX_VOLUME", -1)?.let {
            if (it >= 0) {
                maxLimit = it
                getSystemService(NotificationManager::class.java).notify(1, createNotification())
            }
        }
        intent?.getFloatExtra("PEAK_THRESHOLD", -1f)?.let {
            if (it >= 0f) {
                peakThreshold = it
                energyHistory.clear()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) { }
        visualizer = null
        contentResolver.unregisterContentObserver(observer)
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotification() = createNotificationWithText("Volumen máximo: $maxLimit")

    private fun createNotificationWithText(text: String): Notification {
        val channelId = "volume_limiter_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Mitigador de Volumen",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mitigador de Volumen Activo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .build()
    }
}