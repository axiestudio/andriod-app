package com.axie.remote.pairing

import android.content.Context
import android.net.Uri
import com.axie.remote.net.SignalingClient

/**
 * QR pairing (SPEC.md §5.3): the web viewer renders one QR per phone,
 * `axie-remote://pair?v=1&url=<relay>&token=<token>&name=<label>`, and the
 * in-app scanner ([ScanActivity][com.axie.remote.scan.ScanActivity]) fills the
 * pairing screen from it — no LAN URL typing by hand.
 *
 * This object owns the shared-preference keys so MainActivity and the scanner
 * can never drift apart, and the parse is total: anything that is not our
 * code, version, or a complete pair yields null instead of partial state.
 */
data class Pairing(val serverUrl: String, val token: String, val name: String)

object PairingPrefs {
    const val PREFS = "axie_remote"
    const val KEY_URL = "server_url"
    const val KEY_TOKEN = "device_token"
    const val SCHEME = "axie-remote"
    const val HOST = "pair"
    const val VERSION = "1"

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
            .putString(KEY_URL, pairing.serverUrl)
            .putString(KEY_TOKEN, pairing.token)
            .apply()
    }
}
