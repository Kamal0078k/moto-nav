package dev.motonav.simulator.routing

import java.net.URI
import java.net.URLDecoder

/**
 * One stop read from a shared Google Maps directions link. A blank start with no point means
 * "use the rider's GPS fix". The Maps app often omits a stop's coordinates but always keeps its
 * address text (and sometimes a place ID); Google Routes can locate those itself in the same request.
 */
data class SharedWaypoint(
    val label: String,
    val point: GeoPoint?,
    val address: String? = null,
    val placeId: String? = null,
) {
    /** How this stop is sent to Google Routes, or null when it cannot be located at all. */
    fun routeStop(): RouteStop? = when {
        point != null -> RouteStop.At(point)
        placeId != null -> RouteStop.Place(placeId)
        !address.isNullOrBlank() -> RouteStop.Address(address)
        else -> null
    }
}

data class SharedDirections(val waypoints: List<SharedWaypoint>) {
    init {
        require(waypoints.size >= 2) { "Directions need a start and a destination" }
    }

    val origin: SharedWaypoint get() = waypoints.first()
    val destination: SharedWaypoint get() = waypoints.last()
    val stops: List<SharedWaypoint> get() = waypoints.subList(1, waypoints.lastIndex)
    val needsCurrentLocation: Boolean get() = origin.routeStop() == null

    /** Stops for one Google Routes request; [currentLocation] fills a blank start. */
    fun toStops(currentLocation: GeoPoint? = null): List<RouteStop> {
        val start = origin.routeStop() ?: currentLocation?.let { RouteStop.At(it) }
            ?: throw GoogleMapsLinkException("The shared link has no start point. Use your current location.")
        return listOf(start) + waypoints.drop(1).map { stop ->
            stop.routeStop() ?: throw GoogleMapsLinkException(
                "\"${stop.label}\" has no location in the link. In Google Maps, pick it from the search results before sharing.",
            )
        }
    }
}

class GoogleMapsLinkException(message: String) : IllegalArgumentException(message)

/**
 * Reads the stops that Google Maps embeds in a directions/place link, so a route can be built
 * without any Places Autocomplete or Place Details request. Pure string parsing: no network.
 */
object GoogleMapsLink {
    const val PINNED_LABEL = "Pinned location"
    private val URL_PATTERN = Regex("""https?://[^\s<>"']+""")
    private val COORDINATES = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val TOKEN = Regex("""^(\d+)([a-z])(.*)$""")
    private val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "g.co")

    /** Pulls the first link out of text shared by the Google Maps app ("Directions to … https://…"). */
    fun extractUrl(sharedText: String): String? =
        URL_PATTERN.find(sharedText)?.value?.trimEnd('.', ',', ')', ']')

    fun isShortLink(url: String): Boolean {
        val uri = safeUri(url) ?: return false
        val host = uri.host?.lowercase() ?: return false
        return host in SHORT_HOSTS && (host != "goo.gl" || uri.path.orEmpty().startsWith("/maps"))
    }

    fun isGoogleMapsUrl(url: String): Boolean {
        val uri = safeUri(url) ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (isShortLink(url)) return true
        val googleHost = host == "maps.google.com" || host.startsWith("maps.google.") ||
            Regex("""^(www\.)?google\.[a-z.]+$""").matches(host)
        return googleHost && (host.startsWith("maps.") || uri.rawPath.orEmpty().startsWith("/maps"))
    }

    fun parse(url: String): SharedDirections {
        val uri = safeUri(url)
            ?: throw GoogleMapsLinkException("That doesn't look like a Google Maps link.")
        if (isShortLink(url)) throw GoogleMapsLinkException("Short links must be expanded first.")
        if (!isGoogleMapsUrl(url)) throw GoogleMapsLinkException("That doesn't look like a Google Maps link.")
        val query = queryParameters(uri.rawQuery)
        val path = uri.rawPath.orEmpty()

        query["api"]?.let { return parseApiLink(query) }
        if (query["saddr"] != null || query["daddr"] != null) return parseLegacyLink(query)
        if (path.contains("/dir/")) return parseDirectionsPath(path)
        parsePlace(path, query)?.let { return it }
        throw GoogleMapsLinkException("No directions found. In Google Maps, open directions and tap Share.")
    }

    private fun parseApiLink(query: Map<String, String>): SharedDirections {
        val destination = query["destination"]
            ?: throw GoogleMapsLinkException("The link has no destination.")
        val stops = query["waypoints"].orEmpty().split('|').filter { it.isNotBlank() }
        val stopIds = query["waypoint_place_ids"].orEmpty().split('|')
        return SharedDirections(
            listOf(waypoint(query["origin"].orEmpty(), query["origin_place_id"])) +
                stops.mapIndexed { index, stop -> waypoint(stop, stopIds.getOrNull(index)) } +
                waypoint(destination, query["destination_place_id"]),
        )
    }

    private fun parseLegacyLink(query: Map<String, String>): SharedDirections {
        val destinations = query["daddr"].orEmpty().split(" to:").map { it.trim() }.filter { it.isNotEmpty() }
        if (destinations.isEmpty()) throw GoogleMapsLinkException("The link has no destination.")
        return SharedDirections(listOf(waypoint(query["saddr"].orEmpty())) + destinations.map(::waypoint))
    }

    private fun parseDirectionsPath(rawPath: String): SharedDirections {
        val afterDir = rawPath.substringAfter("/dir/")
        val parts = afterDir.split('/')
        val segments = parts.takeWhile { !it.startsWith("@") && !it.startsWith("data=") }
            .map(::decode)
            .let { if (it.lastOrNull()?.isEmpty() == true && it.size > 1) it.dropLast(1) else it }
        val data = parts.firstOrNull { it.startsWith("data=") }?.removePrefix("data=")
        val dataStops = data?.let(::dataWaypoints).orEmpty()

        val merged = when {
            dataStops.size == segments.size -> segments.zip(dataStops) { segment, dataPoint ->
                SharedWaypoint(labelFor(segment), coordinates(segment) ?: dataPoint, addressFor(segment))
            }
            segments.size >= 2 -> segments.mapIndexed { index, segment ->
                SharedWaypoint(labelFor(segment), coordinates(segment) ?: dataStops.getOrNull(index), addressFor(segment))
            }
            dataStops.size >= 2 -> dataStops.mapIndexed { index, point ->
                SharedWaypoint(segments.getOrNull(index)?.let(::labelFor) ?: "Stop ${index + 1}", point)
            }
            else -> throw GoogleMapsLinkException("The link needs a start and a destination.")
        }
        // "/dir//Place" means the sharer left the start as their own location.
        return SharedDirections(merged.mapIndexed { index, stop ->
            if (index == 0 && stop.point == null && stop.label.isBlank()) SharedWaypoint("", null) else stop
        })
    }

    private fun parsePlace(rawPath: String, query: Map<String, String>): SharedDirections? {
        val fromQuery = (query["q"] ?: query["ll"] ?: query["query"])?.let(::coordinates)
        val placeName = if (rawPath.contains("/place/")) decode(rawPath.substringAfter("/place/").substringBefore('/')) else null
        val fromData = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""").find(rawPath)?.let {
            point(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
        }
        val fromAt = Regex("""/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""").find(rawPath)?.let {
            point(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
        }
        val destination = fromQuery ?: fromData ?: (if (placeName != null) fromAt else null) ?: return null
        return SharedDirections(
            listOf(SharedWaypoint("", null), SharedWaypoint(placeName?.let(::labelFor) ?: "Dropped pin", destination)),
        )
    }

    private fun waypoint(value: String, placeId: String? = null): SharedWaypoint {
        val decoded = value.trim()
        return SharedWaypoint(labelFor(decoded), coordinates(decoded), addressFor(decoded), placeId?.takeIf { it.isNotBlank() })
    }

    private fun addressFor(segment: String): String? =
        segment.trim().takeIf { it.isNotEmpty() && coordinates(it) == null }

    private fun labelFor(segment: String): String {
        if (coordinates(segment) != null) return PINNED_LABEL
        return segment.substringBefore(',').trim().ifEmpty { segment.trim() }
    }

    private fun coordinates(value: String): GeoPoint? =
        COORDINATES.matchEntire(value)?.let { point(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }

    private fun point(latitude: Double, longitude: Double): GeoPoint? =
        runCatching { GeoPoint(latitude, longitude) }.getOrNull()

    /**
     * Google encodes directions state as "!<field><type><value>" tokens, where "m" opens a message
     * spanning the next N tokens. Each stop is a "1m" child of the directions message, and a stop
     * chosen from search carries "!2m2!1d<lng>!2d<lat>". Stops without coordinates yield null.
     */
    internal fun dataWaypoints(data: String): List<GeoPoint?> {
        val tokens = data.split('!').filter { it.isNotEmpty() }.mapNotNull { raw ->
            TOKEN.matchEntire(raw)?.let { DataToken(it.groupValues[1].toInt(), it.groupValues[2][0], it.groupValues[3]) }
        }
        val root = buildTree(tokens)
        val directions = findDirections(root) ?: return emptyList()
        return directions.children.filter { it.field == 1 && it.type == 'm' }.map { stop ->
            val location = stop.children.firstOrNull { it.field == 2 && it.type == 'm' }
            val longitude = location?.children?.firstOrNull { it.field == 1 && it.type == 'd' }?.value?.toDoubleOrNull()
            val latitude = location?.children?.firstOrNull { it.field == 2 && it.type == 'd' }?.value?.toDoubleOrNull()
            if (latitude != null && longitude != null) point(latitude, longitude) else null
        }
    }

    private data class DataToken(val field: Int, val type: Char, val value: String)

    private class DataNode(val field: Int, val type: Char, val value: String) {
        val children = mutableListOf<DataNode>()
    }

    private fun buildTree(tokens: List<DataToken>): DataNode {
        val root = DataNode(0, 'm', "")
        var index = 0
        fun fill(parent: DataNode, count: Int) {
            var consumed = 0
            while (consumed < count && index < tokens.size) {
                val token = tokens[index++]
                consumed++
                val node = DataNode(token.field, token.type, token.value)
                parent.children += node
                if (token.type == 'm') {
                    val span = token.value.toIntOrNull() ?: 0
                    val before = index
                    fill(node, span)
                    consumed += index - before
                }
            }
        }
        fill(root, Int.MAX_VALUE)
        return root
    }

    private fun findDirections(node: DataNode): DataNode? {
        if (node.children.count { it.field == 1 && it.type == 'm' } >= 2 && node.field == 4) return node
        return node.children.asSequence().filter { it.type == 'm' }.mapNotNull(::findDirections).firstOrNull()
    }

    private fun queryParameters(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
        .split('&').filter { it.isNotEmpty() }
        .associate { pair -> decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', "")) }

    private fun safeUri(url: String): URI? = runCatching {
        URI(url.trim().replace(" ", "%20").replace("|", "%7C").replace("\"", "%22"))
    }.getOrNull()

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
