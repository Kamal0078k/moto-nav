package dev.motonav.simulator.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reports the direction of the top edge of a portrait phone, clockwise from magnetic north.
 * Rotation-vector fusion remains usable while the foreground service owns navigation.
 */
class AndroidCompassHeadingSource(
    context: Context,
    private val callbackHandler: Handler,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val filter = CompassHeadingFilter()
    private var listener: ((Double) -> Unit)? = null

    fun start(listener: (Double) -> Unit): Boolean {
        stop()
        val selected = sensor ?: return false
        this.listener = listener
        filter.reset()
        return sensorManager.registerListener(
            this,
            selected,
            SensorManager.SENSOR_DELAY_UI,
            callbackHandler,
        ).also { registered -> if (!registered) this.listener = null }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        listener = null
        filter.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        ) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val rawDegrees = normalizeDegrees(Math.toDegrees(orientation[0].toDouble()))
        filter.update(rawDegrees, event.timestamp / 1_000_000L)?.let { listener?.invoke(it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Circular smoothing prevents the marker jumping at the 359°/0° boundary. */
class CompassHeadingFilter(
    private val smoothing: Double = 0.2,
    private val minimumChangeDegrees: Double = 3.0,
    private val minimumIntervalMillis: Long = 200L,
) {
    private var filteredDegrees: Double? = null
    private var deliveredDegrees: Double? = null
    private var deliveredAtMillis = Long.MIN_VALUE

    fun reset() {
        filteredDegrees = null
        deliveredDegrees = null
        deliveredAtMillis = Long.MIN_VALUE
    }

    fun update(rawDegrees: Double, elapsedMillis: Long): Double? {
        if (!rawDegrees.isFinite()) return null
        val normalized = normalizeDegrees(rawDegrees)
        val filtered = filteredDegrees?.let { circularBlend(it, normalized, smoothing) } ?: normalized
        filteredDegrees = filtered
        val previous = deliveredDegrees
        val intervalPassed = deliveredAtMillis == Long.MIN_VALUE || elapsedMillis - deliveredAtMillis >= minimumIntervalMillis
        if (!intervalPassed || (previous != null && angularDifference(previous, filtered) < minimumChangeDegrees)) return null
        deliveredDegrees = filtered
        deliveredAtMillis = elapsedMillis
        return filtered
    }

    private fun circularBlend(previous: Double, next: Double, alpha: Double): Double {
        val oldRadians = Math.toRadians(previous)
        val newRadians = Math.toRadians(next)
        val x = (1.0 - alpha) * sin(oldRadians) + alpha * sin(newRadians)
        val y = (1.0 - alpha) * cos(oldRadians) + alpha * cos(newRadians)
        return normalizeDegrees(Math.toDegrees(atan2(x, y)))
    }

    private fun angularDifference(first: Double, second: Double): Double =
        kotlin.math.abs((normalizeDegrees(first - second + 180.0)) - 180.0)
}

private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
