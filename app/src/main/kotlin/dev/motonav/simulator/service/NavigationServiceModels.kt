package dev.motonav.simulator.service

import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.routing.RoadRoute
import dev.motonav.simulator.routing.RouteRequest
import dev.motonav.simulator.transport.DisplayDevice
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class NavigationStartPayload(
    val route: RoadRoute,
    val originalRequest: RouteRequest,
    val destinationLabel: String,
    val selectedDisplay: DisplayDevice?,
    val localRequestCount: Int = 0,
    /** False for imported lines: a hosted reroute would abandon the rider's own route. */
    val allowHostedReroute: Boolean = true,
)

/** In-process only: Google route geometry is never put in an Intent, Bundle or persistent store. */
object NavigationStartHandoff {
    private val pending = ConcurrentHashMap<String, NavigationStartPayload>()

    fun put(payload: NavigationStartPayload): String = UUID.randomUUID().toString().also { pending[it] = payload }
    fun consume(token: String): NavigationStartPayload? = pending.remove(token)
    fun discard(token: String) { pending.remove(token) }
    fun clear() { pending.clear() }
}

enum class ServiceStartResult { STARTED, ALREADY_ACTIVE, REJECTED }

class ServiceLifecycleGuard {
    var active: Boolean = false
        private set
    var generation: Long = 0
        private set

    fun start(hasPayload: Boolean): ServiceStartResult {
        if (active) return ServiceStartResult.ALREADY_ACTIVE
        if (!hasPayload) return ServiceStartResult.REJECTED
        generation++
        active = true
        return ServiceStartResult.STARTED
    }

    fun stop(): Long {
        generation++
        active = false
        return generation
    }

    fun accepts(callbackGeneration: Long): Boolean = active && callbackGeneration == generation
}

data class ServicePrerequisites(
    val hasRoute: Boolean,
    val betaWarningAccepted: Boolean,
    val preciseLocation: Boolean,
    val locationServicesEnabled: Boolean,
    val bluetoothPermission: Boolean,
)

object ServiceStartPolicy {
    fun failure(prerequisites: ServicePrerequisites): String? = when {
        !prerequisites.hasRoute -> "Load a route first"
        !prerequisites.betaWarningAccepted -> "Accept the motorcycle beta warning first"
        !prerequisites.preciseLocation -> "Precise location permission is required"
        !prerequisites.locationServicesEnabled -> "Phone location services are disabled"
        !prerequisites.bluetoothPermission -> "Bluetooth permission is required for the display service"
        else -> null
    }
}

data class NavigationNotificationModel(
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val hasContentIntent: Boolean = true,
    val hasStopAction: Boolean = true,
)

object NavigationNotificationModels {
    fun from(snapshot: NavigationSessionSnapshot): NavigationNotificationModel {
        val navigation = snapshot.navigation
        val destination = snapshot.destinationLabel ?: "destination"
        val gps = when (navigation?.state) {
            NavigationState.ACQUIRING_GPS -> "acquiring GPS"
            NavigationState.GPS_DEGRADED -> "GPS lost"
            NavigationState.REROUTING -> "rerouting"
            NavigationState.LOCATION_DISABLED -> "location disabled"
            NavigationState.WAITING_PERMISSION -> "location permission needed"
            NavigationState.ARRIVED -> "arrived"
            else -> "GPS active"
        }
        val distance = navigation?.metresToManeuver?.let { " · ${it.toInt().coerceAtLeast(0)} m to ${navigation.maneuver.name.lowercase()}" } ?: ""
        val ble = snapshot.transportState.notificationLabel()
        return NavigationNotificationModel(
            title = if (snapshot.phase == SessionPhase.ARRIVED) "Arrived at $destination" else "Navigating to $destination",
            text = "$gps$distance · $ble",
            ongoing = snapshot.phase != SessionPhase.ARRIVED,
        )
    }

    private fun dev.motonav.simulator.transport.TransportState.notificationLabel(): String = when (this) {
        dev.motonav.simulator.transport.TransportState.Idle -> "TFT disconnected"
        dev.motonav.simulator.transport.TransportState.Scanning -> "TFT scanning"
        is dev.motonav.simulator.transport.TransportState.Connecting -> "TFT connecting"
        is dev.motonav.simulator.transport.TransportState.Validating -> "TFT validating"
        is dev.motonav.simulator.transport.TransportState.Connected -> "TFT connected"
        is dev.motonav.simulator.transport.TransportState.Reconnecting -> "TFT reconnecting"
        dev.motonav.simulator.transport.TransportState.PermissionRequired -> "Bluetooth permission needed"
        dev.motonav.simulator.transport.TransportState.BluetoothDisabled -> "Bluetooth disabled"
        is dev.motonav.simulator.transport.TransportState.Error -> "TFT error"
    }
}

data class RecoveryMetadata(
    val active: Boolean,
    val destinationLabel: String?,
    val sessionStartMillis: Long?,
    val routeRevision: Long?,
    val localRequestCount: Int,
    val lastFailure: String?,
)

object RecoveryPolicy {
    fun message(metadata: RecoveryMetadata, serviceHasSession: Boolean): String? =
        if (metadata.active && !serviceHasSession) "Previous navigation was interrupted" else null
}
