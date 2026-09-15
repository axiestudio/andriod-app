package com.axie.remote.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the phone's audio OUTPUT (system playback — WhatsApp calls, music,
 * notifications) using Android's AudioPlaybackCapture API (Android 10+).
 *
 * This is SEPARATE from WebRTC's built-in mic capture. It captures what the
 * SPEAKER is playing (the other person's voice on a call, ringtone, etc.)
 * and feeds PCM samples to the WebRTC audio track via a callback.
 *
 * Requirements (already satisfied since screen capture is on):
 * - MediaProjection consent (reused from screen capture)
 * - USAGE_MEDIA or USAGE_GAME (all call audio, music, notifications)
 * - minSdk 29 (Android 10) — our minSdk is already 29
 *
 * Architecture:
 * ```
 * Android AudioFlinger (system mixer)
 *   └─ AudioPlaybackCapture → AudioRecord
 *        └─ PCM callback (16-bit 48kHz mono)
 *             └─ WebRTC AudioSource (via AudioTrackModule)
 * ```
 */
class AudioCapture(
    private val context: Context,
    private val onPcmData: (ByteBuffer, timestamp: Long) -> Unit,
) {
    companion object {
        private const val TAG = "AxieAudioCapture"
        /** 48 kHz — standard for WebRTC / Opus */
        private const val SAMPLE_RATE = 48000
        /** 16-bit PCM (the standard for WebRTC). */
        private const val BITS_PER_SAMPLE = 16
        /** Mono — stereo is collapsed by Android automatically. */
        private const val CHANNELS = 1
        /** 20ms buffer = 960 samples @ 48kHz mono 16‑bit. */
        private const val BUFFER_SIZE_FRAMES = 960
        private const val BUFFER_SIZE_BYTES = BUFFER_SIZE_FRAMES * (BITS_PER_SAMPLE / 8) * CHANNELS
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var audioRecord: AudioRecord? = null

    /**
     * Start capturing system audio output.
     *
     * @param mediaProjection The active MediaProjection (must have been granted
     *   consent — reuses the same consent the user gave for screen capture).
     * @return true if capture started successfully, false otherwise.
     */
    @SuppressLint("NewApi")
    fun start(mediaProjection: MediaProjection): Boolean {
        if (running.getAndSet(true)) {
            Log.w(TAG, "already running")
            return false
        }
        if (Build.VERSION.SDK_INT < 29) {
            Log.w(TAG, "AudioPlaybackCapture requires Android 10+")
            running.set(false)
            return false
        }

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferSize.coerceAtLeast(BUFFER_SIZE_BYTES * 2))
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize — is the MediaProjection still valid?")
                running.set(false)
                return false
            }

            audioRecord?.startRecording()
            Log.i(TAG, "AudioPlaybackCapture started @ ${SAMPLE_RATE}Hz mono PCM-16")

            executor.execute { captureLoop() }
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioPlaybackCapture denied — missing permission or projection", e)
            running.set(false)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlaybackCapture failed to start", e)
            running.set(false)
            return false
        }
    }

    private fun captureLoop() {
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE_BYTES)
        val record = audioRecord ?: return

        while (running.get()) {
            buffer.clear()
            val read = record.read(buffer, BUFFER_SIZE_BYTES)
            if (read > 0) {
                buffer.position(0)
                buffer.limit(read)
                onPcmData(buffer, System.nanoTime())
            } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.w(TAG, "AudioRecord read error (invalid op) — stopping")
                break
            }
        }
    }

    fun stop() {
        running.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.i(TAG, "AudioPlaybackCapture stopped")
    }
}