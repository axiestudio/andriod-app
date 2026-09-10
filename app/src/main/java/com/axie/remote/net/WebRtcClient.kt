package com.axie.remote.net

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
 * P2P screen sharing (SPEC.md Phase 3) with a **pipeline/session split** —
 * the simple architecture:
 *
 * ```
 * Sharing ON  (one system consent, once)
 *   └─ CAPTURE PIPELINE (lives until Sharing OFF)
 *        projection → VideoSource → screen track (always warm)
 *        └─ AUTO-ACCEPT LOOP: long-poll the mailbox
 *             viewer offer → new PeerConnection + answer, instantly, silently
 *             viewer gone  → session closed, pipeline stays up → next offer
 * Sharing OFF → everything down (Idle)
 * ```
 *
 * The viewer's Connect is therefore ALWAYS answered: no per-session prompt,
 * no per-session consent (the capture consent given at Sharing ON covers the
 * pipeline; WebRTC sessions are just viewers plugging into it). Stop on
 * either side ends the current session; only the Sharing toggle stops the
 * pipeline.
 *
 * Signaling = the CRM API's DB mailbox over plain HTTP polling
 * (`/rest/mobile/device/…`, token-authenticated). The mailbox is OPAQUE:
 * every poll returns all live rows for the device side (30 s TTL,
 * delete-on-read) and this client dedupes by row id — no clocks, no cursors.
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
        const val CAPTURE_PERMISSION_CODE = 4001
        private val STUN = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
        private const val MAX_PENDING_ICE = 50
    }

    private val http = SignalingHttp()
    private val executor = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null

    // ---- capture pipeline (Sharing ON ⇒ always warm) -----------------------
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var projectionCb: MediaProjection.Callback? = null

    // ---- per-viewer session -------------------------------------------------
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    /** Viewer ICE that arrived before this session's remote description. */
    private val pendingIce = ArrayDeque<JSONObject>()

    /** Row ids already processed (opaque mailbox — rows can replay). */
    private val seenRowIds = HashSet<String>()
    private var running = false

    /** True while a viewer session is open (pc != null). */
    private fun inSession(): Boolean = pc != null

    /**
     * One consent round-trip (MainActivity → system dialog). The RESULT wires
     * the pipeline via [onCaptureGranted].
     */
    fun startSharing(activityIntentSender: (Intent, code: Int) -> Unit) {
        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activityIntentSender(manager.createScreenCaptureIntent(), CAPTURE_PERMISSION_CODE)
    }

    /** Called once the user granted MediaProjection: pipeline up, loop on. */
    fun onCaptureGranted(resultCode: Int, data: Intent) {
        executor.execute {
            try {
                startPipeline(resultCode, data)
                // Must precede pollLoop: this flag IS the loop's lifeline.
                running = true
                onState("ready")
                pollLoop()
            } catch (error: Exception) {
                running = false
                Log.e(TAG, "pipeline failed", error)
                onError(error.message ?: "capture pipeline failed")
            }
        }
    }

    /**
     * Builds the always-on capture pipeline: PeerConnectionFactory, the
     * MediaProjection-backed screen capturer and its VideoSource. The FGS
     * (type mediaProjection) is already up — the Android 14 prerequisite —
     * so the capturer can create the projection from the consent intent.
     */
    private fun startPipeline(resultCode: Int, data: Intent) {
        if (videoSource != null) return // pipeline already warm
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

        // Revocation callback registered BEFORE capture starts so "Stop
        // streaming" from Quick Settings is never missed.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection revoked by the user")
                stopAll()
            }
        }
        projectionCb = callback
        capturer = ScreenCapturerAndroid(data, callback)
        videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
        surfaceHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            org.webrtc.EglBase.create().eglBaseContext,
        )
        capturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(720, 1600, 420)
        Log.i(TAG, "capture pipeline up")
    }

    /**
     * Auto-accept pump: long-poll the mailbox while the pipeline is up.
     * offer → [openSession] (silently); ice → buffered/applied; hangup →
     * [endSession] (pipeline stays up). No prompts, ever.
     */
    private fun pollLoop() {
        while (running) {
            try {
                val response = http.postJson(
                    "$signalBase/device/poll",
                    JSONObject()
                        .put("token", rawToken)
                        .put("waitMs", 8_000)
                        .toString(),
                ) ?: continue.also { Thread.sleep(1_500) }
                val signals = response.optJSONArray("signals") ?: continue
                for (index in 0 until signals.length()) {
                    val row = signals.getJSONObject(index)
                    if (!seenRowIds.add(row.optString("id"))) continue
                    when (row.optString("kind")) {
                        "offer" -> openSession(row)
                        "ice" -> onRemoteIce(row.optJSONObject("payload"))
                        "ping" -> postSignal(
                            "pong",
                            row.optJSONObject("payload") ?: JSONObject(),
                        )
                        "hangup" -> {
                            Log.i(TAG, "viewer hung up")
                            endSession()
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "poll failed: ${error.message}")
                Thread.sleep(1_500)
            }
        }
    }

    /**
     * AUTO-ACCEPT: a fresh PeerConnection per viewer, fed by the warm screen
     * track. setRemoteDescription → (onSetSuccess) → createAnswer → post.
     * A new offer while a session is open replaces it (stale viewer pages
     * must not lock the phone).
     */
    private fun openSession(offerRow: JSONObject) {
        val factory = this.factory ?: return
        if (inSession()) {
            Log.i(TAG, "new offer — replacing the current session")
            endSession()
        }
        val config = PeerConnection.RTCConfiguration(STUN).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val peer = factory.createPeerConnection(
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
                        PeerConnection.PeerConnectionState.CONNECTED -> onState("live")
                        PeerConnection.PeerConnectionState.FAILED -> endSession()
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.CLOSED,
                        -> endSession()
                        else -> Unit
                    }
                }

                override fun onDataChannel(dc: DataChannel) {
                    dataChannel = dc
                    observeChannel(dc)
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
                override fun onRenegotiationNeeded() = Unit
            },
        ) ?: run {
            onError("PeerConnection creation failed")
            return
        }
        pc = peer

        // The pipeline's VideoSource feeds this session's track (sources can
        // fan out to multiple tracks over the service's lifetime).
        val track: VideoTrack = factory.createVideoTrack("screen", videoSource!!)
        peer.addTrack(track, listOf("screen"))

        val sdp = offerRow.optJSONObject("payload")?.optJSONObject("sdp")
        if (sdp == null) {
            Log.w(TAG, "offer row without sdp — skipped")
            endSession()
            return
        }
        onState("connecting")
        peer.setRemoteDescription(
            object : SdpObserver by noopSdpObserver() {
                override fun onSetSuccess() {
                    // The remote description is set: buffered viewer ICE is
                    // legal now; the answer is created on the same callback.
                    drainPendingIce(peer)
                    peer.createAnswer(
                        object : SdpObserver by noopSdpObserver() {
                            override fun onCreateSuccess(description: SessionDescription) {
                                peer.setLocalDescription(noopSdpObserver(), description)
                                postSignal(
                                    "answer",
                                    JSONObject().put(
                                        "sdp",
                                        JSONObject()
                                            .put("type", "answer")
                                            .put("sdp", description.description),
                                    ),
                                )
                                Log.i(TAG, "offer AUTO-ACCEPTED — answer posted")
                            }
                        },
                        MediaConstraints(),
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp.optString("sdp")),
        )
    }

    /** Viewer ICE before the session's remote description must be buffered. */
    private fun onRemoteIce(payload: JSONObject?) {
        val candidate = payload?.optJSONObject("candidate") ?: return
        val peer = pc
        if (peer == null) {
            if (pendingIce.size < MAX_PENDING_ICE) pendingIce.addLast(candidate)
            return
        }
        applyIce(peer, candidate)
    }

    private fun drainPendingIce(peer: PeerConnection) {
        while (pendingIce.isNotEmpty()) {
            applyIce(peer, pendingIce.removeFirst())
        }
    }

    private fun applyIce(peer: PeerConnection, candidate: JSONObject) {
        peer.addIceCandidate(
            IceCandidate(
                candidate.optString("sdpMid", "0"),
                candidate.optInt("sdpMLineIndex", 0),
                candidate.optString("candidate"),
            ),
        )
    }

    /** Closes the current viewer session; the pipeline and loop stay up. */
    private fun endSession() {
        try {
            dataChannel?.close()
        } catch (_: Exception) {}
        dataChannel = null
        try {
            pc?.close()
        } catch (_: Exception) {}
        pc = null
        pendingIce.clear()
        if (running) onState("ready")
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

    private fun postSignal(kind: String, payload: JSONObject) {
        // DIRECT CALL — do NOT route through the executor: the poll loop
        // occupies it for the whole session, and queueing answers/ICE behind
        // it starves them forever (the phone polled offers but its answers
        // never left — the multi-release "stuck on the offer" root cause).
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

    /** Registers with the CRM (presence, sticky device id, pending count). */
    fun register(onReady: (name: String) -> Unit) {
        // Direct: the keepalive timer thread calls this — executor-queueing
        // would starve it behind the poll loop.
        try {
            val response = http.postJson(
                "$signalBase/device/register",
                JSONObject()
                    .put("token", rawToken)
                    .put("deviceId", android.os.Build.MODEL)
                    .toString(),
            )
            if (response?.optBoolean("ok") == true) {
                onReady(response.optString("name"))
            }
        } catch (error: Exception) {
            Log.w(TAG, "register failed", error)
        }
    }

    /** Foreground-service keepalive: presence while the pipeline is up. */
    fun keepAlive() {
        register { }
    }

    /** Sends a control message to the viewer (pong, telemetry). */
    fun sendControl(message: JSONObject) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try {
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(message.toString().toByteArray()), false))
        } catch (error: Exception) {
            Log.w(TAG, "sendControl failed", error)
        }
    }

    /**
     * Full teardown — ONLY the Sharing toggle (or a projection revocation)
     * gets here: session down, capture pipeline down, loop off.
     */
    fun stopAll() {
        val wasRunning = running
        running = false
        // Tell a connected viewer the phone stopped (web session → idle).
        if (inSession()) {
            try {
                http.postJson(
                    "$signalBase/device/signals",
                    JSONObject()
                        .put("token", rawToken)
                        .put("kind", "hangup")
                        .put("payload", JSONObject().put("by", "device"))
                        .toString(),
                )
            } catch (_: Exception) {}
        }
        endSessionQuietly()
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {}
        try {
            surfaceHelper?.dispose()
        } catch (_: Exception) {}
        surfaceHelper = null
        try {
            videoSource?.dispose()
        } catch (_: Exception) {}
        videoSource = null
        capturer = null
        projectionCb = null
        if (wasRunning) onState("closed")
    }

    private fun endSessionQuietly() {
        try {
            dataChannel?.close()
        } catch (_: Exception) {}
        dataChannel = null
        try {
            pc?.close()
        } catch (_: Exception) {}
        pc = null
        pendingIce.clear()
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

    inner class SignalingHttp {
        private val client = okhttp3.OkHttpClient()

        fun postJson(url: String, body: String): JSONObject? {
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
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
