package com.axie.remote.scan

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.axie.remote.R
import com.axie.remote.pairing.PairingPrefs
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView

/**
 * QR pairing scanner (Carbon Gray 10, same tiles as MainActivity).
 *
 * Continuous scan; the first decodable `axie-remote://pair` code is persisted
 * via [PairingPrefs] and finishes with RESULT_OK so MainActivity can reflect
 * the new pair. Anything else toasts and keeps scanning — a stray barcode
 * never overwrites pairing. Camera lifecycle is pause/resume bound; MainActivity
 * guarantees CAMERA permission before launching.
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var scanner: CompoundBarcodeView
    private var settled = false
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        scanner = findViewById(R.id.scannerView)
        findViewById<Button>(R.id.torchButton).setOnClickListener { toggleTorch() }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }

        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text ?: return
                if (settled) return
                val pairing = PairingPrefs.parsePairUri(text)
                if (pairing == null) {
                    Toast.makeText(this@ScanActivity, R.string.scan_invalid, Toast.LENGTH_SHORT).show()
                    return
                }
                settled = true
                PairingPrefs.save(this@ScanActivity, pairing)
                val label = pairing.name.ifBlank { getString(R.string.app_name) }
                Toast.makeText(
                    this@ScanActivity,
                    getString(R.string.scan_applied) + " ($label)",
                    Toast.LENGTH_LONG,
                ).show()
                setResult(Activity.RESULT_OK)
                finish()
            }

            override fun possibleResultPoints(resultPoints: List<ResultPoint>) {
                // No overlay — the viewfinder frame is feedback enough.
            }
        })
    }

    override fun onResume() {
        super.onResume()
        scanner.resume()
    }

    override fun onPause() {
        super.onPause()
        scanner.pause()
    }

    private fun toggleTorch() {
        try {
            if (torchOn) scanner.setTorchOff() else scanner.setTorchOn()
            torchOn = !torchOn
        } catch (e: Exception) {
            Toast.makeText(this, R.string.scan_invalid, Toast.LENGTH_SHORT).show()
        }
    }
}
