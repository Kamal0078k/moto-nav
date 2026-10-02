package dev.motonav.simulator.location

import dev.motonav.simulator.navigation.NavigationState

enum class LocationPermissionLevel { DENIED, APPROXIMATE, PRECISE }

object LocationReadiness {
    fun state(permission: LocationPermissionLevel, servicesEnabled: Boolean): NavigationState = when {
        permission != LocationPermissionLevel.PRECISE -> NavigationState.WAITING_PERMISSION
        !servicesEnabled -> NavigationState.LOCATION_DISABLED
        else -> NavigationState.ACQUIRING_GPS
    }
}
