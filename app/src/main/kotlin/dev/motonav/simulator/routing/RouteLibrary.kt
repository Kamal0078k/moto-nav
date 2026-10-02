package dev.motonav.simulator.routing

import java.io.File
import java.util.UUID

/**
 * A route the rider imported once and can reuse forever without Places lookups.
 * Link routes keep their stops (one Routes request builds the road route); track routes keep the
 * whole line and navigate with zero hosted requests.
 */
sealed class SavedRoute {
    abstract val id: String
    abstract val name: String
    abstract val createdEpochMillis: Long

    data class Link(
        override val id: String,
        override val name: String,
        override val createdEpochMillis: Long,
        val waypoints: List<SharedWaypoint>,
    ) : SavedRoute()

    data class Track(
        override val id: String,
        override val name: String,
        override val createdEpochMillis: Long,
        val format: TrackFormat,
        val points: List<GeoPoint>,
    ) : SavedRoute() {
        val lengthMetres: Double by lazy { points.zipWithNext().sumOf { (a, b) -> distanceMetres(a, b) } }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

interface RouteLibraryStore {
    fun read(): String?
    fun write(contents: String)
}

class FileRouteLibraryStore(private val file: File) : RouteLibraryStore {
    override fun read(): String? = file.takeIf { it.exists() }?.readText(Charsets.UTF_8)
    override fun write(contents: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(contents, Charsets.UTF_8)
        check(temporary.renameTo(file)) { "Could not save routes" }
    }
}

class RouteLibrary(private val store: RouteLibraryStore) {
    private val routes = load().toMutableList()

    fun all(): List<SavedRoute> = routes.sortedByDescending { it.createdEpochMillis }

    fun find(id: String): SavedRoute? = routes.firstOrNull { it.id == id }

    fun save(route: SavedRoute) {
        routes.removeAll { it.id == route.id }
        routes += route
        persist()
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name is required" }
        val index = routes.indexOfFirst { it.id == id }
        require(index >= 0) { "Route not found" }
        routes[index] = when (val route = routes[index]) {
            is SavedRoute.Link -> route.copy(name = trimmed)
            is SavedRoute.Track -> route.copy(name = trimmed)
        }
        persist()
    }

    fun delete(id: String) {
        if (routes.removeAll { it.id == id }) persist()
    }

    private fun persist() = store.write(encode(routes))

    private fun load(): List<SavedRoute> {
        val text = store.read() ?: return emptyList()
        return runCatching { decode(text) }.getOrDefault(emptyList())
    }

    companion object {
        internal fun encode(routes: List<SavedRoute>): String = buildString {
            append("{\"version\":1,\"routes\":[")
            routes.forEachIndexed { index, route ->
                if (index > 0) append(',')
                append("{\"id\":").append(quote(route.id))
                append(",\"name\":").append(quote(route.name))
                append(",\"created\":").append(route.createdEpochMillis)
                when (route) {
                    is SavedRoute.Link -> {
                        append(",\"kind\":\"link\",\"waypoints\":[")
                        route.waypoints.forEachIndexed { i, stop ->
                            if (i > 0) append(',')
                            append("{\"label\":").append(quote(stop.label))
                            stop.point?.let { append(",\"lat\":").append(it.latitude).append(",\"lng\":").append(it.longitude) }
                            stop.address?.let { append(",\"address\":").append(quote(it)) }
                            stop.placeId?.let { append(",\"placeId\":").append(quote(it)) }
                            append('}')
                        }
                        append(']')
                    }
                    is SavedRoute.Track -> {
                        append(",\"kind\":\"track\",\"format\":").append(quote(route.format.name))
                        append(",\"polyline\":").append(quote(PolylineCodec.encode(route.points)))
                    }
                }
                append('}')
            }
            append("]}")
        }

        internal fun decode(text: String): List<SavedRoute> {
            val root = StrictJsonParser(text).parse().objectOrThrow()
            return root["routes"].arrayOrThrow().mapNotNull { raw ->
                runCatching {
                    val route = raw.objectOrThrow()
                    val id = route["id"].stringOrThrow()
                    val name = route["name"].stringOrThrow()
                    val created = route["created"].numberOrThrow().toLong()
                    when (route["kind"].stringOrThrow()) {
                        "link" -> SavedRoute.Link(id, name, created, route["waypoints"].arrayOrThrow().map { value ->
                            val stop = value.objectOrThrow()
                            val lat = stop.optional("lat")?.numberOrThrow()
                            val lng = stop.optional("lng")?.numberOrThrow()
                            SharedWaypoint(
                                stop["label"].stringOrThrow(),
                                if (lat != null && lng != null) GeoPoint(lat, lng) else null,
                                stop.optional("address")?.stringOrThrow(),
                                stop.optional("placeId")?.stringOrThrow(),
                            )
                        })
                        "track" -> SavedRoute.Track(
                            id, name, created,
                            TrackFormat.valueOf(route["format"].stringOrThrow()),
                            PolylineCodec.decode(route["polyline"].stringOrThrow()),
                        )
                        else -> null
                    }
                }.getOrNull()
            }
        }

        private fun quote(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when {
                    character == '"' -> append("\\\"")
                    character == '\\' -> append("\\\\")
                    character == '\n' -> append("\\n")
                    character == '\r' -> append("\\r")
                    character == '\t' -> append("\\t")
                    character.code < 0x20 -> append("\\u%04x".format(character.code))
                    else -> append(character)
                }
            }
            append('"')
        }
    }
}
