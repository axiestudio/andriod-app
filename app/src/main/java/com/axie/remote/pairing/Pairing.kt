package com.axie.remote.pairing

import android.content.Context
import android.net.Uri
import com.axie.remote.net.SignalingClient
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * QR-first pairing (SPEC.md §5.3): the web viewer renders one QR per phone,
 * `axie-remote://pair?v=1&url=<relay>&token=<token>&name=<label>`, and the
 * in-app scanner ([ScanActivity][com.axie.remote.scan.ScanActivity]) stores it
 * in app-private storage — the relay URL and token have **no UI**: they are
 * never rendered, never editable, never copyable. Re-pairing (a fresh QR)
 * is the only way to change them.
 *
 * This object owns the shared-preference keys so MainActivity and the scanner
 * can never drift apart, and the parse is total: anything that is not our
 * code, version, or a complete pair yields null instead of partial state.
 *
 * Reachability is not a user-driven "test button" — it is a passive health
 * check the app runs for itself ([probe]) and surfaces as paired/unpaired
 * state. The probe is the same request the sharing path performs, so what it
 * reports is exactly what sharing will do (no WebSocket semantics — the
 * signaling is an HTTP mailbox; screen video is P2P WebRTC).
 */
data class Pairing(
	val serverUrl: String,
	val token: String,
	val name: String,
	/** True once a probe (or a successful share) confirmed the token works. */
	val reachable: Boolean = false,
	/** Last probe failure, for the tiny error line under the paired name. */
	val error: String? = null,
	/** Server-assigned device row id — sticky identity across re-registers. */
	val serverDeviceId: String? = null,
	/** Mirrored from register/poll responses: the CRM web UI pinged recently. */
	val viewerOnline: Boolean = false,
)

object PairingPrefs {
	const val PREFS = "axie_remote"
	const val KEY_URL = "server_url"
	const val KEY_TOKEN = "device_token"
	const val KEY_NAME = "device_name"
	const val KEY_REACHABLE = "device_reachable"
	const val KEY_SERVER_ID = "server_device_id"
	const val KEY_VIEWER_ONLINE = "viewer_online"
	const val SCHEME = "axie-remote"
	const val HOST = "pair"
	const val VERSION = "1"

	/** CRM signaling base (https form of the relay URL, /rest/mobile suffix). */
	const val CRM_SUFFIX = "/rest/mobile"

	fun parsePairUri(text: String?): Pairing? {
		val trimmed = text?.trim().orEmpty()
		if (trimmed.isEmpty()) return null
		val uri = try {
			Uri.parse(trimmed)
		} catch (_: Exception) {
			return null
		}
		if (uri.scheme != SCHEME || uri.host != HOST) return null
		if (uri.getQueryParameter("v") != VERSION) return null
		val url = SignalingClient.normalizeUrl(uri.getQueryParameter("url")) ?: return null
		val token = uri.getQueryParameter("token")?.trim().orEmpty()
		if (token.isEmpty()) return null
		val name = uri.getQueryParameter("name")?.trim().orEmpty()
		return Pairing(url, token, name)
	}

	fun save(context: Context, pairing: Pairing) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
			.putBoolean(KEY_REACHABLE, pairing.reachable)
			.putString(KEY_NAME, pairing.name)
			.putString(KEY_URL, pairing.serverUrl)
			.putString(KEY_TOKEN, pairing.token)
			.putString(KEY_SERVER_ID, pairing.serverDeviceId)
			.putBoolean(KEY_VIEWER_ONLINE, pairing.viewerOnline)
			.apply()
	}

	/** The stored pairing, or null when unpaired. */
	fun load(context: Context): Pairing? {
		val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		val url = prefs.getString(KEY_URL, null) ?: return null
		val token = prefs.getString(KEY_TOKEN, null) ?: return null
		return Pairing(
			serverUrl = url,
			token = token,
			name = prefs.getString(KEY_NAME, null).orEmpty(),
			reachable = prefs.getBoolean(KEY_REACHABLE, false),
			serverDeviceId = prefs.getString(KEY_SERVER_ID, null),
			viewerOnline = prefs.getBoolean(KEY_VIEWER_ONLINE, false),
		)
	}

	/** Clear pairing (used after a probe proves the token revoked). */
	fun clear(context: Context) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
	}

	/**
	 * https spelling of the stored relay URL when it is a CRM signaling base
	 * (the QR carries wss:// but signaling is plain HTTP), else null.
	 */
	fun crmSignalBase(context: Context): String? {
		val raw = load(context)?.serverUrl?.trim()?.trimEnd('/') ?: return null
		val https = when {
			raw.startsWith("https://") -> raw
			raw.startsWith("http://") -> raw
			raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
			raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
			else -> return null
		}
		return if (https.endsWith(CRM_SUFFIX)) https else null
	}

	/**
	 * Passive health check — the exact request sharing performs. Resolves on
	 * the calling (worker) thread with a Pairing whose reachable/error fields
	 * are set; the caller persists it via [save].
	 *
	 * - 200 + ok  → reachable, name echoed back by the API
	 * - 404       → token revoked/unpaired on the web side
	 * - other/net → unreachable (gate down, offline, …)
	 */
	fun probe(context: Context, pairing: Pairing): Pairing {
		val signalBase = crmSignalBaseOf(pairing.serverUrl) ?: return pairing.copy(
			reachable = false,
			error = "not a CRM signaling URL",
		)
		return try {
			val body = JSONObject().apply {
				put("token", pairing.token)
				put("deviceId", "probe")
			}.toString()
			val request = Request.Builder()
				.url("$signalBase/device/register")
				.post(body.toRequestBody("application/json".toMediaType()))
				.build()
			okhttp3.OkHttpClient().newCall(request).execute().use { response ->
				val text = response.body?.string().orEmpty()
				val json = runCatching { JSONObject(text) }.getOrNull()
				when {
					response.isSuccessful && json?.optBoolean("ok") == true ->
						pairing.copy(
							reachable = true,
							error = null,
							name = json.optString("name").ifBlank { pairing.name },
							serverDeviceId = json.optString("deviceId").ifBlank { pairing.serverDeviceId },
							viewerOnline = json.optBoolean("viewerOnline", false),
						)
					response.code == 404 ->
						pairing.copy(reachable = false, error = "token no longer valid — scan the QR again")
					else ->
						pairing.copy(reachable = false, error = "unreachable (HTTP ${response.code})")
				}
			}
		} catch (e: Exception) {
			pairing.copy(reachable = false, error = e.message ?: "network error")
		}
	}

	/** https spelling of a relay URL when it targets the CRM signaling base. */
	fun crmSignalBaseOf(rawUrl: String): String? {
		val raw = rawUrl.trim().trimEnd('/')
		val https = when {
			raw.startsWith("https://") -> raw
			raw.startsWith("http://") -> raw
			raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
			raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
			else -> return null
		}
		return if (https.endsWith(CRM_SUFFIX)) https else null
	}
}
