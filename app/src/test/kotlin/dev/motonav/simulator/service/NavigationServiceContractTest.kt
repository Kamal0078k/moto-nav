package dev.motonav.simulator.service

import dev.motonav.simulator.location.GeoLocationSample
import dev.motonav.simulator.location.MonotonicClock
import dev.motonav.simulator.location.ProjectionAndMatchingTest
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.routing.GeoPoint
import dev.motonav.simulator.transport.DisplayDevice
import dev.motonav.simulator.transport.TransportState
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationServiceContractTest {
    private class Clock(var now: Long = 0L) : MonotonicClock { override fun elapsedRealtimeMillis() = now }

    @After fun clearHandoffs() = NavigationStartHandoff.clear()

    @Test fun `manifest declares typed non-exported service and no background location`() {
        val manifest = File(System.getProperty("repositoryRoot"), "mobile-app/app/src/main/AndroidManifest.xml").readText()
        listOf(
            "android.permission.FOREGROUND_SERVICE\"",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { assertTrue(manifest.contains(it)) }
        assertTrue(manifest.contains("android:name=\".service.NavigationService\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"location|connectedDevice\""))
        assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"))
        assertFalse(manifest.contains("BOOT_COMPLETED"))
    }

    @Test fun `start policy rejects each essential prerequisite but not notification denial`() {
        val ready = ServicePrerequisites(true, true, true, true, true)
        assertNull(ServiceStartPolicy.failure(ready))
        assertEquals("Load a route first", ServiceStartPolicy.failure(ready.copy(hasRoute = false)))
        assertEquals("Accept the motorcycle beta warning first", ServiceStartPolicy.failure(ready.copy(betaWarningAccepted = false)))
        assertEquals("Precise location permission is required", ServiceStartPolicy.failure(ready.copy(preciseLocation = false)))
        assertEquals("Phone location services are disabled", ServiceStartPolicy.failure(ready.copy(locationServicesEnabled = false)))
        assertEquals("Bluetooth permission is required for the display service", ServiceStartPolicy.failure(ready.copy(bluetoothPermission = false)))
        // Notification permission is intentionally absent: denial cannot block the foreground service.
        // Static fields (e.g. the Compose compiler's ${'$'}stable marker) are not prerequisites.
        assertEquals(5, ServicePrerequisites::class.java.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) })
    }

    @Test fun `service start is idempotent and stop invalidates late callbacks`() {
        val guard = ServiceLifecycleGuard()
        assertEquals(ServiceStartResult.REJECTED, guard.start(hasPayload = false))
        assertEquals(ServiceStartResult.STARTED, guard.start(hasPayload = true))
        val activeGeneration = guard.generation
        assertEquals(ServiceStartResult.ALREADY_ACTIVE, guard.start(hasPayload = true))
        assertTrue(guard.accepts(activeGeneration))
        guard.stop()
        assertFalse(guard.accepts(activeGeneration))
        assertFalse(guard.active)
    }

    @Test fun `activity recreation cannot create a second live location owner`() {
        val root = File(requireNotNull(System.getProperty("repositoryRoot")))
        val activity = File(root, "mobile-app/app/src/main/kotlin/dev/motonav/simulator/MainActivity.kt").readText()
        val service = File(root, "mobile-app/app/src/main/kotlin/dev/motonav/simulator/service/NavigationService.kt").readText()
        assertFalse(activity.contains("AndroidFusedLocationSource"))
        assertEquals(1, Regex("AndroidFusedLocationSource\\(this\\)").findAll(service).count())
        // One shared display link: the service takes over the screen's connection instead of opening its own.
        assertEquals(1, Regex("DisplayLink\\.acquire\\(this\\)").findAll(service).count())
        assertFalse(service.contains("AndroidBleDisplayTransport("))
    }

    @Test fun `handoff is in memory and can be consumed only once`() {
        val route = route()
        val payload = NavigationStartPayload(
            route,
            dev.motonav.simulator.routing.RouteRequest(route.geometry.first(), route.geometry.last()),
            "destination",
            DisplayDevice("00:11", "OLED"),
        )
        val token = NavigationStartHandoff.put(payload)
        assertEquals(payload, NavigationStartHandoff.consume(token))
        assertNull(NavigationStartHandoff.consume(token))
    }

    @Test fun `controller owns one session across repeated bind-like starts and fully stops`() {
        val clock = Clock()
        val controller = NavigationSessionController(clock)
        val first = controller.start(route(), "destination", 123L)
        val repeated = controller.start(route().copy(revision = 2), "other", 456L)
        assertTrue(first.isActive)
        assertEquals(first.routeRevision, repeated.routeRevision)
        assertEquals(first.startedAtWallClockMillis, repeated.startedAtWallClockMillis)

        val stopped = controller.stop()
        assertFalse(stopped.isActive)
        assertEquals(SessionPhase.IDLE, stopped.phase)
        assertNull(stopped.routeRevision)
    }

    @Test fun `GPS and Bluetooth failures remain independent and make no provider request`() {
        val clock = Clock()
        val controller = NavigationSessionController(clock)
        controller.start(route(), "destination", 123L)
        controller.onLocations(listOf(sample(0.0, 0L)))
        clock.now = 20_001L
        val gpsLost = controller.checkForGpsLoss()
        assertEquals(NavigationState.GPS_DEGRADED, gpsLost.navigation?.state)
        assertEquals(0, gpsLost.providerRequestCount)

        val bluetoothLost = controller.onTransportState(TransportState.BluetoothDisabled)
        assertEquals(TransportState.BluetoothDisabled, bluetoothLost.transportState)
        assertEquals(NavigationState.GPS_DEGRADED, bluetoothLost.navigation?.state)
        assertEquals(0, bluetoothLost.providerRequestCount)
    }

    @Test fun `notification exposes state content intent and immutable stop action contract`() {
        val controller = NavigationSessionController(Clock())
        controller.start(route(), "Workshop", 123L)
        val snapshot = controller.onTransportState(
            TransportState.Connected(DisplayDevice("00:11", "OLED"), 185),
        )
        val model = NavigationNotificationModels.from(snapshot)
        assertTrue(model.title.contains("Workshop"))
        assertTrue(model.text.contains("TFT connected"))
        assertTrue(model.hasContentIntent)
        assertTrue(model.hasStopAction)
        assertTrue(model.ongoing)
        val source = File(
            System.getProperty("repositoryRoot"),
            "mobile-app/app/src/main/kotlin/dev/motonav/simulator/service/NavigationService.kt",
        ).readText()
        assertTrue(source.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(source.contains("setAction(ACTION_STOP)"))
    }

    @Test fun `recovery metadata cannot persist route geometry and requires explicit recovery`() {
        val fields = RecoveryMetadata::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("geometry", ignoreCase = true) || it.contains("route", ignoreCase = true) && it != "routeRevision" })
        val metadata = RecoveryMetadata(true, "destination", 123L, 1L, 0, null)
        assertEquals("Previous navigation was interrupted", RecoveryPolicy.message(metadata, serviceHasSession = false))
        assertNull(RecoveryPolicy.message(metadata, serviceHasSession = true))
    }

    private fun route() = ProjectionAndMatchingTest.route(
        ProjectionAndMatchingTest.points(ORIGIN, listOf(0.0 to 0.0, 0.0 to 100.0, 0.0 to 200.0)),
        listOf(0, 1, 2),
    )

    private fun sample(north: Double, elapsed: Long): GeoLocationSample =
        ProjectionAndMatchingTest.sample(ORIGIN, 0.0, north, elapsed, bearing = 0.0)

    companion object { private val ORIGIN = GeoPoint(12.0, 77.0) }
}
