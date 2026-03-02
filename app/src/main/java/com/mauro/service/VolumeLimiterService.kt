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
import kotlin.math.sqrt

class VolumeLimiterService : Service() {

    // Binder para que la UI pueda obtener los datos del Visualizer
    inner class LocalBinder : Binder() {
        fun getService(): VolumeLimiterService = this@VolumeLimiterService
    }

    private val binder = LocalBinder()
    private lateinit var audioManager: AudioManager
    private var maxLimit = 8
    private var peakThreshold = 0.75f
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // Visualizer compartido — lo usa el servicio para limitar Y la UI para mostrar barras
    private var visualizer: Visualizer? = null
    private val energyHistory = ArrayDeque<Float>(5)

    // FFT data expuesto para que la UI lo lea
    private val _fftData = MutableStateFlow(ByteArray(0))
    val fftData: StateFlow<ByteArray> = _fftData

    private val _waveData = MutableStateFlow(ByteArray(0))
    val waveData: StateFlow<ByteArray> = _waveData

    var captureSize = 0
        private set

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

        scope.launch {
            val repository = VolumeRepositoryImpl(applicationContext)
            val getUseCase = GetVolumeSettingsUseCase(repository)
            val settings = getUseCase().first()
            maxLimit = settings.maxVolume
            withContext(Dispatchers.Main) {
                startVisualizer()
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, observer
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1, createNotification())
        }
    }

    private fun startVisualizer() {
        try {
            val vis = Visualizer(0)
            captureSize = Visualizer.getCaptureSizeRange()[1]
            vis.captureSize = captureSize
            vis.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            vis.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer, waveform: ByteArray, samplingRate: Int
                    ) {
                        _waveData.value = waveform.copyOf()
                    }

                    override fun onFftDataCapture(
                        v: Visualizer, fft: ByteArray, samplingRate: Int
                    ) {
                        _fftData.value = fft.copyOf()

                        // Calcular energía para limitar volumen automáticamente
                        var totalEnergy = 0f
                        val half = fft.size / 2
                        for (i in 0 until half) {
                            val real = fft[i * 2].toFloat()
                            val imag = if (i * 2 + 1 < fft.size) fft[i * 2 + 1].toFloat() else 0f
                            totalEnergy += sqrt(real * real + imag * imag)
                        }
                        val normalizedEnergy = (totalEnergy / (half * 128f)).coerceIn(0f, 1f)

                        if (energyHistory.size >= 5) energyHistory.removeFirst()
                        energyHistory.addLast(normalizedEnergy)
                        val smoothedEnergy = energyHistory.average().toFloat()

                        if (smoothedEnergy > peakThreshold) {
                            handler.post {
                                val currentVol = audioManager.getStreamVolume(
                                    AudioManager.STREAM_MUSIC
                                )
                                if (currentVol > maxLimit) {
                                    val targetVol = (currentVol - 1).coerceAtLeast(maxLimit)
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC, targetVol, 0
                                    )
                                }
                            }
                        }
                    }
                },
                Visualizer.getMaxCaptureRate(),
                true,
                true
            )
            vis.enabled = true
            visualizer = vis
        } catch (e: Exception) {
            // Si falla el visualizer el ContentObserver sigue funcionando
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra("MAX_VOLUME", -1)?.let {
            if (it >= 0) {
                maxLimit = it
                getSystemService(NotificationManager::class.java).notify(1, createNotification())
            }
        }
        intent?.getFloatExtra("PEAK_THRESHOLD", -1f)?.let {
            if (it >= 0f) peakThreshold = it
        }
        return START_STICKY
    }

    // Retornar el binder para que la UI se conecte
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
        contentResolver.unregisterContentObserver(observer)
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
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
            .setContentText("Volumen máximo: $maxLimit")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .build()
    }
}