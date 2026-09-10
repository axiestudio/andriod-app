package com.axie.remote.net

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Lifecycle-bound presence heartbeat for the paired phone: while the app is
 * open it re-runs the device-register probe every [INTERVAL_MS], which the
 * CRM counts as "online" (the web roster shows the phone and enables its
 * Connect button for 45 s after each register).
 *
 * Deliberately boring: one OkHttp call on a daemon thread, results marshalled
 * to the main thread. No foreground service — presence is about the app being
 * open, not about capture (the capture service has its own keepalive).
 */
class PresenceEmitter(
	private val signalBase: () -> String?,
	private val token: () -> String?,
	private val deviceId: () -> String = { "" },
	private val onResult: ((name: String?, pending: Int) -> Unit)? = null,
) {
	private val running = AtomicBoolean(false)
	private val main = Handler(Looper.getMainLooper())
	private val client = OkHttpClient()

	private val tick = object : Runnable {
		override fun run() {
			if (!running.get()) return
			val base = signalBase()
			val tok = token()
			val id = deviceId()
			if (base != null && !tok.isNullOrBlank()) {
				Thread({
					var name: String? = null
					var pending = 0
					try {
						val body = JSONObject().apply {
							put("token", tok)
							put("deviceId", id.ifBlank { "presence" })
						}.toString()
						val request = Request.Builder()
							.url("$base/device/register")
							.post(body.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
							.build()
						client.newCall(request).execute().use { response ->
							if (response.isSuccessful) {
								val json = runCatching {
									JSONObject(response.body?.string().orEmpty())
								}.getOrNull()
								if (json?.optBoolean("ok") == true) {
									name = json.optString("name")
									pending = json.optInt("pending", 0)
								}
							}
						}
					} catch (_: Exception) {
						// Offline — presence simply lapses until the next tick.
					}
					main.post { onResult?.invoke(name, pending) }
				}, "AxiePresence").start()
			}
			main.postDelayed(this, INTERVAL_MS)
		}
	}

	fun start() {
		if (running.getAndSet(true)) return
		main.post(tick)
	}

	fun stop() {
		running.set(false)
		main.removeCallbacks(tick)
	}

	companion object {
		/** Register TTL is 45 s server-side; beat well inside it. */
		private const val INTERVAL_MS = 40_000L
	}
}
