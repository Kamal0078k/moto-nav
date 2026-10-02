package dev.motonav.simulator.service

import android.content.Context

class NavigationRecoveryStore(context: Context) {
    private val preferences = context.getSharedPreferences("navigation_recovery", Context.MODE_PRIVATE)

    fun read(): RecoveryMetadata = RecoveryMetadata(
        active = preferences.getBoolean("active", false),
        destinationLabel = preferences.getString("destination_label", null),
        sessionStartMillis = preferences.getLong("session_start", -1L).takeIf { it >= 0L },
        routeRevision = preferences.getLong("route_revision", -1L).takeIf { it > 0L },
        localRequestCount = preferences.getInt("local_request_count", 0),
        lastFailure = preferences.getString("last_failure", null),
    )

    fun markActive(snapshot: NavigationSessionSnapshot) {
        preferences.edit()
            .putBoolean("active", true)
            .putString("destination_label", snapshot.destinationLabel)
            .putLong("session_start", snapshot.startedAtWallClockMillis ?: -1L)
            .putLong("route_revision", snapshot.routeRevision ?: -1L)
            .putInt("local_request_count", snapshot.providerRequestCount)
            .putString("last_failure", snapshot.lastFailure)
            .apply()
    }

    fun markStopped(lastFailure: String? = null) {
        preferences.edit()
            .putBoolean("active", false)
            .putString("last_failure", lastFailure)
            .apply()
    }
}
