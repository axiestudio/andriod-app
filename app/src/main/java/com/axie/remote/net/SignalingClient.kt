package com.axie.remote.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Signaling channel: single WebSocket to the CRM relay carrying the JSON
 * protocol from SPEC.md §5 — device→server `hello`/`frame`/`bye`,
 * server→device `tap`/`swipe`/`key`/`text`/`longpress`/`drag`/`scroll`/`ping`.
 *
 * URL handling ("URL accessibility" fix): the relay URL typed in-app is
 * *normalized* (bare host → ws://, http→ws, https→wss) and *validated* before
 * any socket is opened, so a malformed URL can never crash the app — the
 * caller gets `null` from [normalizeUrl] and shows an inline error instead.
 * [testConnection] lets the UI prove reachability with a short-lived socket
 * before a sharing session starts.
 */
class SignalingClient(
    serverUrl: String,
    private val deviceId: String,
    private val token: String,
    private val screenWidth: Int = 0,
    private val screenHeight: Int = 0,
    private val screenDpi: Int = 0,
    private val onInput: (JSONObject) -> Unit = {},
    private val onState: (State) -> Unit = {},
) {
    enum class State { IDLE, CONNECTING, OPEN, FAILED, CLOSED }

    companion object {
        private const val TAG = "AxieRemote"
        private const val MAX_RECONNECTS = 5

        /**
         * Normalize user input into a ws(s):// URL, or null when unusable.
         * Accepts: `host:port/path`, `http(s)://…`, `ws(s)://…`.
         * The scheme match is case-insensitive (only the scheme is folded);
         * mirrors the web viewer's normalizeRelayUrl exactly.
         */
        fun normalizeUrl(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            var url = trimmed
            val schemeEnd = url.indexOf("://")
            url = if (schemeEnd < 0) {
                "ws://$url"
            } else {
                val scheme = url.substring(0, schemeEnd).lowercase()
                val rest = url.substring(schemeEnd + 3)
                when (scheme) {
                    "http" -> "ws://$rest"
                    "https" -> "wss://$rest"
                    else -> "$scheme://$rest"
                }
            }
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) return null
            return try {
                Request.Builder().url(url).build()
                url
            } catch (e: Exception) {
                Log.w(TAG, "invalid relay URL: $raw", e)
                null
            }
        }

        /** Quick reachability probe for the "Test connection" button. */
        fun testConnection(
            rawUrl: String?,
            token: String,
            timeoutMs: Long = 8000,
            callback: (ok: Boolean, message: String) -> Unit,
        ) {
            val url = normalizeUrl(rawUrl)
            if (url == null) {
                callback(false, "Invalid URL — use ws(s)://host[:port][/path]")
                return
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            var settled = false
            fun settle(ok: Boolean, msg: String) {
                if (settled) return
                settled = true
                try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
                callback(ok, msg)
            }
            val request = try {
                Request.Builder().url(url).build()
            } catch (e: Exception) {
                callback(false, "Invalid URL: ${e.message}")
                return
            }
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    settle(true, "Reachable (HTTP ${response.code})")
                    webSocket.close(1000, "probe")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    settle(false, t.message ?: "Connection failed")
                }
            })
            // Safety timeout in case neither callback fires.
            Thread({
                try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) {}
                if (!settled) {
                    try { socket.cancel() } catch (_: Exception) {}
                    settle(false, "Timed out — check host, port and network")
                }
            }, "AxieProbeTimeout").apply { isDaemon = true }.start()
        }
    }

    private val normalizedUrl: String? = normalizeUrl(serverUrl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private val reconnects = AtomicInteger(0)
    @Volatile private var closedByApp = false

    fun connect() {
        val url = normalizedUrl
        if (url == null) {
            Log.w(TAG, "connect aborted: invalid relay URL")
            onState(State.FAILED)
            return
        }
        closedByApp = false
        openSocket(url)
    }

    private fun openSocket(url: String) {
        onState(State.CONNECTING)
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            Log.w(TAG, "bad relay URL at connect", e)
            onState(State.FAILED)
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "signaling open -> sending hello")
                reconnects.set(0)
                onState(State.OPEN)
                try {
                    val hello = JSONObject()
                        .put("type", "hello")
                        .put("deviceId", deviceId)
                        .put("token", token)
                    if (screenWidth > 0 && screenHeight > 0) {
                        hello.put(
                            "screen", JSONObject()
                                .put("width", screenWidth)
                                .put("height", screenHeight)
                                .put("dpi", screenDpi)
                        )
                    }
                    // Token in query is also accepted by relays; body is primary.
                    webSocket.send(hello.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "hello failed", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    try {
                        val msg = JSONObject(text)
                        when (msg.optString("type")) {
                            "ping" -> webSocket.send(
                                JSONObject().put("type", "pong").toString()
                            )
                            "bye" -> Log.i(TAG, "server ended session")
                            else -> onInput(msg)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "bad signaling message", e)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "signaling closing $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "signaling closed $code $reason")
                if (!closedByApp) scheduleReconnect(url)
                else onState(State.CLOSED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "signaling failure (HTTP ${response?.code})", t)
                if (!closedByApp) scheduleReconnect(url)
                else onState(State.FAILED)
            }
        })
    }

    private fun scheduleReconnect(url: String) {
        val attempt = reconnects.incrementAndGet()
        if (attempt > MAX_RECONNECTS) {
            Log.w(TAG, "giving up after $MAX_RECONNECTS reconnects")
            onState(State.FAILED)
            return
        }
        val backoff = (1000L * attempt).coerceAtMost(8000L)
        Log.i(TAG, "reconnect $attempt/$MAX_RECONNECTS in ${backoff}ms")
        scope.launch {
            delay(backoff)
            if (!closedByApp) openSocket(url)
        }
    }
    fun sendFrame(base64Jpeg: String, seq: Long, ts: Long) {
        val ws = socket ?: return
        try {
            val frame = JSONObject()
                .put("type", "frame")
                .put("codec", "mjpeg")
                .put("seq", seq)
                .put("ts", ts)
                .put("dataBase64", base64Jpeg)
            ws.send(frame.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendFrame failed", e)
        }
    }

    /**
     * Physical pose for the viewer's aesthetic device mockup (SPEC §5.1 v1.2).
     * Degrees, rotation-vector derived; the relay forwards these to viewers
     * latest-only, like frames.
     */
    fun sendOrientation(azimuth: Float, pitch: Float, roll: Float, rotation: Int) {
        val ws = socket ?: return
        try {
            val pose = JSONObject()
                .put("type", "orientation")
                .put("azimuth", azimuth.toDouble())
                .put("pitch", pitch.toDouble())
                .put("roll", roll.toDouble())
                .put("rotation", rotation)
                .put("ts", System.currentTimeMillis())
            ws.send(pose.toString())
        } catch (e: Exception) {
            Log.w(TAG, "sendOrientation failed", e)
        }
    }

    fun close() {
        closedByApp = true
        try {
            socket?.send(JSONObject().put("type", "bye").toString())
            socket?.close(1000, "bye")
        } catch (e: Exception) {
            Log.w(TAG, "signaling close failed", e)
        } finally {
            onState(State.CLOSED)
            scope.cancel()
            try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
        }
    }
}
