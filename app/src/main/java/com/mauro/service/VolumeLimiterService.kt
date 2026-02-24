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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.mauro.data.repository.VolumeRepositoryImpl
import com.mauro.domain.usecase.GetVolumeSettingsUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class VolumeLimiterService : Service() {

    private lateinit var audioManager: AudioManager
    private var maxLimit = 8
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        }

        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, observer
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra("MAX_VOLUME", -1)?.let {
            if (it >= 0) {
                maxLimit = it
                // Actualizar la notificación con el nuevo límite
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(1, createNotification())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val channelId = "volume_limiter_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Mitigador de Volumen",
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