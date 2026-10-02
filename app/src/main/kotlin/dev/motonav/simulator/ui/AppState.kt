package dev.motonav.simulator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.motonav.simulator.display.MonochromeFrame
import dev.motonav.simulator.navigation.NavigationSnapshot
import dev.motonav.simulator.routing.SavedRoute
import dev.motonav.simulator.routing.SharedDirections
import dev.motonav.simulator.routing.UsageSnapshot
import dev.motonav.simulator.transport.DisplayDevice
import dev.motonav.simulator.transport.TransportState

enum class RouteKind { GOOGLE, IMPORTED }

/** What the route card and overview sheet show about the loaded route. */
data class RouteSummary(
    val name: String,
    val kind: RouteKind,
    val metres: Double,
    val durationMillis: Long?,
    val turns: Int,
    val stops: Int = 0,
    /** Hosted requests this route has cost so far; 0 for imported routes. */
    val apiRequests: Int = 0,
    val reroutesEnabled: Boolean = kind == RouteKind.GOOGLE,
)

sealed interface Sheet {
    data object RouteSetup : Sheet
    data class ImportPreview(
        val directions: SharedDirections,
        val suggestedName: String,
        /** Set when re-opening a saved link, so it is not saved twice. */
        val savedId: String? = null,
    ) : Sheet
    data class Overview(val summary: RouteSummary) : Sheet
    data object Scanner : Sheet
}

data class Prompt(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
    val dismissLabel: String? = "Cancel",
    val onDismiss: () -> Unit = { },
    val destructive: Boolean = false,
)

data class Busy(val title: String, val detail: String, val onCancel: (() -> Unit)? = null)

/** Manual coordinates / Places selections for the advanced route form. */
data class ManualForm(
    val originLatitude: String = "",
    val originLongitude: String = "",
    val originName: String? = null,
    val destinationLatitude: String = "",
    val destinationLongitude: String = "",
    val destinationName: String? = null,
)

class AppState {
    var frame by mutableStateOf(MonochromeFrame())
    var navigation by mutableStateOf<NavigationSnapshot?>(null)
    var transport by mutableStateOf<TransportState>(TransportState.Idle)
    var lastFrameSequence by mutableStateOf<Int?>(null)
    var route by mutableStateOf<RouteSummary?>(null)
    var liveActive by mutableStateOf(false)
    var liveFailure by mutableStateOf<String?>(null)
    var usage by mutableStateOf<UsageSnapshot?>(null)
    var savedRoutes by mutableStateOf<List<SavedRoute>>(emptyList())
    var sheet by mutableStateOf<Sheet?>(null)
    var prompt by mutableStateOf<Prompt?>(null)
    var busy by mutableStateOf<Busy?>(null)
    var scanDevices by mutableStateOf<List<DisplayDevice>>(emptyList())
    var manualForm by mutableStateOf(ManualForm())
    var hasMapsKey by mutableStateOf(false)
    var callAlert by mutableStateOf<dev.motonav.simulator.calls.CallAlert?>(null)
    var callAlertsOn by mutableStateOf(false)
    var clockText by mutableStateOf<String?>(null)
    var nowPlayingOn by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var messageSerial by mutableStateOf(0)

    fun toast(text: String) {
        message = text
        messageSerial++
    }
}
