package dev.motonav.simulator.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.motonav.simulator.routing.SavedRoute
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheets(state: AppState, actions: AppActions) {
    val sheet = state.sheet ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = actions::closeSheet,
        sheetState = sheetState,
        containerColor = Moto.colors.surface,
        contentColor = Moto.colors.text,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Moto.colors.outlineStrong))
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            when (sheet) {
                Sheet.RouteSetup -> RouteSetupContent(state, actions)
                is Sheet.ImportPreview -> ImportPreviewContent(sheet, state, actions)
                is Sheet.Overview -> OverviewContent(sheet.summary, state, actions)
                Sheet.Scanner -> ScannerContent(state, actions)
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = Moto.colors.text)
    if (subtitle != null) {
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Moto.colors.textMuted)
    }
    Spacer(Modifier.height(20.dp))
}

// ---------------------------------------------------------------- Route setup

@Composable
private fun RouteSetupContent(state: AppState, actions: AppActions) {
    SheetHeader("Plan a route", "Bring directions from Google Maps — no place lookups, no extra cost.")

    GoogleMapsImportCard(actions)
    Spacer(Modifier.height(12.dp))

    MotoCard(onClick = actions::pickRouteFile) {
        ListRow(
            icon = Icons.Outlined.FileOpen,
            title = "Open a route file",
            subtitle = "GPX · KML · KMZ · GeoJSON — from My Maps, Earth or any ride app",
            tint = Moto.colors.success,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Moto.colors.textFaint)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Works offline", Moto.colors.success, icon = Icons.Outlined.CloudOff)
            Pill("0 API calls", Moto.colors.success)
        }
    }

    if (state.savedRoutes.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        SectionLabel("My routes", Modifier.padding(start = 4.dp, bottom = 6.dp))
        MotoCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            state.savedRoutes.forEachIndexed { index, route ->
                if (index > 0) HorizontalDivider(color = Moto.colors.outline, modifier = Modifier.padding(start = 58.dp))
                SavedRouteRow(route, actions)
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    AdvancedOptions(state, actions)
}

@Composable
private fun GoogleMapsImportCard(actions: AppActions) {
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf("") }
    MotoCard(highlighted = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.Map, Moto.colors.accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("From Google Maps", style = MaterialTheme.typography.titleMedium, color = Moto.colors.text)
                Text("Recommended · skips Places API", style = MaterialTheme.typography.bodySmall, color = Moto.colors.accent)
            }
        }
        Spacer(Modifier.height(16.dp))
        Step(1, "Open Google Maps and get directions — add stops if you like.")
        Step(2, "Tap ⋮ → Share directions, then pick MotoNav.")
        Step(3, "Or copy the link and paste it below.")
        Spacer(Modifier.height(14.dp))
        MotoTextField(
            value = link,
            onValueChange = { link = it },
            label = "Google Maps link",
            placeholder = "https://maps.app.goo.gl/…",
            trailing = {
                IconButton(onClick = { clipboard.getText()?.text?.let { link = it } }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste from clipboard", tint = Moto.colors.textMuted)
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            "Import directions",
            onClick = {
                val text = link.ifBlank { clipboard.getText()?.text.orEmpty() }
                actions.importSharedText(text)
            },
            icon = Icons.Outlined.Link,
            height = 52.dp,
        )
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(Moto.colors.surfaceRaised).border(1.dp, Moto.colors.outlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = Moto.colors.text)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Moto.colors.textMuted, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun SavedRouteRow(route: SavedRoute, actions: AppActions) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val (icon, tint, subtitle) = when (route) {
        is SavedRoute.Track -> Triple(Icons.Outlined.Timeline, Moto.colors.success, "${formatDistance(route.lengthMetres)} · ${route.format.label} · offline")
        is SavedRoute.Link -> Triple(
            Icons.Outlined.Link,
            Moto.colors.info,
            buildString {
                val stops = route.waypoints.size - 2
                append(if (route.waypoints.first().point == null) "From your location" else route.waypoints.first().label)
                append(" → ").append(route.waypoints.last().label)
                if (stops > 0) append(" · $stops stop${if (stops > 1) "s" else ""}")
            },
        )
    }
    ListRow(icon, route.name, subtitle, tint = tint, onClick = { actions.openSavedRoute(route.id) }) {
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${route.name}", tint = Moto.colors.textMuted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Moto.colors.surfaceRaised) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menu = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = Moto.colors.danger) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = Moto.colors.danger) },
                    onClick = { menu = false; confirmingDelete = true },
                )
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(route.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = Moto.colors.surfaceRaised,
            title = { Text("Rename route") },
            text = { MotoTextField(name, { name = it }, "Name") },
            confirmButton = {
                TextButton(onClick = { actions.renameSavedRoute(route.id, name); renaming = false }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = Moto.colors.textMuted) } },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = Moto.colors.surfaceRaised,
            title = { Text("Delete “${route.name}”?") },
            text = { Text("It will be removed from My routes on this phone.", color = Moto.colors.textMuted) },
            confirmButton = {
                TextButton(onClick = { actions.deleteSavedRoute(route.id); confirmingDelete = false }) { Text("Delete", color = Moto.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel", color = Moto.colors.textMuted) } },
        )
    }
}

@Composable
private fun AdvancedOptions(state: AppState, actions: AppActions) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Other ways", style = MaterialTheme.typography.titleSmall, color = Moto.colors.text)
            Text("Place search or raw coordinates", style = MaterialTheme.typography.bodySmall, color = Moto.colors.textMuted)
        }
        Icon(Icons.Filled.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand", tint = Moto.colors.textMuted, modifier = Modifier.rotate(rotation))
    }
    AnimatedVisibility(expanded) {
        val form = state.manualForm
        Column(Modifier.padding(top = 8.dp)) {
            if (state.hasMapsKey) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Moto.colors.warningSoft).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Moto.colors.warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Place search is billed per lookup (Places API).", style = MaterialTheme.typography.bodySmall, color = Moto.colors.warning)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(form.originName ?: "Search start", onClick = { actions.searchPlace(true) }, icon = Icons.Filled.Search, modifier = Modifier.weight(1f))
                    SecondaryButton(form.destinationName ?: "Search end", onClick = { actions.searchPlace(false) }, icon = Icons.Filled.Search, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            }
            Text("Start", style = MaterialTheme.typography.labelLarge, color = Moto.colors.text)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MotoTextField(form.originLatitude, { actions.updateManualForm(form.copy(originLatitude = it, originName = null)) }, "Latitude", Modifier.weight(1f), numeric = true)
                MotoTextField(form.originLongitude, { actions.updateManualForm(form.copy(originLongitude = it, originName = null)) }, "Longitude", Modifier.weight(1f), numeric = true)
            }
            TextButton(onClick = actions::fillOriginFromLocation) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp), tint = Moto.colors.accent)
                Spacer(Modifier.width(8.dp))
                Text("Use my current location", color = Moto.colors.accent)
            }
            Spacer(Modifier.height(4.dp))
            Text("Destination", style = MaterialTheme.typography.labelLarge, color = Moto.colors.text)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MotoTextField(form.destinationLatitude, { actions.updateManualForm(form.copy(destinationLatitude = it, destinationName = null)) }, "Latitude", Modifier.weight(1f), numeric = true)
                MotoTextField(form.destinationLongitude, { actions.updateManualForm(form.copy(destinationLongitude = it, destinationName = null)) }, "Longitude", Modifier.weight(1f), numeric = true)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Build route · 1 request", onClick = actions::loadManualRoute, icon = Icons.Filled.TwoWheeler, height = 52.dp, enabled = state.hasMapsKey)
        }
    }
}

// ---------------------------------------------------------------- Import preview

@Composable
private fun ImportPreviewContent(sheet: Sheet.ImportPreview, state: AppState, actions: AppActions) {
    var name by remember(sheet.suggestedName) { mutableStateOf(sheet.suggestedName) }
    var save by remember(sheet.savedId) { mutableStateOf(sheet.savedId == null) }
    val directions = sheet.directions
    SheetHeader("Directions imported", "Read straight from the link — no Places lookups were made.")

    MotoCard {
        directions.waypoints.forEachIndexed { index, stop ->
            val first = index == 0
            val last = index == directions.waypoints.lastIndex
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
                    Box(
                        Modifier.size(14.dp).clip(CircleShape).background(
                            when {
                                first -> Moto.colors.info
                                last -> Moto.colors.accent
                                else -> Moto.colors.textMuted
                            },
                        ).border(3.dp, Moto.colors.surface, CircleShape),
                    )
                    if (!last) Box(Modifier.width(2.dp).height(34.dp).background(Moto.colors.outlineStrong))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 10.dp)) {
                    Text(
                        when {
                            stop.point == null && first && stop.label.isBlank() -> "Your current location"
                            stop.label.isBlank() -> "Stop $index"
                            else -> stop.label
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = Moto.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            stop.point != null -> String.format(Locale.US, "%.5f, %.5f", stop.point.latitude, stop.point.longitude)
                            first && stop.label.isBlank() -> "GPS fix taken when you build"
                            stop.routeStop() != null -> "Located by Google in the route request"
                            else -> "No location in link"
                        },
                        style = if (stop.point != null) NumericStyle else MaterialTheme.typography.bodySmall,
                        color = if (stop.routeStop() == null && !(first && stop.label.isBlank())) Moto.colors.warning else Moto.colors.textFaint,
                    )
                }
            }
        }
    }
    val missing = directions.waypoints.withIndex().count { (i, stop) -> stop.routeStop() == null && !(i == 0 && stop.label.isBlank()) }
    if (missing > 0) {
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Moto.colors.warningSoft).padding(14.dp)) {
            Text(
                if (missing == 1) "1 stop needs coordinates" else "$missing stops need coordinates",
                style = MaterialTheme.typography.titleSmall,
                color = Moto.colors.warning,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "This link has no address or coordinates for them. Look them up once — they're saved with the route.",
                style = MaterialTheme.typography.bodySmall,
                color = Moto.colors.warning,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                if (missing == 1) "Look up · 1 Places request" else "Look up · $missing Places requests",
                onClick = { actions.locateMissingStops(sheet) },
                icon = Icons.Filled.Search,
                enabled = state.hasMapsKey,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    MotoTextField(name, { name = it }, "Route name")
    Spacer(Modifier.height(8.dp))
    if (sheet.savedId == null) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch) { save = !save }.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Save to My routes", style = MaterialTheme.typography.titleSmall, color = Moto.colors.text)
                Text("Ride it again later without re-sharing", style = MaterialTheme.typography.bodySmall, color = Moto.colors.textMuted)
            }
            Switch(
                checked = save,
                onCheckedChange = { save = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Moto.colors.accent, checkedThumbColor = Moto.colors.onAccent),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        "Build motorcycle route",
        enabled = missing == 0,
        onClick = { actions.buildSharedRoute(sheet, name.trim().ifEmpty { sheet.suggestedName }, save) },
        icon = Icons.Filled.TwoWheeler,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Uses 1 Google Routes request (TWO_WHEELER) to get road geometry and turns.",
        style = MaterialTheme.typography.bodySmall,
        color = Moto.colors.textFaint,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

// ---------------------------------------------------------------- Overview

@Composable
private fun OverviewContent(summary: RouteSummary, state: AppState, actions: AppActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Route ready", style = MaterialTheme.typography.headlineSmall, color = Moto.colors.text, modifier = Modifier.weight(1f))
        SourceBadge(summary)
    }
    Spacer(Modifier.height(4.dp))
    Text(summary.name, style = MaterialTheme.typography.bodyLarge, color = Moto.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(20.dp))
    MotoCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(formatDistance(summary.metres), "distance")
            summary.durationMillis?.let { Stat(formatDuration(it), if (summary.kind == RouteKind.IMPORTED) "est. at 25 km/h" else "with traffic") }
            Stat(summary.turns.toString(), "turns")
        }
    }
    Spacer(Modifier.height(12.dp))
    val warning = when (summary.kind) {
        RouteKind.GOOGLE -> "Motorcycle routing is in beta and may not always be optimal. Watch road signs, access restrictions and conditions."
        RouteKind.IMPORTED -> "Turns are detected from the line’s shape. Automatic rerouting is off so you stay on your own route — watch road signs."
    }
    warning?.let {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Moto.colors.warningSoft).padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Moto.colors.warning, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Moto.colors.warning)
        }
        Spacer(Modifier.height(16.dp))
    }
    PrimaryButton("Start ride", onClick = { actions.closeSheet(); actions.startRide() }, icon = Icons.Filled.TwoWheeler)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Done", onClick = actions::closeSheet, modifier = Modifier.weight(1f))
    }
    if (state.transport !is dev.motonav.simulator.transport.TransportState.Connected) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: pair the handlebar display first so turns show up the moment you start.",
            style = MaterialTheme.typography.bodySmall,
            color = Moto.colors.textFaint,
        )
    }
}

// ---------------------------------------------------------------- Scanner

@Composable
private fun ScannerContent(state: AppState, actions: AppActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Pair display", style = MaterialTheme.typography.headlineSmall, color = Moto.colors.text)
            Text("Power on the MotoNav TFT nearby.", style = MaterialTheme.typography.bodyMedium, color = Moto.colors.textMuted)
        }
        CircularProgressIndicator(Modifier.size(22.dp), color = Moto.colors.accent, strokeWidth = 2.5.dp)
    }
    Spacer(Modifier.height(20.dp))
    if (state.scanDevices.isEmpty()) {
        MotoCard {
            Text("Searching…", style = MaterialTheme.typography.titleSmall, color = Moto.colors.text)
            Text("Displays advertising the MotoNav service will appear here.", style = MaterialTheme.typography.bodySmall, color = Moto.colors.textMuted)
        }
    } else {
        MotoCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            state.scanDevices.forEachIndexed { index, device ->
                if (index > 0) HorizontalDivider(color = Moto.colors.outline, modifier = Modifier.padding(start = 58.dp))
                ListRow(Icons.Outlined.Bluetooth, device.name, device.address, tint = Moto.colors.info, onClick = { actions.connectDisplay(index) }) {
                    Text("Connect", style = MaterialTheme.typography.labelLarge, color = Moto.colors.accent, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Dialogs

@Composable
fun AppDialogs(state: AppState) {
    state.prompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { state.prompt = null; prompt.onDismiss() },
            containerColor = Moto.colors.surfaceRaised,
            titleContentColor = Moto.colors.text,
            textContentColor = Moto.colors.textMuted,
            title = { Text(prompt.title) },
            text = { Text(prompt.message) },
            confirmButton = {
                TextButton(onClick = { state.prompt = null; prompt.onConfirm() }) {
                    Text(prompt.confirmLabel, color = if (prompt.destructive) Moto.colors.danger else Moto.colors.accent)
                }
            },
            dismissButton = prompt.dismissLabel?.let { label ->
                { TextButton(onClick = { state.prompt = null; prompt.onDismiss() }) { Text(label, color = Moto.colors.textMuted) } }
            },
        )
    }
    state.busy?.let { busy ->
        AlertDialog(
            onDismissRequest = { },
            containerColor = Moto.colors.surfaceRaised,
            icon = { CircularProgressIndicator(Modifier.size(28.dp), color = Moto.colors.accent, strokeWidth = 3.dp) },
            title = { Text(busy.title, color = Moto.colors.text) },
            text = { Text(busy.detail, color = Moto.colors.textMuted) },
            confirmButton = {
                busy.onCancel?.let { cancel -> TextButton(onClick = cancel) { Text("Cancel", color = Moto.colors.textMuted) } }
            },
        )
    }
}
