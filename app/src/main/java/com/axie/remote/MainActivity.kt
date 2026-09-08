package com.axie.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.axie.remote.capture.ScreenCaptureService
import com.axie.remote.control.ControlAccessibilityService

/** M0 host screen: owns the MediaProjection consent dialog and service lifecycle. */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val start = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, start)
                setStatus("Sharing — capture service starting…")
            } else {
                setStatus("Screen-cast permission denied. Tap Start sharing to retry.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.startButton).setOnClickListener { requestSharing() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(
                Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
            )
            setStatus(getString(R.string.status_idle))
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.tapTestButton).setOnClickListener {
            val ok = ControlAccessibilityService.tapCenter()
            setStatus(if (ok) "Test tap dispatched." else "Control service not enabled — enable it first.")
        }
        findViewById<Button>(R.id.backButton).setOnClickListener {
            ControlAccessibilityService.pressBack()
        }
        findViewById<Button>(R.id.homeButton).setOnClickListener {
            ControlAccessibilityService.pressHome()
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
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
