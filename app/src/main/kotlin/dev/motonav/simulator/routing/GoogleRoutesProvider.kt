package dev.motonav.simulator.routing

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()
}

fun interface HttpClient {
    @Throws(IOException::class)
    fun execute(request: HttpRequest, cancellation: CancellationSignal): HttpResponse
}

data class AndroidApplicationIdentity(val packageName: String, val certificateSha1: String) {
    init {
        require(packageName.isNotBlank() && certificateSha1.isNotBlank())
    }
}

fun interface RequestIdSource {
    fun create(): String
}

fun interface RoutingDiagnostics {
    /** Records only an opaque local ID; callers must not attach coordinates or credentials. */
    fun requestStarted(requestId: String)

    companion object {
        val NONE = RoutingDiagnostics { }
    }
}

class GoogleRoutesProvider(
    private val apiKey: String,
    private val identity: AndroidApplicationIdentity,
    private val httpClient: HttpClient,
    private val usageLedger: UsageLedger,
    private val parser: GoogleRouteParser = GoogleRouteParser(),
    private val requestIds: RequestIdSource = RequestIdSource { UUID.randomUUID().toString() },
    private val diagnostics: RoutingDiagnostics = RoutingDiagnostics.NONE,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : RoutingProvider {
    override fun computeRoute(request: RouteRequest, cancellation: CancellationSignal): RoadRoute =
        computeRoute(request.stops(), cancellation)

    /** Stops may be addresses or place IDs: Google locates them inside this one Routes request. */
    fun computeRoute(stops: List<RouteStop>, cancellation: CancellationSignal): RoadRoute {
        if (apiKey.isBlank()) throw RoutingException(RoutingFailure.MissingCredential)
        if (cancellation.isCancelled()) throw RoutingException(RoutingFailure.Cancelled)
        val usage = usageLedger.recordAttempt()
        if (usage.blocked) throw RoutingException(RoutingFailure.QuotaExhausted)
        diagnostics.requestStarted(requestIds.create())
        val httpRequest = createRequest(stops)
        val response = try {
            httpClient.execute(httpRequest, cancellation)
        } catch (failure: SocketTimeoutException) {
            throw RoutingException(RoutingFailure.TimeoutOrNetwork, failure)
        } catch (failure: IOException) {
            if (cancellation.isCancelled()) throw RoutingException(RoutingFailure.Cancelled, failure)
            throw RoutingException(RoutingFailure.TimeoutOrNetwork, failure)
        }
        if (cancellation.isCancelled()) throw RoutingException(RoutingFailure.Cancelled)
        when (response.statusCode) {
            in 200..299 -> Unit
            400 -> throw RoutingException(RoutingFailure.InvalidInput)
            401, 403 -> throw RoutingException(RoutingFailure.AuthenticationOrConfiguration)
            404 -> throw RoutingException(RoutingFailure.NoRoute)
            429 -> throw RoutingException(RoutingFailure.RateLimited(parseRetryAfter(response.header("Retry-After"))))
            in 500..599 -> throw RoutingException(RoutingFailure.ProviderUnavailable)
            else -> throw RoutingException(RoutingFailure.MalformedResponse)
        }
        return parser.parse(response.body)
    }

    fun createRequest(request: RouteRequest): HttpRequest = createRequest(request.stops())

    fun createRequest(stops: List<RouteStop>): HttpRequest = HttpRequest(
        url = ENDPOINT,
        headers = linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "X-Goog-Api-Key" to apiKey,
            "X-Goog-FieldMask" to FIELD_MASK,
            "X-Android-Package" to identity.packageName,
            "X-Android-Cert" to identity.certificateSha1,
        ),
        body = requestBody(stops).toByteArray(Charsets.UTF_8),
    )

    private fun RouteRequest.stops(): List<RouteStop> =
        listOf(RouteStop.At(origin)) + intermediates.map { RouteStop.At(it) } + RouteStop.At(destination)

    private fun waypoint(stop: RouteStop): String = when (stop) {
        is RouteStop.At ->
            """{"location":{"latLng":{"latitude":${decimal(stop.point.latitude)},"longitude":${decimal(stop.point.longitude)}}}}"""
        is RouteStop.Address -> """{"address":${jsonString(stop.text)}}"""
        is RouteStop.Place -> """{"placeId":${jsonString(stop.placeId)}}"""
    }

    private fun requestBody(stops: List<RouteStop>): String = """
        {"origin":${waypoint(stops.first())},"destination":${waypoint(stops.last())},${intermediates(stops)}"travelMode":"TWO_WHEELER","routingPreference":"TRAFFIC_AWARE","computeAlternativeRoutes":false,"polylineQuality":"HIGH_QUALITY","languageCode":"en-IN","units":"METRIC"}
    """.trimIndent()

    private fun intermediates(stops: List<RouteStop>): String {
        val via = stops.subList(1, stops.lastIndex)
        return if (via.isEmpty()) "" else "\"intermediates\":[${via.joinToString(",", transform = ::waypoint)}],"
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun decimal(value: Double): String = java.lang.Double.toString(value)

    private fun parseRetryAfter(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        trimmed.toLongOrNull()?.let { seconds -> return seconds.coerceAtLeast(0L) * 1_000L }
        return runCatching {
            (ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                wallClockMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    companion object {
        const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
        const val FIELD_MASK = "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline," +
            "routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration," +
            "routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.startLocation," +
            "routes.legs.steps.endLocation,routes.legs.steps.travelMode," +
            "routes.legs.steps.navigationInstruction,routes.legs.endLocation"
    }
}

class UrlConnectionHttpClient(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
) : HttpClient {
    override fun execute(request: HttpRequest, cancellation: CancellationSignal): HttpResponse {
        if (cancellation.isCancelled()) throw IOException("Cancelled")
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            request.headers.forEach(::setRequestProperty)
        }
        val cancellationRegistration = cancellation.invokeOnCancellation(connection::disconnect)
        try {
            if (cancellation.isCancelled()) throw IOException("Cancelled")
            connection.outputStream.use { it.write(request.body) }
            if (cancellation.isCancelled()) throw IOException("Cancelled")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { requireNotNull(it.key) }
            return HttpResponse(status, body, headers)
        } finally {
            cancellationRegistration.unregister()
            connection.disconnect()
        }
    }
}
