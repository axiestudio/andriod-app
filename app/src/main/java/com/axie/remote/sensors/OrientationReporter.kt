package com.axie.remote.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

/**
 * Streams the phone's physical pose for the viewer's aesthetic device mockup.
 *
 * Follows the Android position-sensor guide: the deprecated `TYPE_ORIENTATION`
 * sensor is never used. Instead the `TYPE_ROTATION_VECTOR` sensor feeds
 * `getRotationMatrixFromVector()` → `remapCoordinateSystem()` (so pitch/roll
 * stay natural in any display rotation) → `getOrientation()`, yielding
 * azimuth/pitch/roll in degrees.
 * (https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)
 *
 * Resource budget: the listener lives on its own thread at `SENSOR_DELAY_UI`,
 * samples are additionally throttled (≥ 250 ms apart, ≥ 1.5° of movement),
 * and the owner ([ScreenCaptureService][com.axie.remote.capture.ScreenCaptureService])
 * only forwards them while at least one viewer is watching — same policy as
 * video frames. No permission is required for motion sensors.
 */
class OrientationReporter(
    context: Context,
    private val onSample: (azimuth: Float, pitch: Float, roll: Float, rotation: Int) -> Unit,
) {

    companion object {
        private const val TAG = "AxieRemote"
        private const val MIN_INTERVAL_MS = 250L
        private const val MIN_DELTA_DEG = 1.5f
    }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var thread: HandlerThread? = null
    private var lastSentAt = 0L
    private var lastAzimuth = Float.NaN
    private var lastPitch = Float.NaN
    private var lastRoll = Float.NaN

    private val listener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastSentAt < MIN_INTERVAL_MS) return

                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val remapped = FloatArray(9)
                when (displayRotation()) {
                    Surface.ROTATION_0 ->
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remapped
                        )
                    Surface.ROTATION_90 ->
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
                        )
                    Surface.ROTATION_180 ->
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped
                        )
                    else ->
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped
                        )
                }
                val angles = FloatArray(3)
                SensorManager.getOrientation(remapped, angles)
                val azimuth = ((Math.toDegrees(angles[0].toDouble()) + 360.0) % 360.0).toFloat()
                val pitch = Math.toDegrees(angles[1].toDouble()).toFloat()
                val roll = Math.toDegrees(angles[2].toDouble()).toFloat()

                if (!lastAzimuth.isNaN()) {
                    val moved = maxOf(
                        angularDiff(azimuth, lastAzimuth),
                        abs(pitch - lastPitch),
                        abs(roll - lastRoll),
                    )
                    if (moved < MIN_DELTA_DEG) return
                }
                lastSentAt = now
                lastAzimuth = azimuth
                lastPitch = pitch
                lastRoll = roll
                onSample(azimuth, pitch, roll, rotationIndex())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Pose quality is best-effort; the viewer smooths every sample.
            }
        }

    /** Returns false when the device has no rotation-vector sensor. */
    fun start(): Boolean {
        if (thread != null) return true
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            Log.i(TAG, "no rotation-vector sensor — orientation stream off")
            return false
        }
        val worker = HandlerThread("AxieOrientation").also { it.start() }
        thread = worker
        sensorManager.registerListener(
            listener, sensor, SensorManager.SENSOR_DELAY_UI, Handler(worker.looper)
        )
        return true
    }

    fun stop() {
        try {
            sensorManager.unregisterListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregister orientation listener failed", e)
        }
        try {
            thread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "stop orientation thread failed", e)
        }
        thread = null
        lastSentAt = 0L
        lastAzimuth = Float.NaN
        lastPitch = Float.NaN
        lastRoll = Float.NaN
    }

    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            try {
                appContext.display?.rotation
            } catch (_: Exception) {
                null
            } ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            try {
                windowManager.defaultDisplay?.rotation
            } catch (_: Exception) {
                null
            } ?: Surface.ROTATION_0
        }
    }

    private fun rotationIndex(): Int =
        when (displayRotation()) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            else -> 3
        }

    private fun angularDiff(a: Float, b: Float): Float =
        abs(((a - b + 540f) % 360f) - 180f)
}
