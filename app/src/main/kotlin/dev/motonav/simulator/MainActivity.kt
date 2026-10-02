package dev.motonav.simulator

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.android.libraries.places.widget.PlaceAutocomplete
import com.google.android.libraries.places.widget.PlaceAutocompleteActivity
import dev.motonav.simulator.calls.CallAlerts
import dev.motonav.simulator.display.OledRasterRenderer
import dev.motonav.simulator.media.NowPlaying
import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.NavigationSnapshot
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.navigation.RoutePreview
import dev.motonav.simulator.navigation.RouteRenderer
import dev.motonav.simulator.protocol.DisplayFrame
import dev.motonav.simulator.routing.AndroidRouteLoader
import dev.motonav.simulator.routing.CoordinateInput
import dev.motonav.simulator.routing.FileRouteLibraryStore
import dev.motonav.simulator.routing.GeoPoint
import dev.motonav.simulator.routing.GoogleMapsLink
import dev.motonav.simulator.routing.MonotonicRouteRevisionSource
import dev.motonav.simulator.routing.RoadRoute
import dev.motonav.simulator.routing.RouteFileImporter
import dev.motonav.simulator.routing.RouteLibrary
import dev.motonav.simulator.routing.RouteRequest
import dev.motonav.simulator.routing.RouteStop
import dev.motonav.simulator.routing.RoutingCancellation
import dev.motonav.simulator.routing.RoutingException
import dev.motonav.simulator.routing.SavedRoute
import dev.motonav.simulator.routing.SharedDirections
import dev.motonav.simulator.routing.SharedPreferencesUsageLedgerStore
import dev.motonav.simulator.routing.SharedWaypoint
import dev.motonav.simulator.routing.ShortLinkResolver
import dev.motonav.simulator.routing.TrackRouteBuilder
import dev.motonav.simulator.routing.UsageLedger
import dev.motonav.simulator.service.NavigationRecoveryStore
import dev.motonav.simulator.service.NavigationService
import dev.motonav.simulator.service.NavigationSessionSnapshot
import dev.motonav.simulator.service.NavigationStartPayload
import dev.motonav.simulator.service.RecoveryPolicy
import dev.motonav.simulator.transport.AndroidBleDisplayTransport
import dev.motonav.simulator.transport.DisplayDevice
import dev.motonav.simulator.transport.DisplayLink
import dev.motonav.simulator.transport.DisplayTransport
import dev.motonav.simulator.transport.TransportState
import dev.motonav.simulator.ui.AppActions
import dev.motonav.simulator.ui.AppState
import dev.motonav.simulator.ui.Busy
import dev.motonav.simulator.ui.ManualForm
import dev.motonav.simulator.ui.MotoNavApp
import dev.motonav.simulator.ui.Prompt
import dev.motonav.simulator.ui.RouteKind
import dev.motonav.simulator.ui.RouteSummary
import dev.motonav.simulator.ui.Sheet
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), AppActions {
    companion object {
        private const val BLE_PERMISSION_REQUEST = 41
        private const val BLUETOOTH_ENABLE_REQUEST = 42
        private const val LOCATION_PERMISSION_REQUEST = 43
        private const val PLACE_AUTOCOMPLETE_REQUEST = 44
        private const val LOCATION_SETTINGS_REQUEST = 45
        private const val NOTIFICATION_PERMISSION_REQUEST = 46
        private const val ROUTE_FILE_REQUEST = 47
        private const val CALL_PERMISSION_REQUEST = 48
    }

    private val state = AppState()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val renderer = RouteRenderer()
    private val routeRevisions = MonotonicRouteRevisionSource()
    private lateinit var routeLoader: AndroidRouteLoader
    private lateinit var usageLedger: UsageLedger
    private lateinit var library: RouteLibrary
    private var placesClient: PlacesClient? = null
    private var pendingPlaceOrigin: Boolean? = null
    private var pendingPlaceSession: AutocompleteSessionToken? = null
    private var pendingLocationFix: ((GeoPoint) -> Unit)? = null
    private lateinit var transport: AndroidBleDisplayTransport
    private var activityTransportAvailable = false
    private var loadedRoadRoute: RoadRoute? = null
    private var loadedRouteRequest: RouteRequest? = null
    private var loadedDestinationLabel = "destination"
    private var loadedFrom = ""
    private var loadedTo = ""
    private var loadedAllowsReroute = true
    private var betaWarningAccepted = false
    private var selectedDisplay: DisplayDevice?
        get() = DisplayLink.selectedDisplay
        set(value) { DisplayLink.selectedDisplay = value }
    private var pendingServiceStart = false
    private var notificationPromptExplained = false
    private var locationSettingsPrompted = false
    private var foregroundSession = false
        set(value) {
            field = value
            state.liveActive = value
        }
    private var serviceBound = false
    private var serviceBinder: NavigationService.LocalBinder? = null
    private lateinit var recoveryStore: NavigationRecoveryStore
    private var pendingLocationAction = LocationAction.NONE
    private var scanDevices = listOf<DisplayDevice>()

    private enum class LocationAction { NONE, FIX, LIVE }

    private val serviceListener = NavigationService.Listener(::handleServiceSnapshot)
    private val callListener = CallAlerts.Listener { alert ->
        handler.post {
            state.callAlert = alert
            rerenderPreview()
        }
    }

    /** A new song (or music stopping) refreshes both idle and live-route screens. */
    private val nowPlayingListener = NowPlaying.Listener {
        handler.post {
            val snapshot = state.navigation ?: return@post
            if (snapshot.state == NavigationState.READY) {
                render(snapshot, submitToActivityTransport = !foregroundSession)
            }
        }
    }

    /** Re-draws the in-app preview (time, call header) without resending anything to the display. */
    private fun rerenderPreview() {
        state.navigation?.let { render(it, submitToActivityTransport = false) }
    }

    private fun currentClockText(): String = OledRasterRenderer.formatClock(java.time.LocalTime.now(), twentyFourHour = true)

    /** Ticks on each minute boundary so the preview clock matches the display. */
    private val clockTicker = object : Runnable {
        override fun run() {
            rerenderPreview()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceBinder = binder as? NavigationService.LocalBinder
            serviceBound = serviceBinder != null
            serviceBinder?.addListener(serviceListener)
            val snapshot = serviceBinder?.snapshot()
            if (!foregroundSession && snapshot != null && RecoveryPolicy.message(recoveryStore.read(), snapshot.isActive) != null) {
                recoveryStore.markStopped("Previous navigation was interrupted")
                state.toast("Your last ride was interrupted. Load the route again to continue.")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            serviceBound = false
            if (foregroundSession && !recoveryStore.read().active) {
                foregroundSession = false
                ensureActivityTransport()
                state.toast("Ride ended.")
            }
        }
    }

    private val transportListener = object : DisplayTransport.Listener {
        override fun onStateChanged(state: TransportState) {
            this@MainActivity.state.transport = state
            if (state !is TransportState.Connected) this@MainActivity.state.lastFrameSequence = null
        }

        override fun onScanResults(devices: List<DisplayDevice>) {
            scanDevices = devices
            state.scanDevices = devices
        }

        override fun onFrameSent(sequence: Int) {
            state.lastFrameSequence = sequence
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        usageLedger = UsageLedger(SharedPreferencesUsageLedgerStore(this))
        routeLoader = AndroidRouteLoader(this, BuildConfig.MAPS_API_KEY, usageLedger, routeRevisions)
        library = RouteLibrary(FileRouteLibraryStore(File(filesDir, "routes.json")))
        recoveryStore = NavigationRecoveryStore(this)
        state.hasMapsKey = BuildConfig.MAPS_API_KEY.isNotBlank()
        state.savedRoutes = library.all()
        refreshUsage()
        ensureActivityTransport()
        refreshCallAlerts()
        CallAlerts.addListener(callListener)
        NowPlaying.addListener(nowPlayingListener)
        setContent { MotoNavApp(state, this) }
        render(RoutePreview.ready())
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        refreshCallAlerts()
        state.nowPlayingOn = NowPlaying.hasAccess(this) && NowPlaying.isEnabled(this)
        handler.removeCallbacks(clockTicker)
        handler.post(clockTicker)
        if (recoveryStore.read().active) {
            bindNavigationService()
        } else if (foregroundSession) {
            // The notification Stop action may have ended the service while this activity was hidden.
            foregroundSession = false
            ensureActivityTransport()
        }
    }

    override fun onStop() {
        handler.removeCallbacks(clockTicker)
        unbindNavigationService()
        super.onStop()
    }

    override fun onDestroy() {
        CallAlerts.removeListener(callListener)
        NowPlaying.removeListener(nowPlayingListener)
        routeLoader.close()
        io.shutdownNow()
        if (activityTransportAvailable) {
            transport.removeListener(transportListener)
            // Keep the display connected across recreation; drop it only when the rider leaves the app.
            if (isFinishing && !foregroundSession && !recoveryStore.read().active) DisplayLink.release()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Incoming shares

    /** Google Maps → Share → MotoNav, or a GPX/KML file opened from another app. */
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val stream = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        when (intent.action) {
            Intent.ACTION_SEND -> when {
                stream != null -> importRouteFile(stream)
                intent.getStringExtra(Intent.EXTRA_TEXT) != null -> importSharedText(intent.getStringExtra(Intent.EXTRA_TEXT)!!)
            }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") importSharedText(uri.toString()) else importRouteFile(uri)
            }
        }
    }

    // ------------------------------------------------------------------ AppActions: route setup

    override fun openRouteSetup() {
        if (foregroundSession) {
            state.toast("End the ride before changing route.")
            return
        }
        state.sheet = Sheet.RouteSetup
    }

    override fun closeSheet() {
        when (state.sheet) {
            Sheet.Scanner -> if (activityTransportAvailable) transport.stopScan()
            else -> Unit
        }
        state.sheet = null
    }

    override fun importSharedText(text: String) {
        if (foregroundSession) {
            state.toast("End the ride before importing another route.")
            return
        }
        val url = GoogleMapsLink.extractUrl(text)
        if (url == null || !GoogleMapsLink.isGoogleMapsUrl(url)) {
            state.toast(if (text.isBlank()) "Paste a Google Maps link first." else "That isn't a Google Maps link.")
            return
        }
        if (!GoogleMapsLink.isShortLink(url)) {
            showDirections(url)
            return
        }
        var cancelled = false
        state.busy = Busy("Opening link", "Expanding the Google Maps share link…", onCancel = { cancelled = true; state.busy = null })
        io.execute {
            val result = runCatching { ShortLinkResolver().resolve(url) }
            handler.post {
                if (cancelled) return@post
                state.busy = null
                result.onSuccess(::showDirections).onFailure {
                    state.toast("Couldn't open the link. Check your connection and try again.")
                }
            }
        }
    }

    private fun showDirections(url: String) {
        runCatching { GoogleMapsLink.parse(url) }
            .onSuccess { directions ->
                state.sheet = Sheet.ImportPreview(directions, suggestedName(directions))
            }
            .onFailure { state.toast(it.message ?: "No directions found in that link.") }
    }

    private fun suggestedName(directions: SharedDirections): String {
        val to = directions.destination.label.ifBlank { "Destination" }
        val from = directions.origin.label.takeIf { it.isNotBlank() && it != GoogleMapsLink.PINNED_LABEL }
        return if (from == null) "To $to" else "$from → $to"
    }

    override fun buildSharedRoute(sheet: Sheet.ImportPreview, name: String, save: Boolean) {
        if (!state.hasMapsKey) {
            state.toast("Add a Maps key to route shared links — or open a GPX/KML file for fully offline routes.")
            return
        }
        val directions = sheet.directions
        if (save && sheet.savedId == null) {
            library.save(SavedRoute.Link(SavedRoute.newId(), name, System.currentTimeMillis(), directions.waypoints))
            state.savedRoutes = library.all()
        }
        fun build(current: GeoPoint?) {
            runCatching { directions.toStops(current) }
                .onSuccess { stops ->
                    // Only the link's own stops are saved; Google's route result stays in memory for this ride.
                    loadGoogleRoute(stops, name, placeName(directions.origin, "Your location"), placeName(directions.destination, "Destination"))
                }
                .onFailure { state.toast(it.message ?: "Those directions can't be routed.") }
        }
        if (directions.needsCurrentLocation) requestLocationFix(::build) else build(null)
    }

    /**
     * Some share links name a stop without its coordinates. Look each one up once with a
     * single-result Text Search, then persist the coordinates so the route never needs it again.
     */
    override fun locateMissingStops(sheet: Sheet.ImportPreview) {
        val client = placesClientOrNull() ?: run {
            state.toast("Looking up stops needs a Maps key.")
            return
        }
        val waypoints = sheet.directions.waypoints
        val missing = waypoints.indices.filter { waypoints[it].routeStop() == null && !(it == 0 && waypoints[it].label.isBlank()) }
        if (missing.isEmpty()) return
        val updated = waypoints.toMutableList()
        var remaining = missing.size
        var failed = false
        state.busy = Busy("Finding stops", "Looking up ${missing.size} place${if (missing.size > 1) "s" else ""} once…")
        missing.forEach { index ->
            val stop = waypoints[index]
            val request = SearchByTextRequest.builder(stop.label, listOf(Place.Field.LOCATION, Place.Field.DISPLAY_NAME))
                .setMaxResultCount(1)
                .setRegionCode("IN")
                .build()
            client.searchByText(request)
                .addOnSuccessListener { response ->
                    val location = response.places.firstOrNull()?.location
                    if (location == null) failed = true
                    else updated[index] = SharedWaypoint(stop.label, GeoPoint(location.latitude, location.longitude))
                }
                .addOnFailureListener { failed = true }
                .addOnCompleteListener {
                    remaining--
                    if (remaining > 0) return@addOnCompleteListener
                    state.busy = null
                    val directions = SharedDirections(updated)
                    sheet.savedId?.let { id ->
                        (library.find(id) as? SavedRoute.Link)?.let { library.save(it.copy(waypoints = updated)) }
                        state.savedRoutes = library.all()
                    }
                    state.sheet = sheet.copy(directions = directions)
                    if (failed) state.toast("Some stops couldn't be found. Pick them from search results in Google Maps and share again.")
                }
        }
    }

    private fun placesClientOrNull(): PlacesClient? {
        if (BuildConfig.MAPS_API_KEY.isBlank()) return null
        return placesClient ?: runCatching {
            if (!Places.isInitialized()) {
                Places.initializeWithNewPlacesApiEnabled(applicationContext, BuildConfig.MAPS_API_KEY, Locale.forLanguageTag("en-IN"))
            }
            Places.createClient(this)
        }.getOrNull()?.also { placesClient = it }
    }

    override fun pickRouteFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, ROUTE_FILE_REQUEST)
    }

    private fun importRouteFile(uri: Uri) {
        if (foregroundSession) {
            state.toast("End the ride before importing another route.")
            return
        }
        state.busy = Busy("Reading route", "Detecting turns on your phone — no API calls.")
        io.execute {
            val result = runCatching {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("The file could not be opened.")
                val track = RouteFileImporter.import(bytes, name)
                track to TrackRouteBuilder(routeRevisions).build(track.points)
            }
            handler.post {
                state.busy = null
                result.onSuccess { (track, route) ->
                    library.save(SavedRoute.Track(SavedRoute.newId(), track.name, System.currentTimeMillis(), track.format, track.points))
                    state.savedRoutes = library.all()
                    setLoadedRoute(route, requestFor(route), track.name, RouteKind.IMPORTED, from = "Route start", to = track.name)
                }.onFailure {
                    state.toast((it as? dev.motonav.simulator.routing.RouteImportException)?.message
                        ?: "That file couldn't be opened. Try picking it from the Files app.")
                }
            }
        }
    }

    override fun openSavedRoute(id: String) {
        when (val saved = library.find(id)) {
            is SavedRoute.Link -> state.sheet = Sheet.ImportPreview(SharedDirections(saved.waypoints), saved.name, savedId = saved.id)
            is SavedRoute.Track -> {
                state.busy = Busy("Loading route", "Preparing ${saved.name}…")
                io.execute {
                    val result = runCatching { TrackRouteBuilder(routeRevisions).build(saved.points) }
                    handler.post {
                        state.busy = null
                        result.onSuccess { setLoadedRoute(it, requestFor(it), saved.name, RouteKind.IMPORTED, from = "Route start", to = saved.name) }
                            .onFailure { state.toast(it.message ?: "That route couldn't be loaded.") }
                    }
                }
            }
            null -> state.toast("That route no longer exists.")
        }
    }

    override fun renameSavedRoute(id: String, name: String) {
        runCatching { library.rename(id, name) }.onFailure { state.toast(it.message ?: "Couldn't rename.") }
        state.savedRoutes = library.all()
    }

    override fun deleteSavedRoute(id: String) {
        library.delete(id)
        state.savedRoutes = library.all()
    }

    override fun updateManualForm(form: ManualForm) {
        state.manualForm = form
    }

    override fun fillOriginFromLocation() {
        requestLocationFix { point ->
            state.manualForm = state.manualForm.copy(
                originLatitude = point.latitude.toString(),
                originLongitude = point.longitude.toString(),
                originName = null,
            )
        }
    }

    override fun loadManualRoute() {
        val form = state.manualForm
        runCatching {
            RouteRequest(
                CoordinateInput.parse(form.originLatitude, form.originLongitude),
                CoordinateInput.parse(form.destinationLatitude, form.destinationLongitude),
            )
        }.onSuccess { request ->
            loadGoogleRoute(
                listOf(RouteStop.At(request.origin), RouteStop.At(request.destination)),
                form.destinationName ?: "Destination",
                from = form.originName ?: "Start point",
                to = form.destinationName ?: "Destination",
            )
        }.onFailure { state.toast(it.message ?: "Check all four coordinates.") }
    }

    override fun searchPlace(origin: Boolean) {
        if (placesClientOrNull() == null) {
            state.toast("Place search needs a Maps key.")
            return
        }
        val session = AutocompleteSessionToken.newInstance()
        pendingPlaceOrigin = origin
        pendingPlaceSession = session
        val intent = PlaceAutocomplete.createIntent(this) {
            setCountries(listOf("IN"))
            setAutocompleteSessionToken(session)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PLACE_AUTOCOMPLETE_REQUEST)
    }

    private fun handlePlaceAutocompleteResult(resultCode: Int, data: Intent?) {
        val origin = pendingPlaceOrigin
        val fallbackSession = pendingPlaceSession
        pendingPlaceOrigin = null
        pendingPlaceSession = null
        if (resultCode == RESULT_CANCELED) return
        if (origin == null || data == null) {
            state.toast("Place search didn't return a selection.")
            return
        }
        if (resultCode != PlaceAutocompleteActivity.RESULT_OK) {
            val status = PlaceAutocomplete.getResultStatusFromIntent(data)
            state.toast(status?.statusMessage ?: "Place search failed.")
            return
        }
        val prediction = PlaceAutocomplete.getPredictionFromIntent(data) ?: return
        val session = PlaceAutocomplete.getSessionTokenFromIntent(data) ?: fallbackSession
        val request = FetchPlaceRequest.builder(prediction.placeId, listOf(Place.Field.DISPLAY_NAME, Place.Field.LOCATION)).apply {
            setRegionCode("IN")
            session?.let(::setSessionToken)
        }.build()
        placesClient?.fetchPlace(request)
            ?.addOnSuccessListener { response ->
                val location = response.place.location ?: run {
                    state.toast("That place has no usable coordinates.")
                    return@addOnSuccessListener
                }
                val name = response.place.displayName ?: prediction.getPrimaryText(null).toString()
                state.manualForm = if (origin) {
                    state.manualForm.copy(originLatitude = location.latitude.toString(), originLongitude = location.longitude.toString(), originName = name)
                } else {
                    state.manualForm.copy(destinationLatitude = location.latitude.toString(), destinationLongitude = location.longitude.toString(), destinationName = name)
                }
            }
            ?.addOnFailureListener { state.toast(it.message ?: "Couldn't load that place.") }
    }

    // ------------------------------------------------------------------ Route loading

    private fun requestFor(route: RoadRoute): RouteRequest {
        val first = route.geometry.first()
        val last = route.geometry.last()
        return if (first != last) RouteRequest(first, last) else RouteRequest(first, last, listOf(route.geometry[route.geometry.size / 2]))
    }

    private fun placeName(stop: SharedWaypoint, fallback: String): String =
        stop.label.takeIf { it.isNotBlank() } ?: fallback

    private fun loadGoogleRoute(
        stops: List<RouteStop>,
        destinationLabel: String,
        from: String,
        to: String,
    ) {
        if (usageLedger.current().blocked) {
            state.toast("The local Google request budget is used up. Imported files still work offline.")
            return
        }
        val cancellation = RoutingCancellation()
        state.busy = Busy("Building route", "Asking Google for one motorcycle route…", onCancel = {
            cancellation.cancel()
            state.busy = null
        })
        routeLoader.load(stops, cancellation) { result ->
            refreshUsage()
            if (cancellation.isCancelled()) return@load
            state.busy = null
            result.onSuccess { route ->
                // Rerouting needs coordinates, so use the destination Google actually resolved.
                val request = runCatching {
                    RouteRequest(route.geometry.first(), route.legEnds.lastOrNull() ?: route.geometry.last())
                }.getOrNull() ?: requestFor(route)
                setLoadedRoute(route, request, destinationLabel, RouteKind.GOOGLE, from, to, stops = stops.size - 2)
            }.onFailure { error ->
                state.toast((error as? RoutingException)?.failure?.userMessage ?: error.message ?: "Route request failed")
            }
        }
    }

    private fun currentPreview(): NavigationSnapshot = loadedRoadRoute?.let {
        RoutePreview.snapshot(it, loadedDestinationLabel, loadedFrom, loadedTo)
    } ?: RoutePreview.ready()

    private fun setLoadedRoute(
        route: RoadRoute,
        request: RouteRequest,
        name: String,
        kind: RouteKind,
        from: String,
        to: String,
        stops: Int = 0,
    ) {
        loadedFrom = from
        loadedTo = to
        loadedRoadRoute = route
        loadedRouteRequest = request
        loadedDestinationLabel = name
        loadedAllowsReroute = kind == RouteKind.GOOGLE
        // The overview sheet shows the beta / imported-route warning before a ride can start from it.
        betaWarningAccepted = true
        render(RoutePreview.snapshot(route, name, from, to))
        val summary = RouteSummary(
            name = name,
            kind = kind,
            metres = route.totalMetres.toDouble(),
            durationMillis = route.totalDurationMillis,
            turns = route.steps.count { it.maneuver.oledManeuver() == Maneuver.LEFT || it.maneuver.oledManeuver() == Maneuver.RIGHT },
            stops = stops,
            apiRequests = if (kind == RouteKind.GOOGLE) 1 else 0,
        )
        state.route = summary
        state.sheet = Sheet.Overview(summary)
    }

    private fun refreshUsage() {
        state.usage = usageLedger.current()
    }

    // ------------------------------------------------------------------ Location

    private fun requestLocationFix(onFix: (GeoPoint) -> Unit) {
        pendingLocationFix = onFix
        if (!hasAnyLocationPermission()) {
            pendingLocationAction = LocationAction.FIX
            val permissions = if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            requestPermissions(permissions, LOCATION_PERMISSION_REQUEST)
        } else {
            requestCurrentLocation()
        }
    }

    private fun hasAnyLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasPreciseLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION", "MissingPermission")
    private fun requestCurrentLocation() {
        val manager = getSystemService(LocationManager::class.java)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                state.toast("Turn on phone location, then try again.")
                return
            }
        }
        state.busy = Busy("Finding you", "Getting one GPS fix for the start point…", onCancel = {
            pendingLocationFix = null
            state.busy = null
        })
        fun accept(location: Location?) {
            state.busy = null
            val callback = pendingLocationFix ?: return
            pendingLocationFix = null
            if (location == null) {
                state.toast("No GPS fix yet — step outside or try again.")
                return
            }
            callback(GeoPoint(location.latitude, location.longitude))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            manager.getCurrentLocation(provider, null, mainExecutor, ::accept)
        } else {
            manager.requestSingleUpdate(provider, object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) = accept(location)
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Legacy callback")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }, Looper.getMainLooper())
        }
    }

    // ------------------------------------------------------------------ Results

    @Deprecated("Uses the platform activity result API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            when (pendingLocationAction) {
                LocationAction.FIX -> if (hasAnyLocationPermission()) requestCurrentLocation()
                    else state.toast("Location permission is needed to start from where you are.")
                LocationAction.LIVE -> if (hasPreciseLocationPermission()) continueForegroundStart()
                    else showPreciseLocationRequired()
                LocationAction.NONE -> Unit
            }
            pendingLocationAction = LocationAction.NONE
            return
        }
        if (requestCode == CALL_PERMISSION_REQUEST) {
            if (CallAlerts.hasPermissions(this)) {
                CallAlerts.setEnabled(this, true)
                state.toast("Incoming calls will show on the display.")
            } else if (CallAlerts.requiredPermissions.any { !shouldShowRequestPermissionRationale(it) }) {
                state.prompt = Prompt(
                    title = "Allow phone and contacts",
                    message = "To show who's calling, allow Phone, Call logs and Contacts for MotoNav in app settings.",
                    confirmLabel = "Open settings",
                    onConfirm = ::openAppSettings,
                )
            }
            refreshCallAlerts()
            return
        }
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (pendingServiceStart) launchForegroundNavigation()
            return
        }
        if (requestCode != BLE_PERMISSION_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (pendingServiceStart) continueForegroundStart() else startDeviceSelection()
        } else {
            state.transport = TransportState.PermissionRequired
            if (pendingServiceStart) {
                pendingServiceStart = false
                state.toast("Bluetooth permission is needed before a ride can start.")
            }
        }
    }

    @Deprecated("Uses the platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PLACE_AUTOCOMPLETE_REQUEST -> handlePlaceAutocompleteResult(resultCode, data)
            ROUTE_FILE_REQUEST -> if (resultCode == RESULT_OK) data?.data?.let(::importRouteFile)
            LOCATION_SETTINGS_REQUEST -> if (pendingServiceStart) continueForegroundStart()
            BLUETOOTH_ENABLE_REQUEST -> if (resultCode == RESULT_OK) showDeviceScanner() else state.transport = TransportState.BluetoothDisabled
        }
    }

    // ------------------------------------------------------------------ Live ride

    override fun startRide() {
        if (foregroundSession) {
            state.toast("A ride is already active.")
            return
        }
        if (loadedRoadRoute == null) {
            state.toast("Load a route first — share one from Google Maps or open a file.")
            return
        }
        if (!betaWarningAccepted) {
            state.toast("Review the route overview before starting.")
            return
        }
        pendingServiceStart = true
        locationSettingsPrompted = false
        continueForegroundStart()
    }

    private fun continueForegroundStart() {
        if (!pendingServiceStart) return
        if (!hasPreciseLocationPermission()) {
            pendingLocationAction = LocationAction.LIVE
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST,
            )
            return
        }
        if (!locationServicesEnabled()) {
            if (locationSettingsPrompted) {
                pendingServiceStart = false
                state.toast("Location is still off. Turn it on, then tap Start ride again.")
                return
            }
            locationSettingsPrompted = true
            state.prompt = Prompt(
                title = "Turn on location",
                message = "Navigation needs phone location services before the ride can start.",
                confirmLabel = "Open settings",
                onConfirm = {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), LOCATION_SETTINGS_REQUEST)
                },
                onDismiss = { pendingServiceStart = false },
            )
            return
        }
        val missingBluetooth = requiredBlePermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missingBluetooth.isNotEmpty()) {
            requestPermissions(missingBluetooth.toTypedArray(), BLE_PERMISSION_REQUEST)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPromptExplained
        ) {
            notificationPromptExplained = true
            state.prompt = Prompt(
                title = "Ride notification",
                message = "An ongoing notification shows GPS and display status, with a Stop button while the phone is locked.",
                confirmLabel = "Allow",
                onConfirm = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST) },
                dismissLabel = "Not now",
                onDismiss = { launchForegroundNavigation() },
            )
            return
        }
        launchForegroundNavigation()
    }

    private fun launchForegroundNavigation() {
        val route = loadedRoadRoute ?: return
        val originalRequest = loadedRouteRequest ?: return
        pendingServiceStart = false
        detachActivityTransport()
        foregroundSession = true
        state.liveFailure = null
        runCatching {
            NavigationService.start(
                this,
                NavigationStartPayload(
                    route,
                    originalRequest,
                    loadedDestinationLabel,
                    selectedDisplay,
                    usageLedger.current().attempts,
                    allowHostedReroute = loadedAllowsReroute,
                ),
            )
            bindNavigationService()
        }.onFailure { error ->
            foregroundSession = false
            ensureActivityTransport()
            state.toast("The ride couldn't start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun stopRide() {
        pendingServiceStart = false
        serviceBinder?.stopNavigation() ?: runCatching { NavigationService.stop(this) }
    }

    override fun retryRoute() {
        serviceBinder?.retryReroute()
        refreshUsage()
    }

    private fun showPreciseLocationRequired() {
        pendingServiceStart = false
        state.prompt = Prompt(
            title = "Precise location needed",
            message = "Approximate location can be off by hundreds of metres, so it can't pick the right road or turn. Allow precise location to ride.",
            confirmLabel = "Open app settings",
            onConfirm = { openAppSettings() },
            dismissLabel = "Not now",
        )
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
    }

    private fun render(original: NavigationSnapshot, submitToActivityTransport: Boolean = true) {
        // Music only appears on the idle screen; the route screen is kept for navigation and calls.
        val snapshot = if (original.state == NavigationState.READY) {
            val track = NowPlaying.current
            original.copy(primaryText = track?.title, secondaryText = track?.artist)
        } else original
        val route = renderer.render(snapshot)
        val clock = currentClockText()
        state.clockText = clock
        state.frame = OledRasterRenderer.render(snapshot, route, clock)
        state.navigation = snapshot
        if (submitToActivityTransport && activityTransportAvailable && !foregroundSession) transport.submit(
            DisplayFrame(
                sequence = 0,
                state = snapshot.state,
                maneuver = snapshot.maneuver,
                nextTurnMetres = snapshot.metresToManeuver.roundToInt().coerceIn(0, 0xffff),
                remainingDistanceMetres = snapshot.remainingMetres.roundToInt().coerceAtLeast(0).toLong(),
                remainingDurationMinutes = snapshot.remainingDurationMillis?.let {
                    ((it + 59_999L) / 60_000L).coerceIn(0L, 0xffffL).toInt()
                },
                mapHeadingDegrees = snapshot.headingDegrees?.roundToInt()?.mod(360) ?: 0,
                points = route.points,
                contentSource = snapshot.contentSource,
                primaryText = snapshot.primaryText,
                secondaryText = snapshot.secondaryText,
            ),
        )
    }

    // ------------------------------------------------------------------ Service

    private fun handleServiceSnapshot(snapshot: NavigationSessionSnapshot) {
        val wasForeground = foregroundSession
        foregroundSession = snapshot.isActive
        state.transport = snapshot.transportState
        if (snapshot.isActive) {
            detachActivityTransport()
        } else if (wasForeground) {
            ensureActivityTransport()
            refreshUsage()
        }
        if (!snapshot.isActive && wasForeground) {
            // Ride over: the display goes back to the picked route (or the greeting).
            render(currentPreview())
        } else {
            snapshot.navigation?.let { render(it, submitToActivityTransport = false) }
        }
        state.liveFailure = snapshot.lastFailure
    }

    private fun bindNavigationService() {
        if (serviceBound) return
        bindService(Intent(this, NavigationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindNavigationService() {
        val binder = serviceBinder
        if (serviceBound) {
            binder?.removeListener(serviceListener)
            unbindService(serviceConnection)
        }
        serviceBinder = null
        serviceBound = false
    }

    private fun ensureActivityTransport() {
        if (activityTransportAvailable) return
        transport = DisplayLink.acquire(this)
        activityTransportAvailable = true
        transport.setListener(transportListener)
    }

    /** Hands the live display link to the ride service; the connection itself stays up. */
    private fun detachActivityTransport() {
        if (!activityTransportAvailable) return
        transport.removeListener(transportListener)
        activityTransportAvailable = false
    }

    private fun locationServicesEnabled(): Boolean {
        val manager = getSystemService(LocationManager::class.java)
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // ------------------------------------------------------------------ Display pairing

    override fun scanDisplays() = startDeviceSelection()

    override fun setNowPlaying(enabled: Boolean) {
        if (enabled && !NowPlaying.hasAccess(this)) {
            state.prompt = Prompt(
                title = "Show what's playing?",
                message = "Android only shares the current song with apps that have notification access. " +
                    "Turn on MotoNav in the next screen. It reads the song title and artist only; your notifications aren't read or sent anywhere.",
                confirmLabel = "Open settings",
                onConfirm = {
                    NowPlaying.setEnabled(this, true)
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                dismissLabel = "Not now",
            )
            return
        }
        NowPlaying.setEnabled(this, enabled)
        state.nowPlayingOn = enabled && NowPlaying.hasAccess(this)
        state.navigation?.takeIf {
            it.state == NavigationState.READY || it.state == NavigationState.NAVIGATING
        }?.let { render(it, submitToActivityTransport = !foregroundSession) }
    }

    override fun showIdleScreen() {
        if (foregroundSession) return
        loadedRoadRoute = null
        loadedRouteRequest = null
        betaWarningAccepted = false
        state.route = null
        render(RoutePreview.ready())
    }

    override fun setCallAlerts(enabled: Boolean) {
        if (!enabled) {
            CallAlerts.setEnabled(this, false)
            refreshCallAlerts()
            return
        }
        if (CallAlerts.hasPermissions(this)) {
            CallAlerts.setEnabled(this, true)
            refreshCallAlerts()
            return
        }
        state.prompt = Prompt(
            title = "Show calls on the display?",
            message = "MotoNav needs Phone and Call log access to see who's calling or who you're calling, and Contacts to show their name. " +
                "The name is only sent to your paired display. Phone calls only — not WhatsApp.",
            confirmLabel = "Continue",
            onConfirm = { requestPermissions(CallAlerts.requestedPermissions, CALL_PERMISSION_REQUEST) },
            dismissLabel = "Not now",
        )
    }

    private fun refreshCallAlerts() {
        state.callAlertsOn = CallAlerts.isEnabled(this) && CallAlerts.hasPermissions(this)
        if (state.callAlertsOn) CallAlerts.start(this)
    }

    override fun connectDisplay(index: Int) {
        scanDevices.getOrNull(index)?.let { device ->
            selectedDisplay = device
            transport.connect(device)
        }
        closeSheet()
    }

    override fun disconnectDisplay() {
        state.prompt = Prompt(
            title = "Disconnect display?",
            message = "The handlebar TFT will stop receiving turns until you pair again.",
            confirmLabel = "Disconnect",
            destructive = true,
            onConfirm = {
                if (foregroundSession) serviceBinder?.disconnectDisplay() else if (activityTransportAvailable) transport.disconnect()
            },
        )
    }

    private fun startDeviceSelection() {
        if (foregroundSession) {
            state.toast("End the ride before pairing another display.")
            return
        }
        ensureActivityTransport()
        val missing = requiredBlePermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            val preferences = getPreferences(MODE_PRIVATE)
            if (preferences.getBoolean("ble_permission_requested", false) && missing.any { !shouldShowRequestPermissionRationale(it) }) {
                state.prompt = Prompt(
                    title = "Allow nearby devices",
                    message = "Bluetooth permission was denied. Open app settings, allow Nearby devices, then tap Pair display again.",
                    confirmLabel = "Open settings",
                    onConfirm = ::openAppSettings,
                )
                return
            }
            preferences.edit().putBoolean("ble_permission_requested", true).apply()
            requestPermissions(missing.toTypedArray(), BLE_PERMISSION_REQUEST)
            return
        }
        if (!transport.isBluetoothEnabled()) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BLUETOOTH_ENABLE_REQUEST)
            return
        }
        showDeviceScanner()
    }

    private fun showDeviceScanner() {
        scanDevices = emptyList()
        state.scanDevices = emptyList()
        state.sheet = Sheet.Scanner
        transport.startScan()
    }

    private fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
