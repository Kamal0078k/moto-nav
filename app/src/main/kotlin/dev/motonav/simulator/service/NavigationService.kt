package dev.motonav.simulator.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.motonav.simulator.MainActivity
import dev.motonav.simulator.BuildConfig
import dev.motonav.simulator.location.AndroidFusedLocationSource
import dev.motonav.simulator.location.AndroidCompassHeadingSource
import dev.motonav.simulator.location.GeoLocationSample
import dev.motonav.simulator.location.LocationSource
import dev.motonav.simulator.location.MonotonicClock
import dev.motonav.simulator.media.NowPlaying
import dev.motonav.simulator.navigation.NavigationSnapshot
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.navigation.RouteRenderer
import dev.motonav.simulator.protocol.DisplayFrame
import dev.motonav.simulator.routing.AndroidIdentity
import dev.motonav.simulator.routing.GoogleRouteParser
import dev.motonav.simulator.routing.GoogleRoutesProvider
import dev.motonav.simulator.routing.MonotonicRouteRevisionSource
import dev.motonav.simulator.routing.RoutingCancellation
import dev.motonav.simulator.routing.RoutingException
import dev.motonav.simulator.routing.RoutingFailure
import dev.motonav.simulator.routing.RoutingProvider
import dev.motonav.simulator.routing.SharedPreferencesUsageLedgerStore
import dev.motonav.simulator.routing.UrlConnectionHttpClient
import dev.motonav.simulator.routing.UsageLedger
import dev.motonav.simulator.transport.AndroidBleDisplayTransport
import dev.motonav.simulator.transport.DisplayLink
import dev.motonav.simulator.transport.DisplayTransport
import dev.motonav.simulator.transport.TransportState
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class NavigationService : Service() {
    fun interface Listener {
        fun onSessionSnapshot(snapshot: NavigationSessionSnapshot)
    }

    inner class LocalBinder : Binder() {
        fun snapshot(): NavigationSessionSnapshot = latestSnapshot

        fun addListener(listener: Listener) {
            listeners += listener
            mainHandler.post { listener.onSessionSnapshot(latestSnapshot) }
        }

        fun removeListener(listener: Listener) { listeners -= listener }
        fun stopNavigation() { ownerHandler.post { stopSession() } }
        fun retryReroute() {
            ownerHandler.post {
                val request = controller.requestManualReroute()
                if (request == null) publish(controller.snapshot()) else startReroute(request)
            }
        }
        fun disconnectDisplay() { mainHandler.post { transport?.disconnect() } }
    }

    companion object {
        const val ACTION_START = "dev.motonav.simulator.action.START_NAVIGATION"
        const val ACTION_STOP = "dev.motonav.simulator.action.STOP_NAVIGATION"
        const val EXTRA_HANDOFF_TOKEN = "handoff_token"
        const val CHANNEL_ID = "active_navigation"
        const val NOTIFICATION_ID = 8010
        const val ARRIVAL_GRACE_MILLIS = 30_000L

        fun start(context: Context, payload: NavigationStartPayload) {
            val token = NavigationStartHandoff.put(payload)
            val intent = Intent(context, NavigationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HANDOFF_TOKEN, token)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                NavigationStartHandoff.discard(token)
                throw error
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_STOP))
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private lateinit var ownerThread: HandlerThread
    private lateinit var ownerHandler: Handler
    private lateinit var controller: NavigationSessionController
    private lateinit var recoveryStore: NavigationRecoveryStore
    private val lifecycleGuard = ServiceLifecycleGuard()
    private val routeRenderer = RouteRenderer()
    private var locationSource: LocationSource? = null
    private var headingSource: AndroidCompassHeadingSource? = null
    private var transport: AndroidBleDisplayTransport? = null
    private var rideDestinationLabel: String? = null
    private var displayListener: DisplayTransport.Listener? = null
    private lateinit var usageLedger: UsageLedger
    private lateinit var rerouteExecutor: ExecutorService
    private var routingProvider: RoutingProvider? = null
    private var hostedRerouteAllowed = true
    private var rerouteCancellation: RoutingCancellation? = null
    @Volatile private var latestSnapshot = NavigationSessionSnapshot(
        SessionPhase.IDLE, null, null, null, null, TransportState.Idle,
    )
    private val nowPlayingListener = NowPlaying.Listener {
        ownerHandler.post {
            if (lifecycleGuard.active) latestSnapshot.navigation?.let(::submitDisplay)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!lifecycleGuard.active) return
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationSource?.stop()
                val snapshot = controller.onLocationPermissionRevoked()
                publish(snapshot)
                snapshot.navigation?.let(::submitDisplay)
            } else {
                val snapshot = controller.checkForGpsLoss()
                publish(snapshot)
                snapshot.navigation?.let(::submitDisplay)
            }
            ownerHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        recoveryStore = NavigationRecoveryStore(this)
        ownerThread = HandlerThread("MotoNavSessionOwner").apply { start() }
        ownerHandler = Handler(ownerThread.looper)
        NowPlaying.addListener(nowPlayingListener)
        controller = NavigationSessionController(MonotonicClock { SystemClock.elapsedRealtime() })
        usageLedger = UsageLedger(SharedPreferencesUsageLedgerStore(this))
        rerouteExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> ownerHandler.post { stopSession() }
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_HANDOFF_TOKEN)
                if (!promoteImmediately()) {
                    token?.let(NavigationStartHandoff::discard)
                    return START_NOT_STICKY
                }
                val payload = token?.let(NavigationStartHandoff::consume)
                ownerHandler.post { startSession(payload) }
            }
            else -> {
                // No sticky restart: an interrupted Google route must be explicitly requested again.
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Chosen behavior: an explicitly started foreground session continues after task removal.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        NowPlaying.removeListener(nowPlayingListener)
        locationSource?.stop()
        headingSource?.stop()
        // The display link is shared with the app screen; hand it back rather than closing it.
        displayListener?.let { transport?.removeListener(it) }
        rerouteCancellation?.cancel()
        rerouteExecutor.shutdownNow()
        ownerHandler.removeCallbacksAndMessages(null)
        ownerThread.quitSafely()
        listeners.clear()
        super.onDestroy()
    }

    private fun startSession(payload: NavigationStartPayload?) {
        when (lifecycleGuard.start(payload != null)) {
            ServiceStartResult.REJECTED -> {
                stopSession("Navigation start data was unavailable")
                return
            }
            ServiceStartResult.ALREADY_ACTIVE -> return
            ServiceStartResult.STARTED -> Unit
        }
        val start = requireNotNull(payload)
        val generation = lifecycleGuard.generation
        publish(
            controller.start(
                start.route,
                start.destinationLabel,
                System.currentTimeMillis(),
                start.localRequestCount,
                start.originalRequest,
            ),
        )
        hostedRerouteAllowed = start.allowHostedReroute
        routingProvider = if (!start.allowHostedReroute) null else runCatching {
            GoogleRoutesProvider(
                BuildConfig.MAPS_API_KEY,
                AndroidIdentity.from(this),
                UrlConnectionHttpClient(),
                usageLedger,
                GoogleRouteParser(MonotonicRouteRevisionSource(start.route.revision)),
            )
        }.getOrNull()
        recoveryStore.markActive(latestSnapshot)

        rideDestinationLabel = start.destinationLabel
        // Take over the link the app screen already opened: no disconnect, no reconnect delay.
        val displayListener = object : DisplayTransport.Listener {
            override fun onStateChanged(state: TransportState) {
                ownerHandler.post {
                    if (!lifecycleGuard.accepts(generation)) return@post
                    publish(controller.onTransportState(state))
                }
            }

            override fun onScanResults(devices: List<dev.motonav.simulator.transport.DisplayDevice>) = Unit
            override fun onFrameSent(sequence: Int) = Unit
        }
        mainHandler.post {
            if (!lifecycleGuard.accepts(generation)) return@post
            val displayTransport = DisplayLink.acquire(this)
            transport = displayTransport
            this.displayListener = displayListener
            displayTransport.setListener(displayListener)
            start.selectedDisplay?.let(displayTransport::ensureConnected)
        }

        val source = AndroidFusedLocationSource(this)
        locationSource = source
        source.start(object : LocationSource.Listener {
            override fun onSamples(samples: List<GeoLocationSample>) {
                ownerHandler.post {
                    if (!lifecycleGuard.accepts(generation)) return@post
                    controller.onLocations(samples).forEach { update ->
                        publish(update.snapshot)
                        update.snapshot.navigation?.let(::submitDisplay)
                        update.reroute?.let(::startReroute)
                        if (update.snapshot.phase == SessionPhase.ARRIVED) beginArrivalGrace(generation)
                    }
                }
            }

            override fun onLocationDisabled(resolution: (() -> Unit)?) {
                ownerHandler.post {
                    if (!lifecycleGuard.accepts(generation)) return@post
                    val snapshot = controller.onLocationDisabled()
                    publish(snapshot)
                    snapshot.navigation?.let(::submitDisplay)
                }
            }

            override fun onError(message: String) {
                ownerHandler.post {
                    if (lifecycleGuard.accepts(generation)) publish(controller.onLocationError(message))
                }
            }
        })
        AndroidCompassHeadingSource(this, ownerHandler).also { compass ->
            headingSource = compass
            compass.start { headingDegrees ->
                if (!lifecycleGuard.accepts(generation)) return@start
                val snapshot = controller.onDeviceHeading(headingDegrees)
                publish(snapshot, updateForegroundNotification = false)
                snapshot.navigation?.let(::submitDisplay)
            }
        }
        latestSnapshot.navigation?.let(::submitDisplay)
        ownerHandler.post(watchdog)
    }

    private fun beginArrivalGrace(generation: Long) {
        locationSource?.stop()
        ownerHandler.removeCallbacks(watchdog)
        ownerHandler.removeCallbacksAndMessages(ARRIVAL_TOKEN)
        ownerHandler.postAtTime(
            { if (lifecycleGuard.accepts(generation)) stopSession() },
            ARRIVAL_TOKEN,
            SystemClock.uptimeMillis() + ARRIVAL_GRACE_MILLIS,
        )
    }

    private fun stopSession(failure: String? = null) {
        if (lifecycleGuard.active) lifecycleGuard.stop()
        ownerHandler.removeCallbacks(watchdog)
        ownerHandler.removeCallbacksAndMessages(ARRIVAL_TOKEN)
        locationSource?.stop()
        locationSource = null
        headingSource?.stop()
        headingSource = null
        rerouteCancellation?.cancel()
        rerouteCancellation = null
        routingProvider = null
        val oldTransport = transport
        val arrived = latestSnapshot.phase == SessionPhase.ARRIVED
        transport = null
        mainHandler.post {
            // Leave "Arrived!" up after arrival, otherwise a clear "Ride ended"; then hand the link back.
            if (!arrived) oldTransport?.submit(rideEndedFrame())
            displayListener?.let { oldTransport?.removeListener(it) }
        }
        publish(controller.stop(failure))
        recoveryStore.markStopped(failure)
        mainHandler.post {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publish(snapshot: NavigationSessionSnapshot, updateForegroundNotification: Boolean = true) {
        latestSnapshot = snapshot
        if (updateForegroundNotification) updateNotification(snapshot)
        mainHandler.post { listeners.forEach { it.onSessionSnapshot(snapshot) } }
    }

    private fun startReroute(context: RerouteRequestContext) {
        val usage = usageLedger.current()
        when {
            !hostedRerouteAllowed -> {
                publish(controller.deferRerouteBeforeSend(context, "Off your imported route; head back to the line"))
                return
            }
            usage.blocked -> {
                publish(controller.deferRerouteBeforeSend(context, "Daily/monthly routing budget is blocked"))
                return
            }
            !networkAvailable() -> {
                publish(controller.deferRerouteBeforeSend(context, "Network unavailable; continuing evidence is required"))
                return
            }
            routingProvider == null || BuildConfig.MAPS_API_KEY.isBlank() -> {
                publish(controller.deferRerouteBeforeSend(context, "Google routing credential is unavailable"))
                return
            }
        }
        val approvedContext = context.copy(
            budgetDecision = "allowed: day ${usage.dailyAttempts}/${UsageLedger.DAILY_STOP_ATTEMPTS}, " +
                "month ${usage.attempts}/${UsageLedger.STOP_ATTEMPTS}",
        )
        val rerouting = controller.beginReroute(approvedContext) ?: return
        publish(rerouting)
        rerouting.navigation?.let(::submitDisplay)
        val cancellation = RoutingCancellation()
        rerouteCancellation = cancellation
        val provider = requireNotNull(routingProvider)
        rerouteExecutor.execute {
            val result = runCatching { provider.computeRoute(approvedContext.request, cancellation) }
            ownerHandler.post {
                if (rerouteCancellation === cancellation) rerouteCancellation = null
                if (cancellation.isCancelled()) return@post
                controller.updateProviderRequestCount(usageLedger.current().attempts)
                result.onSuccess { replacement ->
                    val completion = controller.completeReroute(approvedContext, replacement)
                    publish(completion.snapshot)
                    if (completion.accepted) completion.snapshot.navigation?.let(::submitDisplay)
                }.onFailure { error ->
                    val routing = error as? RoutingException
                    val failure = routing?.failure
                    val kind = when (failure) {
                        RoutingFailure.TimeoutOrNetwork, RoutingFailure.ProviderUnavailable -> RerouteFailureKind.TRANSIENT
                        is RoutingFailure.RateLimited -> RerouteFailureKind.RATE_LIMITED
                        RoutingFailure.AuthenticationOrConfiguration, RoutingFailure.MissingCredential -> RerouteFailureKind.AUTHENTICATION
                        RoutingFailure.QuotaExhausted -> RerouteFailureKind.BUDGET
                        RoutingFailure.InvalidInput -> RerouteFailureKind.INVALID_REQUEST
                        RoutingFailure.NoRoute -> RerouteFailureKind.NO_ROUTE
                        RoutingFailure.MalformedResponse, null -> RerouteFailureKind.MALFORMED
                        RoutingFailure.Cancelled -> RerouteFailureKind.CANCELLED
                    }
                    val failed = controller.failReroute(
                        approvedContext,
                        kind,
                        failure?.userMessage ?: error.message ?: "Replacement route failed",
                        (failure as? RoutingFailure.RateLimited)?.retryAfterMillis,
                    )
                    publish(failed)
                    failed.navigation?.let(::submitDisplay)
                }
            }
        }
    }

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun submitDisplay(snapshot: NavigationSnapshot) {
        val rendered = routeRenderer.render(snapshot)
        val frame = DisplayFrame(
            sequence = 0,
            state = snapshot.state,
            maneuver = snapshot.maneuver,
            nextTurnMetres = snapshot.metresToManeuver.roundToInt().coerceIn(0, 0xffff),
            remainingDistanceMetres = snapshot.remainingMetres.roundToInt().coerceAtLeast(0).toLong(),
            remainingDurationMinutes = snapshot.remainingDurationMillis?.let {
                ((it + 59_999L) / 60_000L).coerceIn(0L, 0xffffL).toInt()
            },
            mapHeadingDegrees = snapshot.headingDegrees?.roundToInt()?.mod(360) ?: 0,
            points = rendered.points,
            contentSource = snapshot.contentSource,
            primaryText = when (snapshot.state) {
                NavigationState.ARRIVED -> rideDestinationLabel
                else -> null
            },
        )
        mainHandler.post { transport?.submit(frame) }
    }

    private fun rideEndedFrame() = DisplayFrame(
        sequence = 0,
        state = NavigationState.DISCONNECTED,
        maneuver = dev.motonav.simulator.navigation.Maneuver.STRAIGHT,
        nextTurnMetres = 0,
        remainingDistanceMetres = null,
        points = emptyList(),
    )

    @SuppressLint("InlinedApi")
    private fun promoteImmediately(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(
                NavigationNotificationModel(
                    title = "Starting navigation",
                    text = "Preparing GPS and TFT connection",
                    ongoing = true,
                ),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (error: RuntimeException) {
        recoveryStore.markStopped("Foreground service could not start: ${error.javaClass.simpleName}")
        stopSelf()
        false
    }

    private fun updateNotification(snapshot: NavigationSessionSnapshot) {
        if (!snapshot.isActive) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(NavigationNotificationModels.from(snapshot)),
        )
    }

    private fun buildNotification(model: NavigationNotificationModel): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(model.text))
            .setContentIntent(contentIntent)
            .setOngoing(model.ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Active navigation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps GPS navigation and the motorcycle display active while the phone is locked"
                setShowBadge(false)
            },
        )
    }

    private object ARRIVAL_TOKEN
}
