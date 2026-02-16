package com.mauro.service

import android.app.Service
import android.content.Context
import android.media.AudioManager
import android.database.ContentObserver
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.content.Intent

class VolumeLimiterService : Service() {
    private lateinit var audioManager: AudioManager
    private var maxLimit = 8
    private var minLimit = 2

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol > maxLimit) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxLimit, 0)
            } else if (currentVol < minLimit) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minLimit, 0)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        // Aquí deberías cargar los valores reales del DataStore
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }
}