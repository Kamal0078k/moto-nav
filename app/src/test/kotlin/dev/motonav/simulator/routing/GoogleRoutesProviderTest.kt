package dev.motonav.simulator.routing

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRoutesProviderTest {
    private val request = RouteRequest(GeoPoint(0.0, 0.0), GeoPoint(0.0001, 0.0001))

    @Test fun `serializes exact motorcycle request endpoint headers and narrow field mask`() {
        val provider = provider(HttpResponse(200, RESPONSE))
        val http = provider.createRequest(request)
        val body = http.body.toString(Charsets.UTF_8)
        assertEquals(GoogleRoutesProvider.ENDPOINT, http.url)
        assertEquals(
            """{"origin":{"location":{"latLng":{"latitude":0.0,"longitude":0.0}}},"destination":{"location":{"latLng":{"latitude":1.0E-4,"longitude":1.0E-4}}},"travelMode":"TWO_WHEELER","routingPreference":"TRAFFIC_AWARE","computeAlternativeRoutes":false,"polylineQuality":"HIGH_QUALITY","languageCode":"en-IN","units":"METRIC"}""",
            body,
        )
        assertTrue(body.contains("\"travelMode\":\"TWO_WHEELER\""))
        assertTrue(body.contains("\"routingPreference\":\"TRAFFIC_AWARE\""))
        assertTrue(body.contains("\"computeAlternativeRoutes\":false"))
        assertTrue(body.contains("\"polylineQuality\":\"HIGH_QUALITY\""))
        assertTrue(body.contains("\"languageCode\":\"en-IN\""))
        assertTrue(body.contains("\"units\":\"METRIC\""))
        assertFalse(body.contains("DRIVE"))
        assertFalse(body.contains("BICYCLE"))
        assertEquals(
            "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline," +
                "routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration," +
                "routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.startLocation," +
                "routes.legs.steps.endLocation,routes.legs.steps.travelMode," +
                "routes.legs.steps.navigationInstruction,routes.legs.endLocation",
            http.headers.getValue("X-Goog-FieldMask"),
        )
        assertFalse(http.headers.getValue("X-Goog-FieldMask").contains('*'))
        assertEquals("dev.motonav.simulator", http.headers["X-Android-Package"])
        assertEquals("AA:BB", http.headers["X-Android-Cert"])
    }

    @Test fun `initial and replacement requests use the identical motorcycle policy`() {
        val captured = mutableListOf<HttpRequest>()
        val provider = provider(HttpClient { http, _ ->
            captured += http
            HttpResponse(200, RESPONSE)
        })
        val replacement = RouteRequest(GeoPoint(0.00005, 0.0), request.destination)

        provider.computeRoute(request, CancellationSignal.NONE)
        provider.computeRoute(replacement, CancellationSignal.NONE)

        assertEquals(2, captured.size)
        captured.forEach { http ->
            val body = http.body.toString(Charsets.UTF_8)
            assertTrue(body.contains("\"travelMode\":\"TWO_WHEELER\""))
            assertTrue(body.contains("\"routingPreference\":\"TRAFFIC_AWARE\""))
            assertTrue(body.contains("\"computeAlternativeRoutes\":false"))
            assertFalse(body.contains("DRIVE"))
            assertFalse(body.contains("BICYCLE"))
        }
        assertTrue(captured[0].body.toString(Charsets.UTF_8).contains("\"latitude\":0.0"))
        assertTrue(captured[1].body.toString(Charsets.UTF_8).contains("\"latitude\":5.0E-5"))
    }

    @Test fun `maps status network cancellation missing key and malformed JSON to typed failures`() {
        val expectations = mapOf(
            400 to RoutingFailure.InvalidInput,
            401 to RoutingFailure.AuthenticationOrConfiguration,
            403 to RoutingFailure.AuthenticationOrConfiguration,
            404 to RoutingFailure.NoRoute,
            500 to RoutingFailure.ProviderUnavailable,
        )
        expectations.forEach { (status, expected) ->
            assertEquals(expected, failure(provider(HttpResponse(status, "{}"))))
        }
        assertTrue(failure(provider(HttpResponse(429, "{}"))) is RoutingFailure.RateLimited)
        assertEquals(RoutingFailure.MalformedResponse, failure(provider(HttpResponse(200, "not json"))))
        assertEquals(RoutingFailure.TimeoutOrNetwork, failure(provider(HttpClient { _, _ -> throw IOException("offline") })))
        assertEquals(RoutingFailure.TimeoutOrNetwork, failure(provider(HttpClient { _, _ -> throw SocketTimeoutException() })))
        assertEquals(RoutingFailure.Cancelled, failure(provider(HttpResponse(200, RESPONSE)), CancellationSignal { true }))
        assertEquals(
            RoutingFailure.MissingCredential,
            failure(provider(HttpResponse(200, RESPONSE), key = "")),
        )
    }

    @Test fun `six thousandth monthly attempt is persisted and refused before HTTP`() {
        var requests = 0
        val persisted = arrayOf<UsageLedgerState?>(UsageLedgerState(
            billingMonth = "2026-09",
            attempts = 5_999,
            lastObservedEpochMillis = 0,
        ))
        val store = object : UsageLedgerStore {
            override fun read(): UsageLedgerState? = persisted[0]
            override fun write(state: UsageLedgerState) { persisted[0] = state }
        }
        val provider = GoogleRoutesProvider(
            apiKey = "key",
            identity = AndroidApplicationIdentity("dev.motonav.simulator", "AA:BB"),
            httpClient = HttpClient { _, _ -> requests++; HttpResponse(200, RESPONSE) },
            usageLedger = UsageLedger(store, FIXED_CLOCK),
        )

        assertEquals(RoutingFailure.QuotaExhausted, failure(provider))
        assertEquals(0, requests)
        assertEquals(6_000, persisted[0]?.attempts)
    }

    @Test fun `two hundredth daily attempt is persisted and refused before HTTP`() {
        var requests = 0
        val persisted = arrayOf<UsageLedgerState?>(UsageLedgerState(
            billingMonth = "2026-09",
            attempts = 500,
            lastObservedEpochMillis = FIXED_CLOCK.millis(),
            billingDay = "2026-09-22",
            dailyAttempts = 199,
        ))
        val store = object : UsageLedgerStore {
            override fun read(): UsageLedgerState? = persisted[0]
            override fun write(state: UsageLedgerState) { persisted[0] = state }
        }
        val provider = GoogleRoutesProvider(
            apiKey = "key",
            identity = AndroidApplicationIdentity("dev.motonav.simulator", "AA:BB"),
            httpClient = HttpClient { _, _ -> requests++; HttpResponse(200, RESPONSE) },
            usageLedger = UsageLedger(store, FIXED_CLOCK),
        )

        assertEquals(RoutingFailure.QuotaExhausted, failure(provider))
        assertEquals(0, requests)
        assertEquals(200, persisted[0]?.dailyAttempts)
        assertEquals(501, persisted[0]?.attempts)
    }

    @Test fun `retry-after seconds are retained on rate limit`() {
        val failure = failure(
            provider(HttpResponse(429, "{}", mapOf("Retry-After" to listOf("45")))),
        )
        assertEquals(45_000L, (failure as RoutingFailure.RateLimited).retryAfterMillis)
    }

    private fun failure(
        provider: GoogleRoutesProvider,
        cancellation: CancellationSignal = CancellationSignal.NONE,
    ): RoutingFailure = assertThrows(RoutingException::class.java) {
        provider.computeRoute(request, cancellation)
    }.failure

    private fun provider(response: HttpResponse, key: String = "key") = provider(HttpClient { _, _ -> response }, key)

    private fun provider(client: HttpClient, key: String = "key") = GoogleRoutesProvider(
        apiKey = key,
        identity = AndroidApplicationIdentity("dev.motonav.simulator", "AA:BB"),
        httpClient = client,
        usageLedger = UsageLedger(MemoryStore(), FIXED_CLOCK),
        requestIds = RequestIdSource { "request-id" },
    )

    private class MemoryStore : UsageLedgerStore {
        private var state: UsageLedgerState? = null
        override fun read() = state
        override fun write(state: UsageLedgerState) { this.state = state }
    }

    companion object {
        private val FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), UsageLedger.INDIA_ZONE)
        private const val RESPONSE = """{"routes":[{"distanceMeters":16,"duration":"2s","polyline":{"encodedPolyline":"??SS"},"legs":[{"steps":[{"distanceMeters":16,"staticDuration":"2s","polyline":{"encodedPolyline":"??SS"},"startLocation":{"latLng":{"latitude":0.0,"longitude":0.0}},"endLocation":{"latLng":{"latitude":0.0001,"longitude":0.0001}},"travelMode":"TWO_WHEELER","navigationInstruction":{"maneuver":"ARRIVE","instructions":"Arrive"}}]}]}]}"""
    }
}
