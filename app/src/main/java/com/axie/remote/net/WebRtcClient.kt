package com.axie.remote.net

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * WebRTC screen sharing (SPEC.md Phase 3): MediaProjection → H.264 video track
 * peer-to-peer to the browser viewer; remote input arrives over a reliable
 * RTCDataChannel using the exact same JSON commands as the WS relay protocol
 * (§5) — [ControlAccessibilityService] never learns the transport changed.
 *
 * Signaling = the CRM API's DB mailbox over plain HTTP polling
 * (`/rest/mobile/device/…` paths, token-authenticated). No WebSocket relay
 * server, no extra infrastructure: the API only brokers the few-second
 * handshake.
 *
 * Flow (viewer is the offerer — it only receives video):
 *   1. register(token)            → device known to the CRM
 *   2. loop: poll(waitMs=8000)    → first "offer" row = session request
 *   3. start capture (consent)    → createAnswer → POST signal(kind=answer)
 *   4. trickle ICE both ways      → kind=ice rows
 *   5. DataChannel "control"      → tap/swipe/key/text from the browser
 */
class WebRtcClient(
    private val context: Context,
    private val signalBase: String,
    private val rawToken: String,
    private val onInput: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "AxieRemoteWebRtc"
        private const val CONTROL_CHANNEL = "control"
        const val CAPTURE_PERMISSION_CODE = 4001
        private val STUN = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
    }

    private val http = SignalingHttp()
    private val executor = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var dataChannel: DataChannel? = null
    private var cursor: Long = System.currentTimeMillis() - 60_000
    private var running = false

    /**
     * One consent round-trip: resolves the MediaProjection result intent, then
     * wires the screen track into the peer connection and starts polling.
     */
    fun startSharing(activityIntentSender: (Intent, code: Int) -> Unit) {
        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = manager.createScreenCaptureIntent()
        activityIntentSender(intent, CAPTURE_PERMISSION_CODE)
    }

    /** Called from MainActivity once the user granted MediaProjection. */
    fun onCaptureGranted(resultCode: Int, data: Intent) {
        executor.execute { runSession(resultCode, data) }
    }

    private fun runSession(resultCode: Int, data: Intent) {
        try {
            running = true
            onState("starting")

            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(
                        org.webrtc.DefaultVideoEncoderFactory(
                            org.webrtc.EglBase.create().eglBaseContext,
                            true,
                            true,
                        ),
                    )
                    .setVideoDecoderFactory(
                        org.webrtc.DefaultVideoDecoderFactory(org.webrtc.EglBase.create().eglBaseContext),
                    )
                    .createPeerConnectionFactory()
            }

            val config = PeerConnection.RTCConfiguration(STUN).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val peer = factory!!.createPeerConnection(
                config,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        postSignal(
                            "ice",
                            JSONObject().put(
                                "candidate",
                                JSONObject().put("candidate", candidate.sdp)
                                    .put("sdpMid", candidate.sdpMid ?: "0")
                                    .put("sdpMLineIndex", candidate.sdpMLineIndex),
                            ),
                        )
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                        Log.i(TAG, "peer: $newState")
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED ->
                                onState("live")
                            PeerConnection.PeerConnectionState.FAILED ->
                                onError("Peer connection failed")
                            PeerConnection.PeerConnectionState.DISCONNECTED,
                            PeerConnection.PeerConnectionState.CLOSED,
                            -> onState("closed")
                            else -> Unit
                        }
                    }

                    override fun onDataChannel(dc: DataChannel) {
                        dataChannel = dc
                        observeChannel(dc)
                    }

                    // Remaining observer callbacks are no-ops by contract.
                    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                    override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
                    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
                    override fun onRenegotiationNeeded() = Unit
                },
            )!!.also { pc = it }

            // Control channel (ordered, reliable — input events must not drop).
            val init = DataChannel.Init().apply { ordered = true }
            dataChannel = peer.createDataChannel(CONTROL_CHANNEL, init).also {
                observeChannel(it)
            }

            // Screen capture → video track.
            capturer = ScreenCapturerAndroid(
                data,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection revoked by the user")
                        stop()
                    }
                },
            )
            videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread", org.webrtc.EglBase.create().eglBaseContext)
            capturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
            capturer!!.startCapture(720, 1600, 420)
            val track: VideoTrack =
                factory!!.createVideoTrack("screen", videoSource!!)
            peer.addTrack(track, listOf("screen"))

            onState("answering")
            pollLoop()
        } catch (error: Exception) {
            Log.e(TAG, "session failed", error)
            onError(error.message ?: "WebRTC session failed")
        }
    }

    private fun observeChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) = Unit
            override fun onStateChange() {
                Log.i(TAG, "control channel: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes)
                try {
                    onInput(JSONObject(text))
                } catch (error: Exception) {
                    Log.w(TAG, "bad control message: $text", error)
                }
            }
        })
    }

    /** Signaling pump: long-poll the mailbox, answer the first offer. */
    private fun pollLoop() {
        executor.execute {
            var answered = false
            while (running) {
                try {
                    val response = http.postJson(
                        "$signalBase/device/poll",
                        JSONObject()
                            .put("token", rawToken)
                            .put("after", cursor)
                            .put("waitMs", if (answered) 8_000 else 8_000)
                            .toString(),
                    ) ?: continue.also { Thread.sleep(1_500) }
                    val signals = response.optJSONArray("signals") ?: continue
                    for (index in 0 until signals.length()) {
                        val row = signals.getJSONObject(index)
                        cursor = maxOf(cursor, parseTimestamp(row.optString("createdAt")))
                        when (row.optString("kind")) {
                            "offer" -> if (!answered) {
                                answered = true
                                answerPeer(row.getJSONObject("payload").getJSONObject("sdp"))
                            }
                            "ice" -> addRemoteIce(row.optJSONObject("payload"))
                            "hangup" -> {
                                Log.i(TAG, "viewer hung up")
                                stop()
                                return@execute
                            }
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "poll failed: ${error.message}")
                    Thread.sleep(1_500)
                }
            }
        }
    }

    private fun answerPeer(sdp: JSONObject) {
        val peer = pc ?: return
        peer.setRemoteDescription(
            noopSdpObserver(),
            SessionDescription(SessionDescription.Type.OFFER, sdp.optString("sdp")),
        )
        val constraints = MediaConstraints()
        peer.createAnswer(object : SdpObserver by noopSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                peer.setLocalDescription(noopSdpObserver(), description)
                postSignal(
                    "answer",
                    JSONObject().put(
                        "sdp",
                        JSONObject().put("type", "answer").put("sdp", description.description),
                    ),
                )
                onState("connecting")
            }
        }, constraints)
    }

    private fun addRemoteIce(payload: JSONObject?) {
        val candidate = payload?.optJSONObject("candidate") ?: return
        pc?.addIceCandidate(
            IceCandidate(
                candidate.optString("sdpMid", "0"),
                candidate.optInt("sdpMLineIndex", 0),
                candidate.optString("candidate"),
            ),
        )
    }

    private fun postSignal(kind: String, payload: JSONObject) {
        executor.execute {
            try {
                http.postJson(
                    "$signalBase/device/signals",
                    JSONObject()
                        .put("token", rawToken)
                        .put("kind", kind)
                        .put("payload", payload)
                        .toString(),
                )
            } catch (error: Exception) {
                Log.w(TAG, "signal post failed: $kind", error)
            }
        }
    }

    /** Lightweight pairing ping so the CRM shows the phone as online. */
    fun register(onReady: (name: String) -> Unit) {
        executor.execute {
            try {
                val response = http.postJson(
                    "$signalBase/device/register",
                    JSONObject().put("token", rawToken).put("deviceId", android.os.Build.MODEL)
                        .toString(),
                )
                if (response?.optBoolean("ok") == true) {
                    onReady(response.optString("name"))
                }
            } catch (error: Exception) {
                Log.w(TAG, "register failed", error)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            postSignal("hangup", JSONObject().put("by", "device"))
        } catch (_: Exception) {
        }
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            dataChannel?.close()
            pc?.close()
        } catch (_: Exception) {
        }
        capturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        pc = null
        onState("closed")
    }

    private fun noopSdpObserver(): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "create failed: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.w(TAG, "set failed: $error")
        }
    }

    private fun parseTimestamp(iso: String): Long = runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrDefault(0L)

    /** Minimal JSON POST used for signaling. */
    inner class SignalingHttp {
        private val client = okhttp3.OkHttpClient()

        fun postJson(url: String, body: String): JSONObject? {
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} from $url")
                    return null
                }
                return try {
                    JSONObject(text)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
