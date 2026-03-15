package com.mauro.readucirsonido.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import android.media.AudioManager

class VolumeOverlayService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var containerView: FrameLayout? = null

    private val volumeState = mutableIntStateOf(0)
    private val visibleState = mutableStateOf(false)
    private val dismissedState = mutableStateOf(false)

    private var hideJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_UPDATE_VOLUME = "com.mauro.readucirsonido.UPDATE_VOLUME"
        const val EXTRA_VOLUME = "volume"
        const val ACTION_DISMISS = "com.mauro.readucirsonido.OVERLAY_DISMISS"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 2
    }

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        super.onCreate()

        startForeground(NOTIF_ID, createNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Mostrar volumen actual al arrancar
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        volumeState.intValue = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        visibleState.value = true

        createOverlay()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay de Volumen",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Overlay de volumen activo")
            .setContentText("Mostrando nivel de volumen en pantalla")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun createOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }

        // Aplicar owners al FrameLayout contenedor — esto es lo que faltaba
        val container = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@VolumeOverlayService)
            setViewTreeViewModelStoreOwner(this@VolumeOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VolumeOverlayService)
        }

        val composeView = ComposeView(this).apply {
            setContent {
                VolumeOverlayContent(
                    volume = volumeState.intValue,
                    visible = visibleState.value && !dismissedState.value,
                    onDismiss = {
                        dismissedState.value = true
                        visibleState.value = false
                        val intent = Intent(ACTION_DISMISS).apply {
                            setPackage(packageName)
                        }
                        sendBroadcast(intent)
                    }
                )
            }
        }

        container.addView(composeView)
        windowManager.addView(container, params)
        containerView = container
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_VOLUME -> {
                val newVol = intent.getIntExtra(EXTRA_VOLUME, -1)
                if (newVol >= 0 && !dismissedState.value) {
                    volumeState.intValue = newVol
                    visibleState.value = true
                    hideJob?.cancel()
                    hideJob = scope.launch {
                        delay(2500L)
                        visibleState.value = false
                    }
                }
            }
            ACTION_DISMISS -> {
                dismissedState.value = true
                visibleState.value = false
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        containerView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        containerView = null
        super.onDestroy()
    }
}

@Composable
private fun VolumeOverlayContent(
    volume: Int,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)) + scaleIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)) + scaleOut(animationSpec = tween(200))
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .background(Color(0xEE1E1E1E), RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.TopEnd)
                    .background(Color(0xFF555555), CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "×",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔊", fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "$volume",
                    color = Color(0xFF00BFA5),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}