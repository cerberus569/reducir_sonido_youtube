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
import com.mauro.domain.util.SpectrumUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

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

    // --- Anti-parpadeo / anti-falso-positivo ---
    private val lastVolumeAction = AtomicLong(0L)
    private val cooldownMs = 400L
    // Cuántas capturas de FFT SEGUIDAS deben cumplir la condición antes de
    // actuar. Esto evita que un pico aislado (un golpe de batería, un ruido
    // corto) dispare un cambio de volumen. A la tasa máxima de captura del
    // Visualizer esto equivale a decidir en base a ~50-80ms de audio sostenido,
    // no a una sola muestra.
    private val requiredConsecutive = 3
    private var consecutiveReduce = 0
    private var consecutiveIncrease = 0

    // Cuánto sube o baja el volumen por cada detección. Antes esto se
    // calculaba como porcentaje de maxStream (15 * 0.15 = 2.25 -> 2 al bajar,
    // pero 15 * 0.10 = 1.5 -> 1 al subir), lo que hacía que bajar y subir NO
    // fueran simétricos y el resultado de la prueba no coincidiera con lo
    // esperado (ej.: de nivel 4 bajaba a 2 en vez de a 3). Ahora es siempre
    // exactamente 1 nivel en cada dirección.
    private var volumeStep = 1

    private val bandCount = 32
    private val bands = FloatArray(bandCount)

    private var audioStartTime = 0L
    private var wasPlaying = false

    @Volatile private var cachedVolume = 0

    private val _fftData = MutableStateFlow(ByteArray(0))
    val fftData: StateFlow<ByteArray> = _fftData

    // Se mantiene como red de seguridad: si algo (otra app, botones físicos)
    // sube el volumen por encima del máximo configurado, lo vuelve a bajar.
    // A diferencia de la versión anterior, YA NO hay un segundo bucle que
    // además intente subir/bajar el volumen mirando el propio nivel de
    // volumen (sin mirar el audio). Ese bucle competía con la detección de
    // tonos y podía deshacer o duplicar sus cambios.
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

        // Misma fórmula (logarítmica + suavizado) que dibuja las barras en
        // pantalla: lo que el usuario ve cruzar la línea roja es EXACTAMENTE
        // lo que se usa para decidir.
        val newBands = SpectrumUtils.computeBands(fft, bandCount, bands)
        newBands.copyInto(bands)

        val avgAllBands = bands.average().toFloat()

        // BAJAR: 50% o más de las bandas superan la línea roja (peakThreshold)
        val bandsAboveLimit = bands.count { it > peakThreshold }
        val bandsAbovePct = bandsAboveLimit.toFloat() / bandCount
        val reduceCondition = bandsAbovePct >= 0.50f

        // SUBIR: menos del 20% de las bandas superan el 20% del umbral, con
        // audio sonando de forma estable (no en silencio ni recién empezando)
        val minThreshold = peakThreshold * 0.20f
        val bandsAboveMin = bands.count { it > minThreshold }
        val bandsAboveMinPct = bandsAboveMin.toFloat() / bandCount

        val bandsWithAudio = bands.count { it > 0.02f }
        val audioIsPlaying = bandsWithAudio >= 6
        val now = System.currentTimeMillis()

        if (audioIsPlaying && !wasPlaying) audioStartTime = now
        wasPlaying = audioIsPlaying
        val audioEstablished = audioIsPlaying && (now - audioStartTime) >= 1000L

        val increaseCondition = !reduceCondition &&
                audioEstablished &&
                bandsAboveMinPct < 0.20f &&
                avgAllBands > 0.01f &&
                cachedVolume < maxLimit

        // Histéresis: exigir varias capturas seguidas antes de actuar
        consecutiveReduce = if (reduceCondition) consecutiveReduce + 1 else 0
        consecutiveIncrease = if (increaseCondition) consecutiveIncrease + 1 else 0

        val shouldReduce = consecutiveReduce >= requiredConsecutive
        val shouldIncrease = consecutiveIncrease >= requiredConsecutive

        if (!shouldReduce && !shouldIncrease) return
        if (now - lastVolumeAction.get() < cooldownMs) return

        lastVolumeAction.set(now)
        consecutiveReduce = 0
        consecutiveIncrease = 0

        mainHandler.post {
            val maxStream = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            cachedVolume = currentVol

            when {
                shouldReduce -> {
                    val newVol = (currentVol - volumeStep).coerceAtLeast(0)
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
                    val newVol = (currentVol + volumeStep).coerceAtMost(maxLimit)
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
                consecutiveReduce = 0
                consecutiveIncrease = 0
                wasPlaying = false
                audioStartTime = 0L
            }
        }
        intent?.getIntExtra("VOLUME_STEP", -1)?.let {
            if (it > 0) volumeStep = it
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
