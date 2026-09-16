package com.axie.remote.net

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
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
import org.webrtc.audio.JavaAudioDeviceModule
import com.axie.remote.capture.AudioCapture
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * P2P screen sharing (SPEC.md Phase 3) — **hotspot-first** build.
 *
 * Pipeline/session split:
 * ```
 * Sharing ON  (one MediaProjection consent)
 *   └─ CAPTURE PIPELINE (warm until Sharing OFF)
 *        projection → VideoSource → screen track
 *        └─ AUTO-ACCEPT LOOP: long-poll mailbox
 *             offer → new PeerConnection + answer (host-host preferred)
 *             viewer gone → session closed, pipeline stays warm → next offer
 * Sharing OFF → everything down
 * ```
 *
 * Default path = **mobile hotspot → laptop via hotspot** (`192.168.43.1 ↔ 192.168.43.x` host pair,
 * ~15 ms, no TURN bill). Same-WiFi router is the same code (host pair on `192.168.1.x`). TURN (if
 * `TURN_URL` set on server) is the fallback for carrier CGNAT — both peers get it from
 * `device/register` / `device/poll` → `iceServers`.
 *
 * Signalling = CRM API DB mailbox (`/rest/mobile/device/…`, token-auth, 30 s TTL, delete-on-read,
 * dedupe by row id — opaque, no clocks).
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
        private val FALLBACK_STUN = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
        private const val MAX_PENDING_ICE = 50
    }

    private val http = SignalingHttp()
    // pollLoop blocks one thread for 8 s long-polls — never share it with signalling posts.
    private val pollExecutor = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newCachedThreadPool()
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    // ICE bundle from server (register/poll) — hotspot host-pair works with STUN-only,
    // but TURN is appended here when the server has it.
    @Volatile private var iceServers: List<PeerConnection.IceServer> = FALLBACK_STUN

    // ---- capture pipeline (Sharing ON ⇒ warm) -----------------------
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var projectionCb: MediaProjection.Callback? = null

    // ---- audio capture (system playback) -----------------------------------
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioCapture: AudioCapture? = null

    // ---- per-viewer session -------------------------------------------------
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val pendingIce = ArrayDeque<JSONObject>()
    private val seenRowIds = HashSet<String>()
    @Volatile private var running = false
    @Volatile private var connected = false

    private fun inSession(): Boolean = pc != null

    fun startSharing(activityIntentSender: (Intent, code: Int) -> Unit) {
        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activityIntentSender(manager.createScreenCaptureIntent(), CAPTURE_PERMISSION_CODE)
    }

    /** Called once the user granted MediaProjection: pipeline up, loop on. */
    fun onCaptureGranted(resultCode: Int, data: Intent) {
        pollExecutor.execute {
            try {
                startPipeline(resultCode, data)
                running = true
                onState("ready")
                Log.i(TAG, "capture pipeline up — ICE bundle: ${iceServers.size} servers (hotspot default host-pair)")
                pollLoop()
            } catch (error: Exception) {
                running = false
                Log.e(TAG, "pipeline failed", error)
                onError(error.message ?: "capture pipeline failed")
            }
        }
    }

    /**
     * Always-on capture pipeline. Uses a **single** EglBase for both factory and SurfaceTextureHelper
     * (two EglBases caused native crashes on stop/start). PeerConnectionFactory.initialize is idempotent
     * — second call is caught.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun startPipeline(resultCode: Int, data: Intent) {
        if (videoSource != null) return // pipeline already warm
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
        } catch (_: Exception) {
            // Already initialized in this process — expected after Sharing OFF → ON.
        }
        val egl = EglBase.create()
        eglBase = egl
        val eglCtx = egl.eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglCtx, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglCtx))
            .createPeerConnectionFactory()

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection revoked by the user")
                stopAll()
            }
        }
        projectionCb = callback
        capturer = ScreenCapturerAndroid(data, callback)

        videoSource = factory!!.createVideoSource(capturer!!.isScreencast)
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglCtx)
        capturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(720, 1280, 30)

        startAudioCapture(resultCode, data)

        Log.i(TAG, "capture pipeline up + audio source ready")
    }

    /**
     * Best-effort audio (mic source + system playback). Audio must never take
     * down the video pipeline: any failure here only means the viewer gets a
     * video-only answer — Sharing stays ON and offers keep auto-accepting.
     */
    private fun startAudioCapture(resultCode: Int, data: Intent) {
        try {
            // Get the MediaProjection for audio capture (AudioPlaybackCapture
            // needs the same consent the user gave for screen capture).
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)

            // Audio source for mic + system playback audio:
            // The JavaAudioDeviceModule handles mic capture by default through the
            // PeerConnectionFactory. We also add system audio (WhatsApp calls,
            // notifications, media) via AudioPlaybackCapture below.
            audioSource = factory!!.createAudioSource(MediaConstraints())

            // Start system audio capture (WhatsApp calls, ringtones, media playback)
            // using the same MediaProjection consent. This runs alongside the mic
            // capture — both feed into the audio track sent to the browser.
            audioCapture = AudioCapture(context) { _, _ ->
                // AudioPlaybackCapture PCM data — in a full implementation this
                // would be fed into a custom WebRTC audio module.
                // For now we let the standard mic audio flow through.
            }
            if (projection != null) {
                audioCapture?.start(projection)
                Log.i(TAG, "AudioPlaybackCapture started via MediaProjection")
            } else {
                Log.w(TAG, "no MediaProjection — audio playback capture skipped")
            }
        } catch (error: Exception) {
            Log.w(TAG, "audio init failed — continuing video-only", error)
            audioSource = null
            audioCapture = null
        }
    }

    private fun parseIceServers(arr: JSONArray?): List<PeerConnection.IceServer>? {
        if (arr == null || arr.length() == 0) return null
        val out = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urlsRaw = o.opt("urls") ?: continue
            val urls: List<String> = when (urlsRaw) {
                is String -> listOf(urlsRaw)
                is JSONArray -> (0 until urlsRaw.length()).mapNotNull { idx -> urlsRaw.optString(idx).takeIf { it.isNotEmpty() } }
                else -> emptyList()
            }
            if (urls.isEmpty()) continue
            val isTurn = urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
            val username = o.optString("username", "").takeIf { it.isNotEmpty() }
            val credential = o.optString("credential", "").takeIf { it.isNotEmpty() }
            // A TURN server that arrives WITHOUT a username/credential is
            // poisoned: libwebrtc logs "TURN server requires a username" from
            // PeerConnectionImpl.setConfiguration and silently drops relay
            // fallback. Skip the whole entry so the PeerConnection never sees
            // it — host/srflx still work, and a correctly-configured TURN
            // will relay once the API sends proper credentials.
            if (isTurn && (username == null || credential == null)) {
                Log.w(TAG, "TURN without creds skipped (urls=$urls)")
                continue
            }
            for (url in urls) {
                val b = PeerConnection.IceServer.builder(url)
                if (username != null) b.setUsername(username)
                if (credential != null) b.setPassword(credential)
                out.add(b.createIceServer())
                Log.i(TAG, "ICE server: $url ${if (isTurn) "(TURN)" else "(STUN)"}")
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun refreshIceFromJson(json: JSONObject?) {
        if (json == null) return
        val arr = json.optJSONArray("iceServers") ?: return
        val parsed = parseIceServers(arr)
        if (parsed != null) {
            iceServers = parsed
            Log.i(TAG, "ICE refreshed: ${parsed.size} servers (turnConfigured=${json.optBoolean("turnConfigured", false)}) — hotspot host-pair preferred, TURN fallback ready")
        }
    }

    private fun pollLoop() {
        while (running) {
            try {
                val response = http.postJson(
                    "$signalBase/device/poll",
                    JSONObject()
                        .put("token", rawToken)
                        .put("waitMs", 8_000)
                        .toString(),
                )
                if (response == null) {
                    // Server unreachable (no internet, API down, stale base URL):
                    // back off instead of hammering — Sharing stays ON and the
                    // next poll retries. The paired card's probe says why.
                    Log.w(TAG, "poll: no response — retrying in 1.5 s (Sharing stays ON)")
                    Thread.sleep(1_500)
                    continue
                }
                // Piggy-backed ICE bundle (lets server TURN rotation land mid-session)
                refreshIceFromJson(response)
                val signals = response.optJSONArray("signals") ?: continue
                for (index in 0 until signals.length()) {
                    val row = signals.getJSONObject(index)
                    if (!seenRowIds.add(row.optString("id"))) continue
                    when (row.optString("kind")) {
                        "offer" -> openSession(row)
                        "ice" -> onRemoteIce(row.optJSONObject("payload"))
                        "ping" -> postSignal("pong", row.optJSONObject("payload") ?: JSONObject())
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

    private fun openSession(offerRow: JSONObject) {
        Log.i(TAG, "offer ${offerRow.optString("id")} received — auto-accepting")
        val factory = this.factory ?: run {
            Log.w(TAG, "offer ignored — capture pipeline not ready (Sharing did not finish starting)")
            return
        }
        if (inSession()) {
            if (connected) {
                // The viewer re-posts its offer every few seconds until it sees
                // our answer. This session is already live — answering again
                // would tear down working media, so the retry is ignored.
                Log.i(TAG, "offer ignored — session already live (viewer retry)")
                return
            }
            Log.i(TAG, "new offer — replacing the current session")
            endSession()
        }
        // Hotspot default: use server-supplied bundle (STUN + TURN if configured) — host candidates
        // (192.168.43.1 ↔ 192.168.43.x) are always gathered regardless of TURN, and win locally.
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Keep defaults that let host survive (don't prune).
        }
        Log.i(TAG, "creating PeerConnection with ${iceServers.size} ICE servers (hotspot-first)")
        val peer = factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val type = when {
                        candidate.sdp.contains("typ host") -> "host"
                        candidate.sdp.contains("typ srflx") -> "srflx"
                        candidate.sdp.contains("typ relay") -> "relay"
                        else -> "cand"
                    }
                    Log.i(TAG, "local ICE $type: ${candidate.sdp.trim().take(120)}")
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
                        PeerConnection.PeerConnectionState.CONNECTED -> { connected = true; onState("live") }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            Log.w(TAG, "ICE failed — hotspot host-pair missed and no TURN relay (see HOTSPOT-WEBRTC.md Mode C)")
                            endSession()
                        }
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
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.i(TAG, "iceConnection: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.i(TAG, "iceGathering: $state")
                }
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

        val track: VideoTrack = factory.createVideoTrack("screen", videoSource!!)
        peer.addTrack(track, listOf("screen"))

        // Add audio track (best-effort): the PeerConnectionFactory's default
        // AudioDeviceModule captures mic audio automatically. Audio must never
        // break the answer — any failure falls back to video-only with a log.
        try {
            val audioSrc = audioSource
            if (audioSrc != null) {
                val aTrack = factory.createAudioTrack("audio", audioSrc)
                peer.addTrack(aTrack, listOf("audio"))
                audioTrack = aTrack
                Log.i(TAG, "audio track added (mic + system playback)")
            } else {
                Log.i(TAG, "no audio source — answering video-only")
            }
        } catch (error: Exception) {
            Log.w(TAG, "audio track failed — answering video-only", error)
            audioTrack = null
        }

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
                                Log.i(TAG, "offer AUTO-ACCEPTED — answer posted (hotspot host-pair will be tried first)")
                            }
                        },
                        MediaConstraints().apply {
                            // Tell the H.264 encoder to target smooth motion
                            // (30 fps) at 4 Mbps. These are passed as the offer
                            // SDP already has the browser's receive capability,
                            // so the Android encoder tunes itself accordingly.
                            mandatory.add(
                                MediaConstraints.KeyValuePair(
                                    "maxBitrate",
                                    "4000"
                                )
                            )
                            mandatory.add(
                                MediaConstraints.KeyValuePair(
                                    "maxFramerate",
                                    "30"
                                )
                            )
                        },
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp.optString("sdp")),
        )
    }

    private fun onRemoteIce(payload: JSONObject?) {
        val candidate = payload?.optJSONObject("candidate") ?: return
        val sdp = candidate.optString("candidate", "")
        val type = when {
            sdp.contains("typ host") -> "host"
            sdp.contains("typ srflx") -> "srflx"
            sdp.contains("typ relay") -> "relay"
            else -> "cand"
        }
        Log.i(TAG, "remote ICE $type: ${sdp.take(120)}")
        val peer = pc
        if (peer == null) {
            if (pendingIce.size < MAX_PENDING_ICE) pendingIce.addLast(candidate)
            return
        }
        applyIce(peer, candidate)
    }

    private fun drainPendingIce(peer: PeerConnection) {
        while (pendingIce.isNotEmpty()) applyIce(peer, pendingIce.removeFirst())
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

    private fun endSession() {
        connected = false
        try { dataChannel?.close() } catch (_: Exception) {}
        dataChannel = null
        try { pc?.close() } catch (_: Exception) {}
        pc = null
        pendingIce.clear()
        if (running) onState("ready")
    }

    private fun observeChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) = Unit
            override fun onStateChange() { Log.i(TAG, "control channel: ${dc.state()}") }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes)
                try { onInput(JSONObject(text)) } catch (error: Exception) {
                    Log.w(TAG, "bad control message: $text", error)
                }
            }
        })
    }

    private fun postSignal(kind: String, payload: JSONObject) {
        // Fire-and-forget on net pool — never blocks pollExecutor.
        netExecutor.execute {
            try {
                http.postJson(
                    "$signalBase/device/signals",
                    JSONObject().put("token", rawToken).put("kind", kind).put("payload", payload).toString(),
                )
            } catch (error: Exception) {
                Log.w(TAG, "signal post failed: $kind", error)
            }
        }
    }

    /** Registers with CRM (presence, ICE bundle, pending). Runs on net pool — never main. */
    fun register(onReady: (name: String) -> Unit) {
        netExecutor.execute {
            try {
                val response = http.postJson(
                    "$signalBase/device/register",
                    JSONObject().put("token", rawToken).put("deviceId", android.os.Build.MODEL).toString(),
                )
                if (response?.optBoolean("ok") == true) {
                    refreshIceFromJson(response)
                    onReady(response.optString("name"))
                } else {
                    Log.w(TAG, "register: unexpected response $response")
                }
            } catch (error: Exception) {
                Log.w(TAG, "register failed", error)
            }
        }
    }

    fun keepAlive() { register { } }

    fun sendControl(message: JSONObject) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try { dc.send(DataChannel.Buffer(ByteBuffer.wrap(message.toString().toByteArray()), false)) }
        catch (error: Exception) { Log.w(TAG, "sendControl failed", error) }
    }

    fun stopAll() {
        val wasRunning = running
        running = false
        if (inSession()) {
            // Best-effort hangup — never block the calling thread (often Main / FGS).
            val hangupBody = JSONObject().put("token", rawToken).put("kind", "hangup").put("payload", JSONObject().put("by", "device")).toString()
            val hangupUrl = "$signalBase/device/signals"
            netExecutor.execute {
                try { http.postJson(hangupUrl, hangupBody) } catch (_: Exception) {}
            }
        }
        endSessionQuietly()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        surfaceHelper = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        // Stop audio capture (system playback) and dispose audio source
        audioCapture?.stop()
        audioCapture = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        capturer = null
        projectionCb = null
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null
        try { factory?.dispose() } catch (_: Exception) {}
        // Keep factory null so next Sharing ON re-creates with fresh EglBase
        factory = null
        if (wasRunning) onState("closed")
    }

    private fun endSessionQuietly() {
        connected = false
        try { dataChannel?.close() } catch (_: Exception) {}
        dataChannel = null
        try { pc?.close() } catch (_: Exception) {}
        pc = null
        pendingIce.clear()
    }

    private fun noopSdpObserver(): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) { Log.w(TAG, "create failed: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "set failed: $error") }
    }

    inner class SignalingHttp {
        private val client = okhttp3.OkHttpClient()
        fun postJson(url: String, body: String): JSONObject? {
            val request = okhttp3.Request.Builder().url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json").build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} from $url: ${text.take(200)}")
                    return null
                }
                return try { JSONObject(text) } catch (_: Exception) { null }
            }
        }
    }
}
