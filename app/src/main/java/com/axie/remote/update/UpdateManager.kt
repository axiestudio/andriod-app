package com.axie.remote.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Self-update for side-loaded installs (no Play Store).
 *
 * Flow: compare installed versionCode against `releases/latest.json` in the
 * public repo → download the APK with OkHttp → install via a
 * [PackageInstaller] session. The system still shows its install-confirmation
 * UI, and the user must have allowed "Install unknown apps" for Axie Remote
 * (we deep-link there via Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).
 */
object UpdateManager {
    const val LATEST_URL =
        "https://raw.githubusercontent.com/axiestudio/andriod-app/main/releases/latest.json"
    const val STATUS_ACTION = "com.axie.remote.update.INSTALL_STATUS"
    private const val TAG = "AxieRemote"

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
    )

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data class UpToDate(val versionName: String) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    private val http = OkHttpClient()

    fun currentVersion(context: Context): Pair<Long, String> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName ?: "?"
        return PackageInfoCompat.getLongVersionCode(info) to name
    }

    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val (currentCode, currentName) = currentVersion(context)
            val body = http.newCall(Request.Builder().url(LATEST_URL).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext CheckResult.Failed("HTTP ${resp.code}")
                    resp.body?.string() ?: return@withContext CheckResult.Failed("empty feed")
                }
            val json = JSONObject(body)
            val info = UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                notes = json.optString("notes", ""),
            )
            if (info.versionCode > currentCode) CheckResult.Available(info)
            else CheckResult.UpToDate(currentName)
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            CheckResult.Failed(e.message ?: "network error")
        }
    }

    /**
     * Downloads the APK and hands it to PackageInstaller. Progress / terminal
     * states are reported via [onStatus] (called off the main thread).
     * Returns false when the caller must first send the user to the
     * "Install unknown apps" screen.
     */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onStatus: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!context.packageManager.canRequestPackageInstalls()) return@withContext false

        val dir = File(context.getExternalFilesDir("updates"), "").apply { mkdirs() }
        val apk = File(dir, "axie-remote-${info.versionName}.apk")
        onStatus("downloading")
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                onStatus("failed: HTTP ${resp.code}")
                return@withContext true
            }
            val body = resp.body ?: run {
                onStatus("failed: empty file")
                return@withContext true
            }
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                apk.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onStatus("downloading:${(done * 100 / total).toInt()}")
                    }
                }
            }
        }

        onStatus("installing")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val statusIntent = Intent(context, InstallReceiver::class.java).apply {
                action = STATUS_ACTION
            }
            val sender = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(sender.intentSender)
        }
        Log.i(TAG, "install session committed for v${info.versionName}")
        true
    }
}
