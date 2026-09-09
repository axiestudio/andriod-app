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
import com.axie.remote.net.PresenceEmitter
import com.axie.remote.pairing.Pairing
import com.axie.remote.pairing.PairingPrefs
import com.axie.remote.scan.ScanActivity
import com.axie.remote.update.UpdateManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
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
    /**
     * Keeps the paired device visible in the web roster while the app is
     * open: re-runs the pairing probe every 40 s (the roster marks a device
     * online for 45 s after each register). Lifecycle-bound in onResume/onPause.
     */
    private val presence = PresenceEmitter(
        signalBase = { PairingPrefs.crmSignalBase(applicationContext) },
        token = { PairingPrefs.load(applicationContext)?.token.orEmpty() },
        deviceId = { PairingPrefs.load(applicationContext)?.serverDeviceId.orEmpty() },
        onResult = { name ->
            // Presence is server-side; refresh the visible state only.
            if (name != null) loadPairing()
        },
    )

    private lateinit var pairedBlock: View
    private lateinit var unpairedBlock: View
    private lateinit var pairedNameText: TextView
    private lateinit var pairedStatusText: TextView
    private lateinit var pairedDetailText: TextView
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
     * reload from storage and verify reachability immediately — pairing and
     * proof happen in one step, nothing for the user to re-check.
     */
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                loadPairing()
                verifyPairing(showSpinner = true)
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
        pairedBlock = findViewById(R.id.pairedBlock)
        unpairedBlock = findViewById(R.id.unpairedBlock)
        pairedNameText = findViewById(R.id.pairedNameText)
        pairedStatusText = findViewById(R.id.pairedStatusText)
        pairedDetailText = findViewById(R.id.pairedDetailText)
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

        loadPairing()
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

        // Long-press the paired name for the escape hatch: unpair (re-pairing
        // is always a fresh QR — there is no manual URL/token editing).
        pairedNameText.setOnLongClickListener {
            confirmUnpair()
            true
        }
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
        presence.start()
        if (!autoChecked) {
            autoChecked = true
            runUpdateCheck(manual = false)
        }
    }

    override fun onPause() {
        super.onPause()
        statsHandler.removeCallbacks(statsTick)
        presence.stop()
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
        val paired = pairing != null
        val controlOn = ControlAccessibilityService.isEnabled(this)
        val sharingOn = ScreenCaptureService.isRunning
        val relay = ScreenCaptureService.relayState

        val stateRes = when {
            sharingOn && controlOn && relay == "live" -> R.string.session_interactive
            sharingOn -> R.string.session_sharing
            paired && controlOn -> R.string.session_active
            paired || controlOn -> R.string.session_available
            else -> R.string.session_unavailable
        }
        sessionStateText.setText(stateRes)
        val screen = if (sharingOn) "on" else "off"
        val control = if (controlOn) "on" else "off"
        sessionDetailText.text =
            "Screen: $screen · Control: $control · Relay: $relay" +
                if (!paired && !sharingOn) " — scan the pairing QR, enable control, then start sharing."
                else if (!controlOn) " — enable control for remote taps."
                else if (!sharingOn) " — start sharing to go live."
                else ""
        sharingDetailText.text = "Screen: $screen · Control: $control · Relay: $relay"
    }

    // ---- pairing state (QR-first: storage is the single source of truth) -----

    private var pairing: Pairing? = null

    private fun loadPairing() {
        pairing = PairingPrefs.load(this)
        renderPairing()
    }

    /**
     * Passive reachability check on a worker thread — the exact request the
     * sharing path performs. The result updates the paired card (verified /
     * unreachable / token-revoked); there is no button to press.
     */
    private fun verifyPairing(showSpinner: Boolean = false) {
        val current = pairing ?: return
        if (showSpinner) {
            pairedStatusText.text = getString(R.string.pair_probing)
        }
        Thread({
            val probed = PairingPrefs.probe(this, current)
            PairingPrefs.save(this, probed)
            runOnUiThread {
                if (pairing?.token == current.token) {
                    pairing = probed
                    renderPairing()
                    refreshAll()
                }
            }
        }, "AxiePairProbe").start()
    }

    private fun renderPairing() {
        val p = pairing
        pairedBlock.visibility = if (p == null) View.GONE else View.VISIBLE
        unpairedBlock.visibility = if (p == null) View.VISIBLE else View.GONE
        if (p == null) return
        pairedNameText.text =
            p.name.ifBlank { getString(R.string.pair_status_unknown) }
        pairedStatusText.text = when {
            p.reachable && p.viewerOnline -> getString(R.string.pair_verified_two_way)
            p.reachable -> getString(R.string.pair_verified)
            p.error != null -> getString(R.string.pair_probe_fail, p.error)
            else -> getString(R.string.pair_status_unknown)
        }
        pairedStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (p.reachable) R.color.carbon_green else R.color.carbon_gray_idle,
            ),
        )
        pairedDetailText.setText(R.string.pair_idle)
        connectionStatusText.visibility =
            if (p.error != null && !p.reachable) View.VISIBLE else View.GONE
        connectionStatusText.text =
            if (p.error != null && !p.reachable) {
                getString(R.string.pair_probe_fail, p.error)
            } else {
                ""
            }
    }

    private fun confirmUnpair() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_forget_title)
            .setMessage(R.string.pair_forget_message)
            .setPositiveButton(R.string.pair_forget_yes) { _, _ ->
                PairingPrefs.clear(this)
                loadPairing()
                refreshAll()
                Toast.makeText(this, R.string.pair_unpaired, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * True when the stored pairing targets the CRM signaling base (WebRTC
     * transport). All manual-URL spellings are gone — the QR decided.
     */
    private fun usesCrmSignaling(): Boolean = PairingPrefs.crmSignalBase(this) != null

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
        val signalBase = PairingPrefs.crmSignalBase(this) ?: return
        val token = pairing?.token ?: return
        val start = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_SERVER_URL, signalBase)
            putExtra(ScreenCaptureService.EXTRA_DEVICE_TOKEN, token)
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
        if (usesCrmSignaling()) {
            requestNotifThenConsent()
            return
        }
        // Not paired (or the stored pairing is not a CRM QR) — the QR is the
        // only way in, so the scanner IS the fix. Offline preview exists in
        // the legacy flow only; remote control always pairs first.
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.url_required_title)
            .setMessage(R.string.url_required_message)
            .setPositiveButton(R.string.scan_pair) { _, _ ->
                onScanQr()
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
        refreshAll()
    }

    private fun requestNotifThenConsent() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission()) {
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
        setSharingState(SharingState.STARTING)
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        val start = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenCaptureService.EXTRA_SERVER_URL, pairing?.serverUrl.orEmpty())
            putExtra(ScreenCaptureService.EXTRA_DEVICE_TOKEN, pairing?.token.orEmpty())
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
