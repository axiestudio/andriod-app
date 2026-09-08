package com.axie.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.axie.remote.capture.ScreenCaptureService
import com.axie.remote.control.ControlAccessibilityService
import com.google.android.material.textfield.TextInputEditText

/** Host screen: pairing fields, MediaProjection consent, service lifecycle. */
class MainActivity : AppCompatActivity() {

    private enum class SharingState { IDLE, STARTING, SHARING, DENIED }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var controlStateText: TextView
    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var deviceTokenInput: TextInputEditText

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
        serverUrlInput = findViewById(R.id.serverUrlInput)
        deviceTokenInput = findViewById(R.id.deviceTokenInput)

        serverUrlInput.setText(prefs.getString(KEY_URL, ""))
        deviceTokenInput.setText(prefs.getString(KEY_TOKEN, ""))

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

        setSharingState(SharingState.IDLE)
    }

    override fun onResume() {
        super.onResume()
        refreshControlState()
    }

    private fun refreshControlState() {
        controlStateText.text =
            if (ControlAccessibilityService.isEnabled()) getString(R.string.control_on)
            else getString(R.string.control_off)
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
