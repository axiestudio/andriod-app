package com.axie.remote.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.axie.remote.R
import com.axie.remote.control.ControlAccessibilityService
import com.axie.remote.net.SignalingClient
import com.axie.remote.net.WebRtcClient
import com.axie.remote.sensors.OrientationReporter
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service (type `mediaProjection`) owning the share-screen session.
 *
 * Phase 1: the [VirtualDisplay] feeds an [ImageReader]; frames are JPEG-encoded
 * (≈720p, ~3 fps, quality 60, latest-only drop policy) and shipped as `frame`
 * messages over [SignalingClient]. Viewer input (`tap`/`swipe`/`key`/`text`/
 * `longpress`/`drag`/`scroll`) arrives on the same socket and is dispatched to
 * [ControlAccessibilityService] — so **screen share and remote control run in
 * one session** (goal.md §§2–3, session chain §6). Throttled pose samples
 * ([OrientationReporter][com.axie.remote.sensors.OrientationReporter]) ride the
 * same socket while watched, driving the viewer's 3D device mockup.
 *
 * Empty relay URL = offline preview mode: capture + count frames locally with
 * no network (still proves permissions/lifecycle).
 *
 * Revocation (user stops sharing in Quick Settings) arrives via
 * [MediaProjection.Callback.onStop] and tears everything down — a leaked
 * VirtualDisplay keeps the mirror alive.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.axie.remote.capture.START"
        const val ACTION_STOP = "com.axie.remote.capture.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_DEVICE_TOKEN = "deviceToken"
        /** Transport selector: "relay" (MJPEG WS, default) or "webrtc" (P2P). */
        const val EXTRA_MODE = "mode"
        const val MODE_RELAY = "relay"
        const val MODE_WEBRTC = "webrtc"
        private const val TAG = "AxieRemote"
        private const val CHANNEL_ID = "axie_screen_share"
        private const val NOTIF_ID = 42
        private const val JPEG_QUALITY = 60
        private const val MIN_FRAME_INTERVAL_MS = 330L // ~3 fps, LAN-friendly

        /** Observable by MainActivity for the session-state row. */
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var relayState: String = "off" // off|connecting|live|failed
            private set
        /**
         * Session telemetry for the in-app stats line (same process, no IPC).
         * Frames actually uploaded, viewers reported by the relay (null = the
         * relay stays silent), and whether the pose reporter is sampling.
         */
        @Volatile var poseOn: Boolean = false
            private set
        @Volatile var viewerCount: Int? = null
            private set
        private val sentFrames = AtomicLong(0)
        val streamedCount: Long get() = sentFrames.get()
        val watcherCount: Int? get() = viewerCount
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var signaling: SignalingClient? = null
    private var webRtc: WebRtcClient? = null
    private var orientation: OrientationReporter? = null
    private val frames = AtomicLong(0)
    private var lastSentAt = 0L
    // viewerCount lives on the companion (session telemetry); its contract:
    // null = relay doesn't report (old relay) → keep sending. Zero = skip
    // JPEG encode + upload; local capture keeps counting for a clean rejoin.

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
                    startCapture(
                        resultCode,
                        resultData,
                        intent.getStringExtra(EXTRA_SERVER_URL).orEmpty(),
                        intent.getStringExtra(EXTRA_DEVICE_TOKEN).orEmpty(),
                        intent.getStringExtra(EXTRA_MODE) ?: MODE_RELAY,
                    )
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        serverUrl: String,
        token: String,
        mode: String = MODE_RELAY,
    ) {
        if (mediaProjection != null || webRtc != null) return // already running
        sentFrames.set(0)
        createChannel()
        val notification = buildNotification("Starting screen capture…")
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        if (mode == MODE_WEBRTC) {
            // P2P transport (SPEC.md §5.4): the FGS is now up, which is the
            // Android 14 prerequisite for getMediaProjection — ScreenCapturerAndroid
            // creates the projection internally from the consent intent.
            startWebRtcSession(resultData, serverUrl, token)
            return
        }

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
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                frames.incrementAndGet()
                encodeAndSend(image, width, height)
            } catch (e: Exception) {
                Log.w(TAG, "frame encode failed", e)
            } finally {
                try { image.close() } catch (_: Exception) {}
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
        isRunning = true

        // Signaling: share screen AND receive control on one socket.
        val normalized = SignalingClient.normalizeUrl(serverUrl.ifBlank { null })
        if (normalized == null) {
            relayState = "off"
            Log.i(TAG, "capture started ${width}x$height dpi=$dpi (offline preview — no relay URL)")
            updateNotification("Sharing screen locally — no relay URL set")
        } else {
            relayState = "connecting"
            viewerCount = null // unknown until this relay reports presence
            val deviceId = deviceId()
            signaling = SignalingClient(
                serverUrl = normalized,
                deviceId = deviceId,
                token = token,
                screenWidth = width,
                screenHeight = height,
                screenDpi = dpi,
                onInput = { msg ->
                    if (msg.optString("type") == "viewers") {
                        viewerCount = msg.optInt("count", 0).coerceAtLeast(0)
                        Log.i(TAG, "viewers present: $viewerCount")
                    } else {
                        val ok = ControlAccessibilityService.handleRemoteCommand(msg)
                        Log.i(TAG, "remote ${msg.optString("type")} -> ${if (ok) "dispatched" else "FAILED"}")
                        if (!ok && !ControlAccessibilityService.isEnabled(this)) {
                            Log.w(TAG, "input dropped: Axie Control accessibility service is OFF")
                        }
                    }
                },
                onState = { state ->
                    relayState = when (state) {
                        SignalingClient.State.OPEN -> "live"
                        SignalingClient.State.CONNECTING -> "connecting"
                        SignalingClient.State.FAILED -> "failed"
                        else -> "off"
                    }
                    when (state) {
                        SignalingClient.State.OPEN ->
                            updateNotification("Live — sharing + remote control ready")
                        SignalingClient.State.FAILED ->
                            updateNotification("Sharing locally — relay unreachable")
                        else -> {}
                    }
                },
            ).also { it.connect() }
            Log.i(TAG, "capture started ${width}x$height dpi=$dpi relay=$normalized")
            updateNotification("Sharing screen — connecting to relay…")
            // Pose stream for the viewer's device mockup: the reporter samples
            // on its own throttled thread; samples only leave the phone while
            // at least one viewer is watching (viewerCount == 0 skips, same as
            // frames; null on old relays keeps sending for compatibility).
            orientation = OrientationReporter(this) { azimuth, pitch, roll, rotation ->
                if (viewerCount == 0) return@OrientationReporter
                signaling?.sendOrientation(azimuth, pitch, roll, rotation)
            }.also { poseOn = it.start() }
        }
    }

    /** Latest-only MJPEG: throttle, JPEG-encode, base64, send. Drops when offline. */
    private fun encodeAndSend(image: android.media.Image, width: Int, height: Int) {
        val sock = signaling ?: run {
            val n = frames.get()
            if (n % 60L == 0L) {
                Log.i(TAG, "captured $n frames (${width}x$height, offline)")
                updateNotification("Sharing screen locally — $n frames")
            }
            return
        }
        if (viewerCount == 0) {
            // Relay reports nobody watching: skip JPEG encode + upload entirely.
            // Local capture keeps counting (frames) so rejoin is seamless.
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSentAt < MIN_FRAME_INTERVAL_MS) return // drop, never queue
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = if (rowPadding > 0) width + rowPadding / pixelStride else width
        var bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        var cropped: Bitmap? = null
        try {
            val src = if (paddedWidth != width) {
                cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                cropped
            } else bitmap
            val out = ByteArrayOutputStream(width * height / 4)
            src.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val seq = sentFrames.incrementAndGet()
            sock.sendFrame(b64, seq, System.currentTimeMillis())
            lastSentAt = now
            if (seq % 30L == 0L) {
                Log.i(TAG, "streamed $seq frames (${width}x$height q=$JPEG_QUALITY)")
                updateNotification("Live — $seq frames streamed")
            }
        } finally {
            try { cropped?.recycle() } catch (_: Exception) {}
            try { bitmap.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * P2P session hosted by THIS foreground service (SPEC.md §5.4). The FGS
     * with type mediaProjection is already up — the documented Android 14
     * prerequisite before getMediaProjection — so the capturer can create the
     * projection from the consent intent.
     */
    private fun startWebRtcSession(resultData: Intent, serverUrl: String, token: String) {
        isRunning = true
        relayState = "connecting"
        viewerCount = null
        val httpsBase = serverUrl.trim().trimEnd('/').let {
            when {
                it.startsWith("https://") -> it
                it.startsWith("http://") -> it
                it.startsWith("wss://") -> "https://" + it.removePrefix("wss://")
                it.startsWith("ws://") -> "http://" + it.removePrefix("ws://")
                else -> it
            }
        }
        webRtc = WebRtcClient(
            context = applicationContext,
            signalBase = httpsBase,
            rawToken = token,
            onInput = { msg ->
                val ok = ControlAccessibilityService.handleRemoteCommand(msg)
                Log.i(TAG, "remote ${msg.optString("type")} -> ${if (ok) "dispatched" else "FAILED"}")
            },
            onState = { state ->
                relayState = when (state) {
                    "live" -> "live"
                    "connecting", "answering", "starting" -> "connecting"
                    "failed" -> "failed"
                    else -> "off"
                }
                when (state) {
                    "live" -> updateNotification("Live — P2P sharing + remote control ready")
                    "failed" -> updateNotification("P2P session failed")
                    "closed" -> { stopCapture(); stopSelf() }
                    else -> {}
                }
            },
            onError = { message ->
                Log.e(TAG, "WebRTC error: $message")
                relayState = "failed"
                updateNotification("P2P error: $message")
            },
        )
        webRtc?.register { name -> Log.i(TAG, "registered with CRM as \"$name\"") }
        webRtc?.onCaptureGranted(Activity.RESULT_OK, resultData)
        updateNotification("P2P sharing — waiting for the viewer…")
        Log.i(TAG, "WebRTC session started signalBase=$httpsBase")
    }

    private fun deviceId(): String {
        val androidId = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }
        return "${Build.MODEL ?: "android"}-${androidId?.take(8) ?: "dev"}"
    }

    private fun stopCapture() {
        try { webRtc?.stop() } catch (e: Exception) {
            Log.w(TAG, "stop WebRTC session failed", e)
        }
        webRtc = null
        try { orientation?.stop() } catch (e: Exception) {
            Log.w(TAG, "stop orientation reporter failed", e)
        }
        orientation = null
        poseOn = false
        try { signaling?.close() } catch (e: Exception) {
            Log.w(TAG, "close signaling failed", e)
        }
        signaling = null
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
        isRunning = false
        relayState = "off"
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "capture stopped")
    }

    private data class CaptureSize(val width: Int, val height: Int, val dpi: Int)

    /** Prefer WindowMetrics (API 30+); fall back to DisplayMetrics on API 29. */
    private fun captureSize(): CaptureSize {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            // Cap width at 720p to bound CPU/memory; aspect preserved via height scale.
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
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
