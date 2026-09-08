package com.axie.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.axie.remote.capture.ScreenCaptureService
import com.axie.remote.control.ControlAccessibilityService
import com.axie.remote.update.UpdateManager
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/** Host screen: pairing fields, consent, service lifecycle, self-update. */
class MainActivity : AppCompatActivity() {

    private enum class SharingState { IDLE, STARTING, SHARING, DENIED }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var controlStateText: TextView
    private lateinit var guidanceStrip: View
    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var deviceTokenInput: TextInputEditText
    private lateinit var versionText: TextView
    private lateinit var updateStateText: TextView
    private lateinit var downloadButton: Button

    private var pendingUpdate: UpdateManager.UpdateInfo? = null
    private var autoChecked = false

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                savePairing()
                val start = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, start)
                setSharingState(SharingState.SHARING)
            } else {
                setSharingState(SharingState.DENIED)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        controlStateText = findViewById(R.id.controlStateText)
        guidanceStrip = findViewById(R.id.guidanceStrip)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        deviceTokenInput = findViewById(R.id.deviceTokenInput)
        versionText = findViewById(R.id.versionText)
        updateStateText = findViewById(R.id.updateStateText)
        downloadButton = findViewById(R.id.downloadButton)

        serverUrlInput.setText(prefs.getString(KEY_URL, ""))
        deviceTokenInput.setText(prefs.getString(KEY_TOKEN, ""))
        versionText.text = getString(
            R.string.current_version, UpdateManager.currentVersion(this).second
        )

        findViewById<Button>(R.id.startButton).setOnClickListener { requestSharing() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(
                Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
            )
            setSharingState(SharingState.IDLE)
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.appInfoButton).setOnClickListener { openAppInfo() }
        findViewById<Button>(R.id.tapTestButton).setOnClickListener {
            val ok = ControlAccessibilityService.tapCenter()
            controlStateText.text =
                if (ok) getString(R.string.tap_ok) else getString(R.string.tap_missing)
        }
        findViewById<Button>(R.id.backButton).setOnClickListener {
            ControlAccessibilityService.pressBack()
        }
        findViewById<Button>(R.id.homeButton).setOnClickListener {
            ControlAccessibilityService.pressHome()
        }
        findViewById<Button>(R.id.checkUpdatesButton).setOnClickListener {
            runUpdateCheck(manual = true)
        }
        downloadButton.setOnClickListener {
            pendingUpdate?.let { info -> runUpdateDownload(info) }
        }

        setSharingState(SharingState.IDLE)
    }

    override fun onResume() {
        super.onResume()
        refreshControlState()
        if (!autoChecked) {
            autoChecked = true
            runUpdateCheck(manual = false)
        }
    }

    private fun refreshControlState() {
        val enabled = ControlAccessibilityService.isEnabled()
        controlStateText.text =
            if (enabled) getString(R.string.control_on) else getString(R.string.control_off)
        // The red guidance strip (Restricted-settings steps) only clutters when useful.
        guidanceStrip.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    /**
     * Side-loaded apps on Android 13+ cannot enable accessibility until the user
     * allows it: App info → menu → "Allow restricted settings". No API can do
     * this for them, so we deep-link to App info with the steps above it.
     */
    private fun openAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun runUpdateCheck(manual: Boolean) {
        if (manual) updateStateText.setText(R.string.updates_checking)
        lifecycleScope.launch {
            when (val result = UpdateManager.check(this@MainActivity)) {
                is UpdateManager.CheckResult.Available -> {
                    pendingUpdate = result.info
                    updateStateText.text =
                        getString(R.string.updates_available, result.info.versionName)
                    downloadButton.text =
                        getString(R.string.updates_download, result.info.versionName)
                    downloadButton.visibility = View.VISIBLE
                }
                is UpdateManager.CheckResult.UpToDate -> {
                    pendingUpdate = null
                    downloadButton.visibility = View.GONE
                    updateStateText.setText(R.string.updates_up_to_date)
                }
                is UpdateManager.CheckResult.Failed -> {
                    if (manual) updateStateText.setText(R.string.updates_failed)
                }
            }
        }
    }

    private fun runUpdateDownload(info: UpdateManager.UpdateInfo) {
        lifecycleScope.launch {
            val allowed = UpdateManager.downloadAndInstall(this@MainActivity, info) { status ->
                runOnUiThread {
                    if (status.startsWith("downloading:")) {
                        val pct = status.substringAfter(":").toIntOrNull() ?: 0
                        updateStateText.text = getString(R.string.updates_downloading, pct)
                    } else if (status == "installing") {
                        updateStateText.setText(R.string.updates_installing)
                    } else if (status.startsWith("failed")) {
                        updateStateText.setText(R.string.updates_failed)
                    }
                }
            }
            if (!allowed) {
                // System "Install unknown apps" gate: send them there, then retry.
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.updates_need_sources,
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }
    }

    private fun requestSharing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        savePairing()
        setSharingState(SharingState.STARTING)
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun savePairing() {
        prefs.edit()
            .putString(KEY_URL, serverUrlInput.text?.toString().orEmpty())
            .putString(KEY_TOKEN, deviceTokenInput.text?.toString().orEmpty())
            .apply()
    }

    private fun setSharingState(state: SharingState) {
        val (textRes, colorRes) = when (state) {
            SharingState.IDLE -> R.string.status_idle to R.color.carbon_gray_idle
            SharingState.STARTING -> R.string.status_starting to R.color.carbon_gray_idle
            SharingState.SHARING -> R.string.status_sharing to R.color.carbon_green
            SharingState.DENIED -> R.string.status_denied to R.color.carbon_red
        }
        statusText.setText(textRes)
        statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    companion object {
        private const val PREFS = "axie_remote"
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "device_token"
    }
}
