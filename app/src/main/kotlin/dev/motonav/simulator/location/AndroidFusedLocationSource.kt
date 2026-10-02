package dev.motonav.simulator.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

@SuppressLint("MissingPermission")
class AndroidFusedLocationSource(
    context: Context,
) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val settings = LocationServices.getSettingsClient(context)
    private var listener: LocationSource.Listener? = null
    private var started = false

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
        .setMinUpdateIntervalMillis(500L)
        .setMinUpdateDistanceMeters(2f)
        .setMaxUpdateDelayMillis(1_000L)
        .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val samples = result.locations.map { location ->
                GeoLocationSample(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    wallClockMillis = location.time,
                    elapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000L,
                    horizontalAccuracyMetres = location.accuracy.toDouble(),
                    bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() },
                    bearingAccuracyDegrees = location.bearingAccuracyDegrees.toDouble().takeIf { location.hasBearingAccuracy() },
                    speedMetresPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() },
                    speedAccuracyMetresPerSecond = location.speedAccuracyMetersPerSecond.toDouble().takeIf { location.hasSpeedAccuracy() },
                    isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider,
                )
            }.sortedBy { it.elapsedRealtimeMillis }
            if (samples.isNotEmpty()) listener?.onSamples(samples)
        }
    }

    override fun start(listener: LocationSource.Listener) {
        stop()
        this.listener = listener
        started = true
        val settingsRequest = LocationSettingsRequest.Builder().addLocationRequest(request).build()
        settings.checkLocationSettings(settingsRequest)
            .addOnSuccessListener { if (started) requestUpdates() }
            .addOnFailureListener { error ->
                if (!started) return@addOnFailureListener
                listener.onLocationDisabled(null)
            }
    }

    override fun stop() {
        started = false
        client.removeLocationUpdates(callback)
        listener = null
    }

    private fun requestUpdates() {
        runCatching { client.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
            .onFailure { listener?.onError("Precise foreground location permission is required") }
    }
}
