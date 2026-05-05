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
    private val cooldownMs = 300L
    private val energyHistory = ArrayDeque<Float>(5)
    private var audioStartTime = 0L
    private var wasPlaying = false
    private var volumeCheckJob: Job? = null

    @Volatile private var cachedVolume = 0

    private val _fftData = MutableStateFlow(ByteArray(0))
    val fftData: StateFlow<ByteArray> = _fftData

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            cachedVolume = currentVol
            if (currentVol > maxLimit) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxLimit, 0)
                cachedVolume = maxLimit
                notifyOverlay(maxLimit)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cachedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

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

        startVolumeMonitor()
    }

    private fun startVolumeMonitor() {
        volumeCheckJob?.cancel()
        volumeCheckJob = scope.launch {
            while (isActive) {
                delay(500L)
                withContext(Dispatchers.Main) {
                    val maxStream = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    cachedVolume = currentVol

                    // Límite superior: peakThreshold * volumen máximo del sistema
                    val upperLimit = (maxStream * peakThreshold).toInt().coerceAtLeast(1)
                    // Límite inferior: 20% del upperLimit
                    val lowerLimit = (upperLimit * 0.20f).toInt().coerceAtLeast(0)

                    when {
                        currentVol > upperLimit -> {
                            val reduction = ((maxStream * 0.15f).toInt()).coerceAtLeast(1)
                            val newVol = (currentVol - reduction).coerceAtLeast(0)
                            if (newVol != currentVol) {
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                cachedVolume = newVol
                                updateNotification("↓ Vol $currentVol > límite $upperLimit → $newVol/$maxStream")
                                notifyOverlay(newVol)
                            }
                        }
                        currentVol < lowerLimit && currentVol < maxLimit -> {
                            val increase = ((maxStream * 0.10f).toInt()).coerceAtLeast(1)
                            val newVol = (currentVol + increase).coerceAtMost(maxLimit)
                            if (newVol != currentVol) {
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                cachedVolume = newVol
                                updateNotification("↑ Vol $currentVol < mínimo $lowerLimit → $newVol/$maxStream")
                                notifyOverlay(newVol)
                            }
                        }
                    }
                }
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
            vis.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED

            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    v: Visualizer, waveform: ByteArray, samplingRate: Int
                ) {}

                override fun onFftDataCapture(
                    v: Visualizer, fft: ByteArray, samplingRate: Int
                ) {
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
                bands[i] = (avg / 128f).coerceIn(0f, 1f)
            }
        }

        val instantPeak = bands.max()
        val avgAllBands = bands.average().toFloat()

        if (energyHistory.size >= 5) energyHistory.removeFirst()
        energyHistory.addLast(instantPeak)
        if (energyHistory.size < 2) return

        val now = System.currentTimeMillis()
        if (now - lastVolumeAction.get() < cooldownMs) return

        val smoothedPeak = energyHistory.average().toFloat()

        val bandsAboveLimit = bands.count { it > peakThreshold }
        val bandsAbovePct = bandsAboveLimit.toFloat() / bandCount

        val bandsAboveMin = bands.count { it > peakThreshold * 0.20f }
        val bandsAboveMinPct = bandsAboveMin.toFloat() / bandCount

        // BAJAR: 50% o más de bandas superan el umbral máximo
        val shouldReduce = bandsAbovePct >= 0.50f

        val bandsWithAudio = bands.count { it > 0.02f }
        val audioIsPlaying = bandsWithAudio >= 6

        if (audioIsPlaying && !wasPlaying) audioStartTime = now
        wasPlaying = audioIsPlaying

        val audioEstablished = audioIsPlaying && (now - audioStartTime) >= 1000L

        // SUBIR: menos del 20% de bandas superan el límite mínimo
        val shouldIncrease = !shouldReduce &&
                audioEstablished &&
                bandsAboveMinPct < 0.20f &&
                avgAllBands > 0.01f &&
                cachedVolume < maxLimit

        if (!shouldReduce && !shouldIncrease) return

        lastVolumeAction.set(now)
        mainHandler.post {
            val maxStream = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            cachedVolume = currentVol

            when {
                shouldReduce -> {
                    val reduction = ((maxStream * 0.15f).toInt()).coerceAtLeast(1)
                    val newVol = (currentVol - reduction).coerceAtLeast(0)
                    if (newVol != currentVol) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        cachedVolume = newVol
                        updateNotification(
                            "↓ ${(bandsAbovePct * 100).toInt()}% bandas sobre límite → vol: $newVol/$maxStream"
                        )
                        notifyOverlay(newVol)
                    }
                }
                shouldIncrease -> {
                    val increase = ((maxStream * 0.10f).toInt()).coerceAtLeast(1)
                    val newVol = (currentVol + increase).coerceAtMost(maxLimit)
                    if (newVol != currentVol) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        cachedVolume = newVol
                        updateNotification(
                            "↑ ${(bandsAboveMinPct * 100).toInt()}% bandas sobre mínimo → vol: $newVol/$maxStream"
                        )
                        notifyOverlay(newVol)
                    }
                }
            }
        }
    }

    private fun notifyOverlay(volume: Int) {
        try {
            val intent = Intent(this, VolumeOverlayService::class.java).apply {
                action = VolumeOverlayService.ACTION_UPDATE_VOLUME
                putExtra(VolumeOverlayService.EXTRA_VOLUME, volume)
            }
            startService(intent)
        } catch (e: Exception) { }
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
                wasPlaying = false
                audioStartTime = 0L
                startVolumeMonitor()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        volumeCheckJob?.cancel()
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