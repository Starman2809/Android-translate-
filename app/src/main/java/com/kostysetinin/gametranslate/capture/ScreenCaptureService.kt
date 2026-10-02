package com.kostysetinin.gametranslate.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.kostysetinin.gametranslate.R
import com.kostysetinin.gametranslate.logic.LineReadiness
import com.kostysetinin.gametranslate.logic.StabilityGate
import com.kostysetinin.gametranslate.logic.TranslationMemory
import com.kostysetinin.gametranslate.ocr.OcrEngine
import com.kostysetinin.gametranslate.overlay.OverlayLine
import com.kostysetinin.gametranslate.overlay.SessionState
import com.kostysetinin.gametranslate.overlay.TranslationOverlay
import com.kostysetinin.gametranslate.prefs.SettingsStore
import com.kostysetinin.gametranslate.translate.GeminiTranslator
import com.kostysetinin.gametranslate.translate.TranslationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processing = AtomicBoolean(false)
    private val tornDown = AtomicBoolean(false)
    private val stability = StabilityGate()
    private val lineReadiness = LineReadiness()
    private val memory = TranslationMemory()
    private val ocr = OcrEngine()
    private val translator = GeminiTranslator()
    private val displayLock = Any()

    private lateinit var store: SettingsStore
    private lateinit var overlay: TranslationOverlay
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var paused = false
    private var lastRunAt = 0L
    private var lastStatus = ""

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        overlay = TranslationOverlay(this).also { panel ->
            panel.onPauseToggle = { setPaused(!paused) }
            panel.onStop = { mainHandler.post { shutdown(fromCallback = false) } }
        }
        captureThread = HandlerThread("screen-capture")
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown(fromCallback = false)
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_START -> startProjection(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdown(fromCallback = false)
        super.onDestroy()
    }

    private fun startProjection(intent: Intent) {
        if (mediaProjection != null || tornDown.get()) return
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = projectionData(intent)
        startAsForeground()
        if (code != Activity.RESULT_OK || data == null) {
            publish("Захват не подтверждён")
            shutdown(fromCallback = false)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            mediaProjection = manager.getMediaProjection(code, data)
        } catch (error: SecurityException) {
            Log.e(TAG, "MediaProjection denied", error)
            publish("Система отклонила захват экрана")
            shutdown(fromCallback = false)
            return
        }
        val projection = mediaProjection
        if (projection == null) {
            publish("Захват экрана недоступен")
            shutdown(fromCallback = false)
            return
        }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { shutdown(fromCallback = true) }
            }
        }, mainHandler)
        if (!createDisplayOnce(projection)) return
        overlay.attach()
        SessionState.running.value = true
        SessionState.paused.value = false
        publish("Ищу текст")
    }

    /**
     * Android allows one virtual display per screen-capture permission.
     * Creating it again, even after a display change, crashes the process.
     */
    private fun createDisplayOnce(projection: MediaProjection): Boolean {
        val size = screenSize()
        val reader = openReader(size)
        return try {
            virtualDisplay = projection.createVirtualDisplay(
                "GameTranslate",
                size.width,
                size.height,
                size.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
            imageReader = reader
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "Virtual display rejected", error)
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            mediaProjection = null
            runCatching { projection.stop() }
            shutdown(
                fromCallback = true,
                status = "Система отклонила захват. Нажмите «Начать перевод» и подтвердите его ещё раз.",
            )
            false
        }
    }

    private fun openReader(size: ScreenSize): ImageReader {
        return ImageReader.newInstance(
            size.width,
            size.height,
            android.graphics.PixelFormat.RGBA_8888,
            2,
        ).also { reader ->
            reader.setOnImageAvailableListener({ onImage(it) }, captureHandler)
        }
    }

    private fun onImage(reader: ImageReader) {
        val image = try {
            synchronized(displayLock) {
                if (reader !== imageReader) null else reader.acquireLatestImage()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to acquire frame", error)
            null
        } ?: return
        var claimed = false
        var frame: DecodedFrame? = null
        try {
            if (paused || tornDown.get()) return
            val settings = store.load()
            val now = SystemClock.elapsedRealtime()
            if (now - lastRunAt < settings.intervalMs) return
            if (!processing.compareAndSet(false, true)) return
            claimed = true
            lastRunAt = now
            val decoded = FrameDecoder.decode(image, settings.region)
            frame = decoded
            scope.launch {
                try {
                    processFrame(decoded, settings)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Frame failed", error)
                    publish("Сбой кадра")
                } finally {
                    if (!decoded.bitmap.isRecycled) decoded.bitmap.recycle()
                    processing.set(false)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Decode failed", error)
            if (claimed && frame == null) processing.set(false)
            publish("Не удалось прочитать кадр")
        } finally {
            image.close()
        }
    }

    private suspend fun processFrame(frame: DecodedFrame, settings: com.kostysetinin.gametranslate.prefs.TranslateSettings) {
        if (settings.apiKey.isBlank()) {
            publish("Вставьте ключ Gemini")
            return
        }
        val lines = ocr.read(
            bitmap = frame.bitmap,
            script = settings.script,
            cropLeft = frame.cropLeft,
            cropTop = frame.cropTop,
            scale = frame.scale,
        )
        if (lines.isEmpty()) {
            val blank = FrameDecoder.looksBlank(frame.bitmap)
            if (stability.observe(if (blank) "blank" else "empty")) {
                mainHandler.post { overlay.clearLines() }
                publish(
                    if (blank) "Чёрный кадр: игра может запрещать захват" else "Текст не найден",
                )
            }
            return
        }
        val ready = lineReadiness.ready(lines) { memory.normalize(it) }
        val missing = ready.filter { memory.get(settings.target.id, it.text) == null }
        if (missing.isNotEmpty()) {
            publish("Перевожу…")
            try {
                val translated = translator.translate(
                    lines = missing.map { it.text },
                    targetLanguageName = settings.target.promptName,
                    apiKey = settings.apiKey,
                    model = settings.model,
                )
                missing.zip(translated).forEach { (line, value) ->
                    memory.put(settings.target.id, line.text, value)
                }
                publish("На экране")
            } catch (error: TranslationException) {
                publish(error.message ?: "Ошибка перевода")
            }
        } else {
            publish("На экране")
        }
        val drawn = lines.map { line ->
            OverlayLine(
                box = line,
                translation = memory.get(settings.target.id, line.text),
                showOriginal = settings.showOriginal,
            )
        }
        mainHandler.post { overlay.update(drawn) }
    }

    private fun setPaused(value: Boolean) {
        paused = value
        SessionState.paused.value = value
        val label = if (value) "Пауза" else "Ищу текст"
        publish(label)
        mainHandler.post { overlay.setPaused(value) }
    }

    private fun publish(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        mainHandler.post {
            SessionState.status.value = text
            if (::overlay.isInitialized) overlay.setStatus(text)
        }
    }

    private fun shutdown(fromCallback: Boolean, status: String = "Остановлено") {
        if (!tornDown.compareAndSet(false, true)) return
        scope.cancel()
        captureHandler.post {
            synchronized(displayLock) {
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
                imageReader = null
            }
        }
        if (!fromCallback) {
            runCatching { mediaProjection?.stop() }
        }
        mediaProjection = null
        ocr.close()
        mainHandler.post {
            if (::overlay.isInitialized) overlay.detach()
            SessionState.running.value = false
            SessionState.paused.value = false
            SessionState.status.value = status
        }
        captureThread.quitSafely()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun screenSize(): ScreenSize {
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        }
        return ScreenSize(
            width = bounds.width().coerceAtLeast(1),
            height = bounds.height().coerceAtLeast(1),
            density = resources.configuration.densityDpi.coerceAtLeast(1),
        )
    }

    private fun projectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private data class ScreenSize(val width: Int, val height: Int, val density: Int)

    companion object {
        private const val TAG = "GameTranslate"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 41

        const val ACTION_START = "com.kostysetinin.gametranslate.START"
        const val ACTION_STOP = "com.kostysetinin.gametranslate.STOP"
        const val ACTION_PAUSE = "com.kostysetinin.gametranslate.PAUSE"
        const val ACTION_RESUME = "com.kostysetinin.gametranslate.RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
