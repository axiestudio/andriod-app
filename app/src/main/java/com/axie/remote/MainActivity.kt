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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import com.axie.remote.net.SignalingClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.axie.remote.pairing.PairingPrefs
import com.axie.remote.scan.ScanActivity
import com.axie.remote.update.UpdateManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/** Host screen: permission toggles, pairing + URL validation, session lifecycle, self-update. */
class MainActivity : AppCompatActivity() {

    private enum class SharingState { IDLE, STARTING, SHARING, DENIED }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var sharingDetailText: TextView
    private lateinit var sessionStatsText: TextView
    private lateinit var controlStateText: TextView
    private lateinit var guidanceStrip: View
    private lateinit var stepRestrictedBlock: View
    private lateinit var serverUrlLayout: TextInputLayout
    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var deviceTokenInput: TextInputEditText
    private lateinit var connectionStatusText: TextView
    private lateinit var versionText: TextView
    private lateinit var updateStateText: TextView
    private lateinit var downloadButton: Button
    private lateinit var notifSwitch: SwitchMaterial
    private lateinit var sharingSwitch: SwitchMaterial
    private lateinit var controlSwitch: SwitchMaterial
    private lateinit var unknownSourcesSwitch: SwitchMaterial
    private lateinit var sessionStateText: TextView
    private lateinit var sessionDetailText: TextView

    /** Guards switch listeners while we reflect system truth (no event loops). */
    private var suppressSwitchEvents = false

    private var pendingUpdate: UpdateManager.UpdateInfo? = null
    private var autoChecked = false

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                savePairing()
                if (usesCrmSignaling()) {
                    startWebRtcSession(result.resultCode, result.data!!)
                    setSharingState(SharingState.SHARING)
                } else {
                    startCaptureService(result.resultCode, result.data!!)
                    setSharingState(SharingState.SHARING)
                }
            } else {
                setSharingState(SharingState.DENIED)
            }
            refreshAll()
        }

    /**
     * Notification permission (Android 13+). Capture works without it — only the
     * foreground-service indicator is affected — so we proceed either way.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshAll()
            launchProjectionConsent()
        }

    /**
     * Camera permission (QR pairing scanner). Capture works without it — the
     * scanner is the only feature that needs the camera — so denial just
     * leaves manual pairing, with an explanation.
     */
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner()
            else Toast.makeText(this, R.string.scan_denied, Toast.LENGTH_LONG).show()
        }

    /**
     * QR scan result: ScanActivity already persisted the pair via PairingPrefs;
     * reflect it into the form so the user reviews before sharing.
     */
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                serverUrlInput.setText(prefs.getString(PairingPrefs.KEY_URL, ""))
                deviceTokenInput.setText(prefs.getString(PairingPrefs.KEY_TOKEN, ""))
                serverUrlLayout.error = null
                connectionStatusText.setText(R.string.scan_applied)
            }
            refreshAll()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PairingPrefs.PREFS, MODE_PRIVATE)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        sharingDetailText = findViewById(R.id.sharingDetailText)
        sessionStatsText = findViewById(R.id.sessionStatsText)
        controlStateText = findViewById(R.id.controlStateText)
        guidanceStrip = findViewById(R.id.guidanceStrip)
        stepRestrictedBlock = findViewById(R.id.stepRestrictedBlock)
        serverUrlLayout = findViewById(R.id.serverUrlLayout)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        deviceTokenInput = findViewById(R.id.deviceTokenInput)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        versionText = findViewById(R.id.versionText)
        updateStateText = findViewById(R.id.updateStateText)
        downloadButton = findViewById(R.id.downloadButton)
        notifSwitch = findViewById(R.id.notifSwitch)
        sharingSwitch = findViewById(R.id.sharingSwitch)
        controlSwitch = findViewById(R.id.controlSwitch)
        unknownSourcesSwitch = findViewById(R.id.unknownSourcesSwitch)
        sessionStateText = findViewById(R.id.sessionStateText)
        sessionDetailText = findViewById(R.id.sessionDetailText)

        serverUrlInput.setText(prefs.getString(PairingPrefs.KEY_URL, ""))
        deviceTokenInput.setText(prefs.getString(PairingPrefs.KEY_TOKEN, ""))
        versionText.text = getString(
            R.string.current_version, UpdateManager.currentVersion(this).second
        )

        // Permission toggles — each toggle asks, the SYSTEM grants (goal.md §5).
        notifSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            onNotifToggle(checked)
        }
        sharingSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            if (checked) requestSharing() else stopSharing()
        }
        controlSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            onControlToggle(checked)
        }
        unknownSourcesSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchEvents) return@setOnCheckedChangeListener
            onUnknownSourcesToggle(checked)
        }

        serverUrlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                serverUrlLayout.error = null
            }
            override fun afterTextChanged(s: Editable?) { refreshSession() }
        })

        findViewById<Button>(R.id.testConnectionButton).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.scanButton).setOnClickListener { onScanQr() }
        findViewById<Button>(R.id.startButton).setOnClickListener { requestSharing() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopSharing() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            askToEnableControl()
        }
        findViewById<Button>(R.id.appInfoButton).setOnClickListener { openAppInfo() }
        findViewById<Button>(R.id.checkAgainButton).setOnClickListener { checkControlAgain() }
        findViewById<Button>(R.id.tapTestButton).setOnClickListener {
            val ok = ControlAccessibilityService.tapCenter()
            controlStateText.text =
                if (ok) getString(R.string.tap_ok) else getString(R.string.tap_missing)
        }
        findViewById<Button>(R.id.backButton).setOnClickListener {
            if (!ControlAccessibilityService.pressBack()) showControlOff()
        }
        findViewById<Button>(R.id.homeButton).setOnClickListener {
            if (!ControlAccessibilityService.pressHome()) showControlOff()
        }
        findViewById<Button>(R.id.recentsButton).setOnClickListener {
            if (!ControlAccessibilityService.pressRecents()) showControlOff()
        }
        findViewById<Button>(R.id.typeTestButton).setOnClickListener {
            val ok = ControlAccessibilityService.typeText("Hello from Axie Remote")
            Toast.makeText(
                this,
                if (ok) R.string.type_ok else R.string.type_missing,
                Toast.LENGTH_LONG
            ).show()
        }
        findViewById<Button>(R.id.checkUpdatesButton).setOnClickListener {
            runUpdateCheck(manual = true)
        }
        downloadButton.setOnClickListener {
            pendingUpdate?.let { info -> runUpdateDownload(info) }
        }

        setSharingState(
            if (ScreenCaptureService.isRunning) SharingState.SHARING else SharingState.IDLE
        )
        refreshAll()
        maybeShowSetupWizard()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        statsHandler.post(statsTick)
        if (!autoChecked) {
            autoChecked = true
            runUpdateCheck(manual = false)
        }
    }

    override fun onPause() {
        super.onPause()
        statsHandler.removeCallbacks(statsTick)
    }

    /** 1 Hz session telemetry (frames uploaded, watchers, pose) while visible. */
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTick =
        object : Runnable {
            override fun run() {
                updateSessionStats()
                statsHandler.postDelayed(this, 1000L)
            }
        }

    private fun updateSessionStats() {
        if (!ScreenCaptureService.isRunning) {
            sessionStatsText.visibility = View.GONE
            return
        }
        val watchers = ScreenCaptureService.watcherCount
        val watchersText = when (watchers) {
            null -> getString(R.string.watchers_unknown)
            0 -> getString(R.string.watchers_none)
            else -> getString(R.string.watchers_some, watchers)
        }
        val poseText = getString(
            if (ScreenCaptureService.poseOn) R.string.pose_on else R.string.pose_off
        )
        sessionStatsText.text =
            getString(R.string.session_stats, ScreenCaptureService.streamedCount, watchersText, poseText)
        sessionStatsText.visibility = View.VISIBLE
    }

    // ---- permission toggles -------------------------------------------------

    private fun onNotifToggle(checked: Boolean) {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.setup_done, Toast.LENGTH_SHORT).show()
            refreshAll()
            return
        }
        if (checked && !hasNotificationPermission()) {
            requestNotifPermission()
        } else if (!checked && hasNotificationPermission()) {
            // Cannot revoke programmatically — send the user to the system toggle.
            Toast.makeText(this, R.string.notif_rationale_message, Toast.LENGTH_LONG).show()
            openAppInfo()
        }
        refreshAll()
    }

    private fun onControlToggle(checked: Boolean) {
        val enabled = ControlAccessibilityService.isEnabled(this)
        if (checked && !enabled) {
            askToEnableControl()
        } else if (!checked && enabled) {
            // Cannot disable programmatically — system toggle only.
            Toast.makeText(this, R.string.step2_body, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        refreshAll()
    }

    /**
     * "Install unknown apps" (the Allow toggle in App info / Special access).
     * Required for the in-app self-update (UpdateManager → PackageInstaller).
     * Grant and revoke both live in system Settings — we reflect the truth and
     * deep-link to the exact toggle screen.
     */
    private fun onUnknownSourcesToggle(checked: Boolean) {
        val allowed = packageManager.canRequestPackageInstalls()
        if (checked == allowed) return
        if (checked) {
            Toast.makeText(this, R.string.updates_need_sources, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.updates_need_sources, Toast.LENGTH_LONG).show()
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
        )
        refreshAll()
    }

    private fun canInstallUnknownApps(): Boolean =
        packageManager.canRequestPackageInstalls()

    private fun refreshAll() {
        refreshControlState()
        refreshSwitches()
        refreshSession()
    }

    private fun refreshSwitches() {
        suppressSwitchEvents = true
        try {
            notifSwitch.isChecked =
                Build.VERSION.SDK_INT < 33 || hasNotificationPermission()
            sharingSwitch.isChecked = ScreenCaptureService.isRunning
            controlSwitch.isChecked = ControlAccessibilityService.isEnabled(this)
            unknownSourcesSwitch.isChecked = canInstallUnknownApps()
        } finally {
            suppressSwitchEvents = false
        }
    }

    private fun refreshControlState() {
        val enabled = ControlAccessibilityService.isEnabled(this)
        controlStateText.text =
            if (enabled) getString(R.string.control_on) else getString(R.string.control_off)
        // The red guidance strip (Restricted-settings steps) only clutters when useful.
        guidanceStrip.visibility = if (enabled) View.GONE else View.VISIBLE
        // Step 1 exists only on Android 13+, where sideloaded apps are gated.
        stepRestrictedBlock.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE
            else View.GONE
    }

    /**
     * Session chain from goal.md §6:
     * Unavailable → Available → Active → Screen sharing → Remote interaction.
     */
    private fun refreshSession() {
        val urlOk = SignalingClient.normalizeUrl(serverUrlInput.text?.toString()) != null
        val controlOn = ControlAccessibilityService.isEnabled(this)
        val sharingOn = ScreenCaptureService.isRunning
        val relay = ScreenCaptureService.relayState

        val stateRes = when {
            sharingOn && controlOn && relay == "live" -> R.string.session_interactive
            sharingOn -> R.string.session_sharing
            urlOk && controlOn -> R.string.session_active
            urlOk || controlOn -> R.string.session_available
            else -> R.string.session_unavailable
        }
        sessionStateText.setText(stateRes)
        val screen = if (sharingOn) "on" else "off"
        val control = if (controlOn) "on" else "off"
        sessionDetailText.text =
            "Screen: $screen · Control: $control · Relay: $relay" +
                if (!urlOk && !sharingOn) " — set a relay URL, enable control, then start sharing."
                else if (!controlOn) " — enable control for remote taps."
                else if (!sharingOn) " — start sharing to go live."
                else ""
        sharingDetailText.text = "Screen: $screen · Control: $control · Relay: $relay"
    }

    // ---- URL validation + connection test ("URL accessibility") --------------

    private fun currentUrl(): String = serverUrlInput.text?.toString().orEmpty()

    /** Null = valid-but-empty (offline preview allowed); non-null = normalized URL. */
    private fun validatedUrl(): String? {
        val raw = currentUrl()
        if (raw.isBlank()) return null
        val normalized = SignalingClient.normalizeUrl(raw)
        if (normalized == null) {
            serverUrlLayout.error = getString(R.string.conn_invalid)
        } else {
            serverUrlLayout.error = null
        }
        return normalized
    }

    private fun testConnection() {
        savePairing()
        val raw = currentUrl()
        if (raw.isBlank()) {
            serverUrlLayout.error = getString(R.string.conn_invalid)
            connectionStatusText.setText(R.string.conn_invalid)
            return
        }
        val normalized = SignalingClient.normalizeUrl(raw)
        if (normalized == null) {
            serverUrlLayout.error = getString(R.string.conn_invalid)
            connectionStatusText.setText(R.string.conn_invalid)
            return
        }
        serverUrlLayout.error = null
        connectionStatusText.setText(R.string.conn_testing)
        val token = deviceTokenInput.text?.toString().orEmpty()
        val signalBase = crmSignalBase()
        if (signalBase != null) {
            // CRM signaling (WebRTC): probe the device register endpoint with
            // the pairing token — HTTP semantics, not WebSocket semantics.
            testCrmSignalBase(signalBase, token)
            return
        }
        SignalingClient.testConnection(normalized, token) { ok, message ->
            runOnUiThread {
                connectionStatusText.text = getString(
                    if (ok) R.string.conn_ok else R.string.conn_failed, message
                )
                refreshSession()
            }
        }
    }

    /**
     * HTTP probe for CRM pairings: POST /device/register with the stored
     * token. 200 = token valid (shows the paired name), 404 = unknown token,
     * 401/403 = malformed. Never speaks WebSocket — the old ws:// probe
     * reported "expected HTTP 101 but was 404" against the REST API.
     */
    private fun testCrmSignalBase(signalBase: String, token: String) {
        Thread({
            var ok = false
            var message: String
            try {
                val body = JSONObject().apply {
                    put("token", token)
                    put("deviceId", "probe")
                }.toString()
                val request = okhttp3.Request.Builder()
                    .url("$signalBase/device/register")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    when {
                        response.isSuccessful && json?.optBoolean("ok") == true -> {
                            ok = true
                            message = getString(R.string.conn_ok, json.optString("name"))
                        }
                        response.code == 404 ->
                            message = getString(R.string.conn_unknown_token)
                        else ->
                            message = getString(R.string.conn_failed, "HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                message = getString(R.string.conn_failed, e.message ?: "network error")
            }
            runOnUiThread {
                connectionStatusText.text = message
                refreshSession()
            }
        }, "AxieCrmProbe").start()
    }

    /**
     * True when the pairing URL is the CRM signaling base (WebRTC transport).
     * The QR scanner persists the URL in ws(s) spelling (normalizeUrl), while
     * manual entry may keep https — both spellings are accepted.
     */
    private fun usesCrmSignaling(): Boolean = crmSignalBase() != null

    /** The signaling base as an https:// URL, or null when not a CRM pairing. */
    private fun crmSignalBase(): String? {
        val raw = currentUrl().trim().trimEnd('/')
        val https = when {
            raw.startsWith("https://") -> raw
            raw.startsWith("http://") -> raw
            raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
            raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
            else -> return null
        }
        return if (https.endsWith("/rest/mobile")) https else null
    }

    /**
     * P2P session: capture consent is already granted, so hand the projection
     * intent straight to [WebRtcClient] — it answers the browser's offer from
     * the signaling mailbox and streams the screen. Input from the viewer's
     * DataChannel lands in the same accessibility service the relay used.
     */
    /**
     * P2P session: hand the consent to [ScreenCaptureService] in WebRTC mode.
     * The service must promote itself to a mediaProjection foreground service
     * BEFORE the projection is created (Android 14 requirement — see
     * developer.android.com/about/versions/14/changes/fgs-types-required), so
     * the transport lives inside the FGS instead of the activity.
     */
    private fun startWebRtcSession(resultCode: Int, data: Intent) {
        val signalBase = crmSignalBase() ?: return
        val start = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_SERVER_URL, signalBase)
            putExtra(ScreenCaptureService.EXTRA_DEVICE_TOKEN, deviceTokenInput.text?.toString().orEmpty())
            putExtra(ScreenCaptureService.EXTRA_MODE, ScreenCaptureService.MODE_WEBRTC)
        }
        ContextCompat.startForegroundService(this, start)
    }

    // ---- QR pairing ---------------------------------------------------------

    /**
     * Documented runtime-permission flow for the scanner camera: already
     * granted → scan; rationale needed → educate, then ask; otherwise ask
     * directly. Denial keeps manual pairing (goal: never block sharing).
     */
    private fun onScanQr() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchScanner()
            return
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.CAMERA
            )
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scan_camera_title)
                .setMessage(R.string.scan_camera_message)
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(R.string.action_not_now, null)
                .show()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        scanLauncher.launch(Intent(this, ScanActivity::class.java))
    }

    // ---- sharing ------------------------------------------------------------

    /**
     * Documented runtime-permission flow: already granted → go; rationale
     * needed → educate, then ask; otherwise ask directly. Either answer leads
     * to the capture consent — notifications are not required for sharing.
     */
    private fun requestSharing() {
        val raw = currentUrl()
        if (raw.isNotBlank() && SignalingClient.normalizeUrl(raw) == null) {
            serverUrlLayout.error = getString(R.string.conn_invalid)
            Toast.makeText(this, R.string.conn_invalid, Toast.LENGTH_LONG).show()
            refreshAll()
            return
        }
        serverUrlLayout.error = null
        if (raw.isBlank()) {
            // Offline preview is legitimate — but confirm, since remote control
            // needs the relay (goal.md §§2–3 run on one session).
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.url_required_title)
                .setMessage(R.string.url_required_message)
                .setPositiveButton(R.string.action_continue_offline) { _, _ ->
                    requestNotifThenConsent()
                }
                .setNegativeButton(R.string.action_not_now, null)
                .show()
            refreshAll()
            return
        }
        requestNotifThenConsent()
    }

    private fun requestNotifThenConsent() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission()) {
            savePairing()
            launchProjectionConsent()
            return
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notif_rationale_title)
                .setMessage(R.string.notif_rationale_message)
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton(R.string.action_not_now) { _, _ ->
                    savePairing()
                    launchProjectionConsent()
                }
                .show()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission()) return
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notif_rationale_title)
                .setMessage(R.string.notif_rationale_message)
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton(R.string.action_not_now, null)
                .show()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun launchProjectionConsent() {
        savePairing()
        setSharingState(SharingState.STARTING)
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        val start = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenCaptureService.EXTRA_SERVER_URL, currentUrl())
            putExtra(ScreenCaptureService.EXTRA_DEVICE_TOKEN, deviceTokenInput.text?.toString().orEmpty())
        }
        ContextCompat.startForegroundService(this, start)
    }

    private fun stopSharing() {
        // One stop path for both transports — the capture service owns the
        // MJPEG relay and the P2P WebRTC session alike.
        startService(
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
        )
        setSharingState(SharingState.IDLE)
        refreshAll()
    }

    private fun showControlOff() {
        Toast.makeText(this, R.string.tap_missing, Toast.LENGTH_LONG).show()
    }

    // ---- accessibility onboarding -------------------------------------------

    /**
     * Auto-trigger: on first launch with control still off, offer the guided
     * setup instead of leaving the user to discover the system screens.
     */
    private fun maybeShowSetupWizard() {
        if (ControlAccessibilityService.isEnabled(this)) return
        if (prefs.getBoolean(KEY_SETUP_SEEN, false)) return
        prefs.edit().putBoolean(KEY_SETUP_SEEN, true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_title)
            .setMessage(R.string.setup_message)
            .setPositiveButton(R.string.start_setup) { _, _ -> startControlSetup() }
            .setNegativeButton(R.string.action_later, null)
            .show()
    }

    private fun startControlSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) openAppInfo()
        else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun checkControlAgain() {
        refreshAll()
        Toast.makeText(
            this,
            if (ControlAccessibilityService.isEnabled(this)) R.string.setup_done
            else R.string.setup_not_yet,
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Accessibility cannot be *granted* by API — only the user can enable it in
     * system Settings. So we educate first, then deep-link (the documented
     * pattern). The Restricted-settings steps stay visible in the guidance
     * strip for side-loaded installs.
     */
    private fun askToEnableControl() {
        if (ControlAccessibilityService.isEnabled(this)) {
            refreshAll()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.a11y_rationale_title)
            .setMessage(R.string.a11y_rationale_message)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
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

    private fun savePairing() {
        prefs.edit()
            .putString(PairingPrefs.KEY_URL, serverUrlInput.text?.toString().orEmpty())
            .putString(PairingPrefs.KEY_TOKEN, deviceTokenInput.text?.toString().orEmpty())
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
        refreshSwitches()
        refreshSession()
    }

    companion object {
        private const val TAG = "AxieRemoteMain"
        private const val KEY_SETUP_SEEN = "setup_seen"
    }
}
