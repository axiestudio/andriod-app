package com.axie.remote.net

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.webrtc.ExternalAudioProcessingFactory

/**
 * Mixes the phone's system audio (WhatsApp calls, media, ringtones) into the
 * mic frames WebRTC is about to encode, so the viewer hears both on the one
 * audio track it already receives. No new PeerConnection negotiation, no
 * native code, no extra bandwidth negotiation.
 *
 * How: the factory is built with [ExternalAudioProcessingFactory] and this
 * mixer registered via `setCapturePostProcessing` — native code then calls
 * [process] on the audio thread every 10 ms with the captured mic frame,
 * after capture and before the encoder. We add our buffered system samples
 * into that frame with saturation. The system side is fed by [pushPcm] from
 * `AudioCapture` (AudioPlaybackCapture, 48 kHz mono int16).
 *
 * Safety (audio must never break the call):
 * - First frame verifies the layout: exactly 1 band (time-domain PCM) and a
 *   writable buffer. Anything else disables mixing with a log — mic continues.
 * - [process] never throws: any error disables mixing, mic continues.
 * - Starved (quiet system) frames mix zeros — normal, not an error.
 * - Overflow drops oldest samples with a throttled log, never blocks.
 * - No PCM content is ever logged (screen-share privacy rule).
 */
class SystemAudioMixer : ExternalAudioProcessingFactory.AudioProcessing {
    companion object {
        private const val TAG = "AxieAudioMixer"
        /** System bed under the voice so the mic stays intelligible. */
        private const val SYSTEM_GAIN = 0.5f
        /** Source domain of [pushPcm]: AudioPlaybackCapture is fixed 48 kHz mono. */
        private const val SOURCE_RATE = 48_000
        /** FIFO holds ~1 s of system audio; overflow drops oldest. */
        private const val FIFO_CAP_SAMPLES = 48_000
        /** Largest single frame we mix (10 ms @ 48 kHz mono). */
        private const val MAX_FRAME_SAMPLES = 960
        private const val OVERFLOW_LOG_MS = 5_000L
    }

    private val lock = Any()
    private val fifo = ShortArray(FIFO_CAP_SAMPLES)
    private var fifoHead = 0
    private var fifoSize = 0

    /** Absolute 48 kHz index of [fifoHead]; [readPos] is the next fractional read. */
    private var srcIndex = 0L
    private var readPos = 0.0

    @Volatile private var expectRate = SOURCE_RATE
    @Volatile private var enabled = true
    @Volatile private var verified = false
    @Volatile private var pcmSeen = false

    private var mixedFrames = 0L
    private var starvedReads = 0L
    private var lastOverflowLog = 0L
    private val scratch = ShortArray(MAX_FRAME_SAMPLES)

    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        Log.i(TAG, "mixer init: rate=$sampleRateHz channels=$numChannels")
        if (numChannels != 1) {
            enabled = false
            Log.w(TAG, "mixer disabled — mono layout expected, mic continues")
            return
        }
        expectRate = sampleRateHz
        resetLocked(reason = "init")
    }

    override fun reset(newRate: Int) {
        Log.i(TAG, "mixer reset: newRate=$newRate")
        expectRate = newRate
        resetLocked(reason = "reset")
    }

    /** System PCM in: 48 kHz mono int16, any chunk size. Never blocks. */
    fun pushPcm(pcm: ByteBuffer) {
        if (!enabled) return
        try {
            if (!pcmSeen) {
                pcmSeen = true
                Log.i(TAG, "system PCM flowing into mixer")
            }
            val view = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            synchronized(lock) {
                while (view.remaining() >= 2) {
                    if (fifoSize >= FIFO_CAP_SAMPLES) {
                        fifoHead = (fifoHead + 1) % FIFO_CAP_SAMPLES
                        fifoSize--
                        srcIndex++
                        val now = System.currentTimeMillis()
                        if (now - lastOverflowLog > OVERFLOW_LOG_MS) {
                            lastOverflowLog = now
                            Log.w(TAG, "mixer FIFO overflow — dropping oldest system audio")
                        }
                    }
                    fifo[(fifoHead + fifoSize) % FIFO_CAP_SAMPLES] = view.short
                    fifoSize++
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "mixer push failed — skipping chunk", error)
        }
    }

    /** Clears buffered system audio (session end / rate change). */
    fun reset() {
        synchronized(lock) { resetLocked(reason = "session") }
    }

    private fun resetLocked(reason: String) {
        fifoHead = 0
        fifoSize = 0
        srcIndex = 0L
        readPos = 0.0
        if (reason == "session") Log.i(TAG, "mixer FIFO cleared")
    }

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!enabled) return
        try {
            mix(numBands, numFrames, buffer)
        } catch (error: Exception) {
            enabled = false
            Log.e(TAG, "mixer disabled after error — mic continues", error)
        }
    }

    private fun mix(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!verified) {
            verified = true
            Log.i(
                TAG,
                "first process: bands=$numBands frames=$numFrames " +
                    "capacity=${buffer.capacity()} readOnly=${buffer.isReadOnly}",
            )
            if (numBands != 1) {
                enabled = false
                Log.w(TAG, "mixer disabled — time-domain frames expected (bands=1), mic continues")
                return
            }
            if (buffer.isReadOnly) {
                enabled = false
                Log.w(TAG, "mixer disabled — buffer is read-only, mic continues")
                return
            }
        }
        if (numBands != 1 || buffer.isReadOnly) return
        if (numFrames <= 0 || numFrames > MAX_FRAME_SAMPLES) return

        pullResampled(scratch, numFrames, expectRate)
        // Absolute get/put on a native-ordered duplicate: the shared buffer's
        // own position/limit are never disturbed.
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val shorts = minOf(numFrames, view.remaining() / 2)
        for (i in 0 until shorts) {
            val pos = i * 2
            val mic = view.getShort(pos).toInt()
            val add = (scratch[i] * SYSTEM_GAIN).toInt()
            var mixed = mic + add
            if (mixed > 32767) mixed = 32767 else if (mixed < -32768) mixed = -32768
            view.putShort(pos, mixed.toShort())
        }
        mixedFrames++
        if (mixedFrames == 1L || mixedFrames % 6000L == 0L) {
            Log.i(TAG, "mixing live: frames=$mixedFrames starvedReads=$starvedReads")
        }
    }

    /** Pulls [n] system samples resampled 48 kHz → [rate]; starved tail is zeros. */
    private fun pullResampled(dst: ShortArray, n: Int, rate: Int) {
        synchronized(lock) {
            if (rate == SOURCE_RATE) {
                val take = minOf(n, fifoSize)
                for (i in 0 until take) dst[i] = fifo[(fifoHead + i) % FIFO_CAP_SAMPLES]
                for (i in take until n) dst[i] = 0
                if (take < n) starvedReads++
                fifoHead = (fifoHead + take) % FIFO_CAP_SAMPLES
                fifoSize -= take
                srcIndex += take
                readPos = (srcIndex).toDouble()
                return
            }
            val step = SOURCE_RATE.toDouble() / rate
            var starved = false
            for (i in 0 until n) {
                val p = readPos + i * step
                val idx = p.toLong()
                val frac = (p - idx).toFloat()
                val s0 = sampleAtLocked(idx)
                val s1 = sampleAtLocked(idx + 1)
                if (s0 == null || s1 == null) starved = true
                val a = (s0 ?: 0).toFloat()
                val b = (s1 ?: 0).toFloat()
                dst[i] = (a + (b - a) * frac).toInt().toShort()
            }
            if (starved) starvedReads++
            readPos += n * step
            // Discard source samples fully behind the read head (keep 1 for interp).
            val dropUntil = readPos.toLong() - 1
            while (srcIndex < dropUntil && fifoSize > 0) {
                fifoHead = (fifoHead + 1) % FIFO_CAP_SAMPLES
                fifoSize--
                srcIndex++
            }
        }
    }

    /** Sample at absolute 48 kHz index, or null when not buffered (starved). */
    private fun sampleAtLocked(absIdx: Long): Short? {
        val offset = absIdx - srcIndex
        if (offset < 0 || offset >= fifoSize) return null
        return fifo[(fifoHead + offset.toInt()) % FIFO_CAP_SAMPLES]
    }
}
