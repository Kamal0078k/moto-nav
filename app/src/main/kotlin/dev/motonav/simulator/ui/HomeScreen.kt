package dev.motonav.simulator.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.motonav.simulator.OledPreviewView
import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.routing.UsageLedger
import dev.motonav.simulator.transport.TransportState
import java.util.Locale

interface AppActions {
    fun openRouteSetup()
    fun closeSheet()
    fun importSharedText(text: String)
    fun pickRouteFile()
    fun buildSharedRoute(sheet: Sheet.ImportPreview, name: String, save: Boolean)
    fun locateMissingStops(sheet: Sheet.ImportPreview)
    fun openSavedRoute(id: String)
    fun renameSavedRoute(id: String, name: String)
    fun deleteSavedRoute(id: String)
    fun searchPlace(origin: Boolean)
    fun fillOriginFromLocation()
    fun updateManualForm(form: ManualForm)
    fun loadManualRoute()
    fun startRide()
    fun stopRide()
    fun retryRoute()
    fun scanDisplays()
    fun connectDisplay(index: Int)
    fun disconnectDisplay()
    fun setCallAlerts(enabled: Boolean)
    fun setNowPlaying(enabled: Boolean)
    fun showIdleScreen()
}

@Composable
fun HomeScreen(state: AppState, actions: AppActions) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Moto.colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        TopBar(state, actions)
        Spacer(Modifier.height(8.dp))
        DisplayCard(state)
        Spacer(Modifier.height(12.dp))
        RouteCard(state, actions)
        Spacer(Modifier.height(12.dp))
        RideControls(state, actions)
        Spacer(Modifier.height(20.dp))
        SectionLabel("On the display", Modifier.padding(start = 4.dp, bottom = 8.dp))
        CallAlertsCard(state, actions)
        Spacer(Modifier.height(20.dp))
        SectionLabel("Google API budget", Modifier.padding(start = 4.dp, bottom = 8.dp))
        UsageCard(state)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TopBar(state: AppState, actions: AppActions) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Moto.colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.TwoWheeler, contentDescription = null, tint = Moto.colors.onAccent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("MotoNav", style = MaterialTheme.typography.titleLarge, color = Moto.colors.text)
            Text("Heads-up display companion", style = MaterialTheme.typography.bodySmall, color = Moto.colors.textMuted)
        }
        DisplayChip(state.transport, onClick = {
            if (state.transport is TransportState.Connected) actions.disconnectDisplay() else actions.scanDisplays()
        })
    }
}

@Composable
private fun DisplayChip(transport: TransportState, onClick: () -> Unit) {
    val (label, color, icon) = when (transport) {
        is TransportState.Connected -> Triple("Connected", Moto.colors.success, Icons.Filled.BluetoothConnected)
        TransportState.Scanning -> Triple("Scanning", Moto.colors.info, Icons.Filled.BluetoothSearching)
        is TransportState.Connecting, is TransportState.Validating -> Triple("Connecting", Moto.colors.info, Icons.Filled.BluetoothSearching)
        is TransportState.Reconnecting -> Triple("Reconnecting", Moto.colors.warning, Icons.Filled.BluetoothSearching)
        TransportState.BluetoothDisabled -> Triple("Bluetooth off", Moto.colors.warning, Icons.Filled.BluetoothDisabled)
        TransportState.PermissionRequired -> Triple("Allow Bluetooth", Moto.colors.warning, Icons.Filled.BluetoothDisabled)
        is TransportState.Error -> Triple("Display error", Moto.colors.danger, Icons.Filled.BluetoothDisabled)
        TransportState.Idle -> Triple("Pair display", Moto.colors.textMuted, Icons.Filled.Bluetooth)
    }
    val background by animateColorAsState(color.copy(alpha = 0.14f), label = "chip")
    Row(
        Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClickLabel = if (transport is TransportState.Connected) "Disconnect display" else "Pair display", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun DisplayCard(state: AppState) {
    val snapshot = state.navigation
    MotoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        // The phone preview is bit-exact with the 128×160 portrait TFT.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF020303))
                .border(1.dp, Moto.colors.outline, RoundedCornerShape(16.dp))
                .padding(10.dp),
        ) {
            val frame = previewFrame(state)
            if (snapshot == null) {
                Column(
                    Modifier.fillMaxWidth().aspectRatio(0.8f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Route, contentDescription = null, tint = Moto.colors.textFaint, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Display preview", style = MaterialTheme.typography.titleSmall, color = Moto.colors.textMuted)
                    Text("Load a route to see what your TFT will show", style = MaterialTheme.typography.bodySmall, color = Moto.colors.textFaint)
                }
            } else AndroidView(
                factory = { OledPreviewView(it).apply { setBackgroundColor(android.graphics.Color.rgb(2, 3, 3)) } },
                update = { it.show(frame) },
                modifier = Modifier.fillMaxWidth().aspectRatio(0.8f),
            )
        }
        if (snapshot != null && snapshot.state != NavigationState.READY) Column {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val maneuver = snapshot.maneuver
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Moto.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(maneuver, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "maneuver") {
                    Icon(maneuverIcon(it), contentDescription = maneuverLabel(it), tint = Moto.colors.accent, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    snapshot?.let { formatDistance(it.metresToManeuver) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    color = Moto.colors.text,
                )
                Text(maneuverLabel(maneuver), style = MaterialTheme.typography.bodyMedium, color = Moto.colors.textMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                NavigationPill(state)
                Spacer(Modifier.height(8.dp))
                Text(
                    snapshot?.let { "${formatDistance(it.remainingMetres)} left" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Moto.colors.textMuted,
                )
            }
        }
        }
        state.liveFailure?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Moto.colors.warning,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Moto.colors.warningSoft).padding(12.dp),
            )
        }
    }
}

/** The in-app preview mirrors the TFT firmware's media and call presentation. */
@Composable
private fun previewFrame(state: AppState): dev.motonav.simulator.display.MonochromeFrame {
    val base = animatedDisplayFrame(state)
    val call = state.callAlert ?: return base
    val onRoute = state.navigation?.state == NavigationState.NAVIGATING
    val caller = dev.motonav.simulator.protocol.FrameCodec.displayText(call.caller)
    val elapsedSeconds by androidx.compose.runtime.produceState(initialValue = 0L, call) {
        if (call.phase != dev.motonav.simulator.calls.CallPhase.ACTIVE) return@produceState
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            value = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1_000
            kotlinx.coroutines.delay(1_000)
        }
    }
    // On the route the caller takes over the footer (scrolling if the name is long); other ride
    // screens keep the header handset.
    val scrolls = onRoute && dev.motonav.simulator.display.OledRasterRenderer.callFooterNeedsScrolling(caller)
    val marquee by androidx.compose.runtime.produceState(0, call, scrolls) {
        if (!scrolls) return@produceState
        val started = System.currentTimeMillis()
        while (true) {
            value = dev.motonav.simulator.display.OledRasterRenderer
                .callFooterMarqueeOffset(caller, System.currentTimeMillis() - started)
            kotlinx.coroutines.delay(dev.motonav.simulator.display.IdleFace.FRAME_MS)
        }
    }
    val header = remember(base, call, onRoute, elapsedSeconds, marquee) {
        if (onRoute) {
            dev.motonav.simulator.display.OledRasterRenderer.withCallFooter(base, call.phase, caller, elapsedSeconds, marquee)
        } else {
            dev.motonav.simulator.display.OledRasterRenderer.withCallHeader(base, call.phase, null, elapsedSeconds)
        }
    }
    // During a ride the map stays visible. Outside a ride, every call phase has a dedicated screen.
    val rideActive = state.navigation?.state.let {
        it != NavigationState.READY && it != NavigationState.ROUTE_PREVIEW && it != NavigationState.DISCONNECTED && it != null
    }
    if (rideActive) return header
    val fullScrolls = dev.motonav.simulator.display.OledRasterRenderer.nowPlayingTitleNeedsScrolling(caller)
    val fullMarquee by androidx.compose.runtime.produceState(0, call, fullScrolls) {
        if (!fullScrolls) return@produceState
        val started = System.currentTimeMillis()
        while (true) {
            value = dev.motonav.simulator.display.OledRasterRenderer
                .nowPlayingTitleMarqueeOffset(caller, System.currentTimeMillis() - started)
            kotlinx.coroutines.delay(dev.motonav.simulator.display.IdleFace.FRAME_MS)
        }
    }
    return remember(call, elapsedSeconds, fullMarquee) {
        val status = if (call.phase == dev.motonav.simulator.calls.CallPhase.MISSED) "missed call" else "calling..."
        dev.motonav.simulator.display.OledRasterRenderer.callFrame(
            caller = caller,
            status = status,
            phase = call.phase,
            elapsedSeconds = elapsedSeconds,
            marqueeOffsetPx = fullMarquee,
        )
    }
}

/** Animates the idle face and the now-playing ticker (music is not shown during a live route). */
@Composable
private fun animatedDisplayFrame(state: AppState): dev.motonav.simulator.display.MonochromeFrame {
    val snapshot = state.navigation
    val title = snapshot?.primaryText
        ?.let { dev.motonav.simulator.protocol.FrameCodec.displayText(it, dev.motonav.simulator.protocol.FrameCodec.MAX_TEXT_LENGTH) }
        ?.takeIf { it.isNotBlank() }
    val artist = snapshot?.secondaryText
        ?.let { dev.motonav.simulator.protocol.FrameCodec.displayText(it, dev.motonav.simulator.protocol.FrameCodec.MAX_TEXT_LENGTH) }
        ?.takeIf { it.isNotBlank() }
    if (snapshot?.state != NavigationState.READY) return state.frame
    val clock = state.clockText
    val face = remember { dev.motonav.simulator.display.IdleFace() }
    val frame by androidx.compose.runtime.produceState(
        initialValue = dev.motonav.simulator.display.OledRasterRenderer.readyFrame(clock, title, artist = artist),
        title, artist, clock,
    ) {
        val started = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val offset = if (title != null && dev.motonav.simulator.display.OledRasterRenderer.nowPlayingTitleNeedsScrolling(title)) {
                dev.motonav.simulator.display.OledRasterRenderer.nowPlayingTitleMarqueeOffset(title, now - started)
            } else 0
            val artistOffset = if (artist != null && dev.motonav.simulator.display.OledRasterRenderer.titleNeedsScrolling(artist)) {
                dev.motonav.simulator.display.OledRasterRenderer.marqueeOffset(artist, now - started)
            } else 0
            value = dev.motonav.simulator.display.OledRasterRenderer.readyFrame(
                clock,
                title,
                offset,
                face.pose(now),
                artist,
                artistOffset,
            )
            kotlinx.coroutines.delay(dev.motonav.simulator.display.IdleFace.FRAME_MS)
        }
    }
    return frame
}

@Composable
private fun NavigationPill(state: AppState) {
    val snapshot = state.navigation
    if (!state.liveActive) {
        val arrived = snapshot?.state == NavigationState.ARRIVED
        Pill(if (arrived) "Arrived" else "Preview", if (arrived) Moto.colors.success else Moto.colors.textMuted, dot = true)
        return
    }
    val (label, color) = when (snapshot?.state) {
        NavigationState.NAVIGATING -> "On route" to Moto.colors.success
        NavigationState.ACQUIRING_GPS -> "Finding GPS" to Moto.colors.warning
        NavigationState.GPS_DEGRADED -> "GPS weak" to Moto.colors.warning
        NavigationState.REROUTING -> "Rerouting" to Moto.colors.info
        NavigationState.ARRIVED -> "Arrived" to Moto.colors.success
        NavigationState.LOCATION_DISABLED -> "Location off" to Moto.colors.danger
        NavigationState.WAITING_PERMISSION -> "Needs permission" to Moto.colors.danger
        NavigationState.DISCONNECTED, NavigationState.READY, NavigationState.ROUTE_PREVIEW, null -> "Idle" to Moto.colors.textMuted
    }
    Pill(label, color, dot = true)
}

@Composable
private fun RouteCard(state: AppState, actions: AppActions) {
    val route = state.route
    MotoCard(onClick = actions::openRouteSetup) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Route", Modifier.weight(1f))
            if (route != null) SourceBadge(route)
        }
        Spacer(Modifier.height(10.dp))
        if (route == null) {
            Text("No route yet", style = MaterialTheme.typography.titleLarge, color = Moto.colors.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "Share directions from Google Maps, or open a GPX / KML file.",
                style = MaterialTheme.typography.bodyMedium,
                color = Moto.colors.textMuted,
            )
        } else {
            Text(route.name, style = MaterialTheme.typography.titleLarge, color = Moto.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat(formatDistance(route.metres), "distance")
                route.durationMillis?.let { Stat(formatDuration(it), if (route.kind == RouteKind.IMPORTED) "est. time" else "time") }
                Stat(route.turns.toString(), if (route.turns == 1) "turn" else "turns")
                if (route.stops > 0) Stat(route.stops.toString(), if (route.stops == 1) "stop" else "stops")
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                if (route == null) "Plan a route" else "Change route",
                onClick = actions::openRouteSetup,
                icon = if (route == null) Icons.Outlined.Add else Icons.Filled.Route,
                modifier = Modifier.weight(1f),
            )
            // Clears the picked route so the display returns to its idle clock screen.
            if (route != null && !state.liveActive) {
                SecondaryButton("Back to clock", onClick = actions::showIdleScreen, icon = Icons.Filled.Schedule, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SourceBadge(route: RouteSummary) {
    when (route.kind) {
        RouteKind.IMPORTED -> Pill("Offline · 0 API calls", Moto.colors.success, icon = Icons.Outlined.CloudOff)
        RouteKind.GOOGLE -> Pill(
            if (route.apiRequests == 1) "Google · 1 request" else "Google · ${route.apiRequests} requests",
            Moto.colors.info,
            icon = Icons.Outlined.Map,
        )
    }
}

@Composable
private fun RideControls(state: AppState, actions: AppActions) {
    AnimatedContent(state.liveActive, label = "ride") { live ->
        if (live) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    "End ride",
                    onClick = actions::stopRide,
                    icon = Icons.Filled.Stop,
                    container = Moto.colors.danger,
                    content = Color(0xFF1A0505),
                    modifier = Modifier.weight(1f),
                )
                if (state.route?.reroutesEnabled == true) {
                    SecondaryButton("Reroute", onClick = actions::retryRoute, icon = Icons.Filled.Refresh, modifier = Modifier.height(56.dp))
                }
            }
        } else {
            val canRide = state.route != null
            Column {
                PrimaryButton(
                    "Start ride",
                    onClick = actions::startRide,
                    icon = Icons.Filled.TwoWheeler,
                    enabled = canRide,
                )
                AnimatedVisibility(!canRide) {
                    Text(
                        "Load a route to ride with live GPS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Moto.colors.textFaint,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CallAlertsCard(state: AppState, actions: AppActions) {
    MotoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        ListRow(
            icon = Icons.Filled.MusicNote,
            title = "Now playing",
            subtitle = if (state.nowPlayingOn) "Song title shown while idle and navigating"
            else "Show the playing song on idle and live-route screens",
            tint = Moto.colors.info,
            onClick = { actions.setNowPlaying(!state.nowPlayingOn) },
        ) {
            androidx.compose.material3.Switch(
                checked = state.nowPlayingOn,
                onCheckedChange = actions::setNowPlaying,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = Moto.colors.accent,
                    checkedThumbColor = Moto.colors.onAccent,
                ),
            )
        }
        androidx.compose.material3.HorizontalDivider(color = Moto.colors.outline, modifier = Modifier.padding(start = 58.dp))
        ListRow(
            icon = Icons.Filled.Call,
            title = "Incoming calls",
            subtitle = if (state.callAlertsOn) "Caller and handset stay visible above the route · phone calls only"
            else "Show who's calling on the display",
            tint = Moto.colors.success,
            onClick = { actions.setCallAlerts(!state.callAlertsOn) },
        ) {
            androidx.compose.material3.Switch(
                checked = state.callAlertsOn,
                onCheckedChange = actions::setCallAlerts,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = Moto.colors.accent,
                    checkedThumbColor = Moto.colors.onAccent,
                ),
            )
        }
    }
}

@Composable
private fun UsageCard(state: AppState) {
    val usage = state.usage
    MotoCard {
        if (!state.hasMapsKey) {
            Text("No Maps key configured", style = MaterialTheme.typography.titleSmall, color = Moto.colors.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "Imported GPX / KML files still work fully offline.",
                style = MaterialTheme.typography.bodySmall,
                color = Moto.colors.textMuted,
            )
            return@MotoCard
        }
        Meter("Routes requests today", usage?.dailyAttempts ?: 0, UsageLedger.DAILY_STOP_ATTEMPTS)
        Spacer(Modifier.height(16.dp))
        Meter("Routes requests this month", usage?.attempts ?: 0, UsageLedger.STOP_ATTEMPTS)
        Spacer(Modifier.height(14.dp))
        Text(
            "Shared links and files skip Places entirely. Resets ${usage?.resetDate ?: "monthly"}.",
            style = MaterialTheme.typography.bodySmall,
            color = Moto.colors.textFaint,
        )
    }
}

fun maneuverIcon(maneuver: Maneuver): ImageVector = when (maneuver) {
    Maneuver.LEFT -> Icons.Filled.TurnLeft
    Maneuver.RIGHT -> Icons.Filled.TurnRight
    Maneuver.ARRIVE -> Icons.Filled.SportsScore
    Maneuver.STRAIGHT -> Icons.Filled.Straight
}

fun maneuverLabel(maneuver: Maneuver): String = when (maneuver) {
    Maneuver.LEFT -> "then turn left"
    Maneuver.RIGHT -> "then turn right"
    Maneuver.ARRIVE -> "to destination"
    Maneuver.STRAIGHT -> "continue straight"
}

fun formatDistance(metres: Double): String = when {
    metres < 1000 -> "${(metres / 10).toInt() * 10} m"
    metres < 10_000 -> String.format(Locale.US, "%.1f km", metres / 1000)
    else -> "${(metres / 1000).toInt()} km"
}

fun formatDuration(millis: Long): String {
    val minutes = (millis / 60_000.0).let { kotlin.math.ceil(it).toLong() }.coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
