package com.axie.remote.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.axie.remote.R
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase-0 foreground service (type `mediaProjection`).
 *
 * Owns the [MediaProjection] token, pumps frames from a [VirtualDisplay] into an
 * [ImageReader] and counts them. No network yet — Phase 1 reuses this exact surface
 * and ships JPEGs over the [com.axie.remote.net.SignalingClient] WebSocket.
 *
 * Revocation (user stops sharing in Quick Settings) arrives via [MediaProjection.Callback.onStop]
 * and tears everything down — a leaked VirtualDisplay keeps the mirror alive.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.axie.remote.capture.START"
        const val ACTION_STOP = "com.axie.remote.capture.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val TAG = "AxieRemote"
        private const val CHANNEL_ID = "axie_screen_share"
        private const val NOTIF_ID = 42
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private val frames = AtomicLong(0)

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection revoked by system — stopping service")
                stopCapture()
                stopSelf()
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                if (resultData == null) {
                    Log.e(TAG, "ACTION_START without projection data — ignoring")
                    stopSelf()
                } else {
                    startCapture(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) return // already running
        createChannel()
        val notification = buildNotification("Starting screen capture…")
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val (width, height, dpi) = captureSize()
        val thread = HandlerThread("AxieCapture").also { it.start() }
        worker = thread
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage()
            if (image != null) {
                image.close()
                val n = frames.incrementAndGet()
                if (n % 60L == 0L) {
                    Log.i(TAG, "captured $n frames (${width}x$height)")
                    updateNotification("Sharing screen — $n frames captured")
                }
            }
        }, Handler(thread.looper))

        virtualDisplay = projection.createVirtualDisplay(
            "AxieRemote",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
        Log.i(TAG, "capture started ${width}x$height dpi=$dpi")
        updateNotification("Sharing screen — waiting for frames…")
    }

    private fun stopCapture() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release VirtualDisplay failed", e)
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close ImageReader failed", e)
        }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop MediaProjection failed", e)
        }
        try {
            worker?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "stop worker failed", e)
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "capture stopped")
    }

    private data class CaptureSize(val width: Int, val height: Int, val dpi: Int)

    /** Prefer WindowMetrics (API 30+); fall back to DisplayMetrics on API 29. */
    private fun captureSize(): CaptureSize {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            // Cap width at 720p to bound Phase-0 CPU/memory; aspect preserved via height scale.
            val scale = 720f / bounds.width().coerceAtLeast(1)
            val w = 720
            val h = (bounds.height() * scale).toInt().coerceAtLeast(1)
            CaptureSize(w, h, resources.configuration.densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val scale = 720f / dm.widthPixels.coerceAtLeast(1)
            CaptureSize(720, (dm.heightPixels * scale).toInt().coerceAtLeast(1), dm.densityDpi)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
