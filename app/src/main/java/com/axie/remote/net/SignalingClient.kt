package com.axie.remote.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Phase-1+ signaling channel. Single WebSocket to the CRM relay carrying the JSON
 * protocol from SPEC.md §5: device→server `hello`/`frame`/`bye`, server→device
 * `tap`/`swipe`/`key`/`text`/`ping`.
 *
 * Phase 0 ships this unconnected (stub): [connect] is implemented and logs, frame
 * sending and input dispatch get wired in M3.
 */
class SignalingClient(
    private val serverUrl: String,
    private val deviceId: String,
    private val token: String,
    private val onInput: (JSONObject) -> Unit = {},
) {
    companion object {
        private const val TAG = "AxieRemote"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "signaling open -> sending hello")
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("deviceId", deviceId)
                    .put("token", token)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    try {
                        val msg = JSONObject(text)
                        if (msg.optString("type") == "ping") {
                            webSocket.send(JSONObject().put("type", "pong").toString())
                        } else {
                            onInput(msg)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "bad signaling message", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "signaling failure", t)
            }
        })
    }

    fun sendFrame(base64Jpeg: String, seq: Long, ts: Long) {
        val frame = JSONObject()
            .put("type", "frame")
            .put("codec", "mjpeg")
            .put("seq", seq)
            .put("ts", ts)
            .put("dataBase64", base64Jpeg)
        socket?.send(frame.toString())
    }

    fun close() {
        try {
            socket?.send(JSONObject().put("type", "bye").toString())
            socket?.close(1000, "bye")
        } catch (e: Exception) {
            Log.w(TAG, "signaling close failed", e)
        } finally {
            scope.cancel()
            client.dispatcher.executorService.shutdown()
        }
    }
}
