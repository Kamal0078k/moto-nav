package dev.motonav.simulator.routing

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

enum class TrackFormat(val label: String) { GPX("GPX"), KML("KML"), KMZ("KMZ"), GEOJSON("GeoJSON") }

data class ImportedTrack(val name: String, val points: List<GeoPoint>, val format: TrackFormat)

class RouteImportException(message: String) : IllegalArgumentException(message)

/**
 * Reads a route line from files riders already have: GPX tracks/routes, KML or KMZ exported
 * from Google My Maps / Google Earth, and GeoJSON. Everything is parsed on-device.
 */
object RouteFileImporter {
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val JOIN_GAP_METRES = 250.0

    fun import(bytes: ByteArray, fileName: String?): ImportedTrack {
        if (bytes.size > MAX_BYTES) throw RouteImportException("That file is larger than 20 MB.")
        val fallbackName = fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported route"
        val format = detect(bytes, fileName)
        val (name, points) = when (format) {
            TrackFormat.GPX -> parseGpx(xml(bytes))
            TrackFormat.KML -> parseKml(xml(bytes))
            TrackFormat.KMZ -> parseKml(xml(unzipKml(bytes)))
            TrackFormat.GEOJSON -> parseGeoJson(bytes.toString(Charsets.UTF_8))
        }
        val cleaned = points.fold(mutableListOf<GeoPoint>()) { list, point ->
            if (list.isEmpty() || distanceMetres(list.last(), point) > 0.5) list += point
            list
        }
        if (cleaned.size < 2) throw RouteImportException("No route line was found in that file.")
        return ImportedTrack(name?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName, cleaned, format)
    }

    private fun detect(bytes: ByteArray, fileName: String?): TrackFormat {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        when (extension) {
            "gpx" -> return TrackFormat.GPX
            "kml" -> return TrackFormat.KML
            "kmz" -> return TrackFormat.KMZ
            "geojson", "json" -> return TrackFormat.GEOJSON
        }
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return TrackFormat.KMZ
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
        return when {
            head.contains("<gpx", ignoreCase = true) -> TrackFormat.GPX
            head.contains("<kml", ignoreCase = true) -> TrackFormat.KML
            head.trimStart().startsWith("{") -> TrackFormat.GEOJSON
            else -> throw RouteImportException("Unsupported file. Use GPX, KML, KMZ or GeoJSON.")
        }
    }

    private fun xml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            isExpandEntityReferences = false
        }
        return runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) }
            .getOrElse { throw RouteImportException("The file could not be read as XML.") }
    }

    private fun unzipKml(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".kml", ignoreCase = true)) {
                    return zip.readBytes().also { if (it.size > MAX_BYTES) throw RouteImportException("KML is too large.") }
                }
            }
        }
        throw RouteImportException("The KMZ file does not contain a KML document.")
    }

    private fun Document.elements(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.child(localName: String): Element? {
        val nodes = getElementsByTagNameNS("*", localName)
        return if (nodes.length > 0) nodes.item(0) as Element else null
    }

    private fun parseGpx(document: Document): Pair<String?, List<GeoPoint>> {
        fun points(tag: String) = document.elements(tag).mapNotNull { element ->
            val latitude = element.getAttribute("lat").toDoubleOrNull()
            val longitude = element.getAttribute("lon").toDoubleOrNull()
            if (latitude != null && longitude != null) runCatching { GeoPoint(latitude, longitude) }.getOrNull() else null
        }
        val track = points("trkpt").ifEmpty { points("rtept") }.ifEmpty { points("wpt") }
        val name = (document.elements("trk") + document.elements("rte") + document.elements("metadata"))
            .firstNotNullOfOrNull { it.child("name")?.textContent }
        return name to track
    }

    private fun parseKml(document: Document): Pair<String?, List<GeoPoint>> {
        val lines = document.elements("LineString").mapNotNull { line ->
            line.child("coordinates")?.textContent?.let(::kmlCoordinates)?.takeIf { it.size >= 2 }
        } + document.elements("Track").mapNotNull { track ->
            val coordinates = track.getElementsByTagNameNS("*", "coord")
            (0 until coordinates.length).mapNotNull { index ->
                val parts = coordinates.item(index).textContent.trim().split(Regex("\\s+"))
                pointOrNull(parts.getOrNull(1), parts.getOrNull(0))
            }.takeIf { it.size >= 2 }
        }
        val documentName = document.elements("Document").firstOrNull()?.let { doc ->
            (0 until doc.childNodes.length).map { doc.childNodes.item(it) }
                .firstOrNull { it is Element && it.localName == "name" }?.textContent
        }
        return documentName to joinLines(lines)
    }

    private fun kmlCoordinates(text: String): List<GeoPoint> = text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
        val parts = tuple.split(',')
        pointOrNull(parts.getOrNull(1), parts.getOrNull(0))
    }

    private fun parseGeoJson(text: String): Pair<String?, List<GeoPoint>> {
        val root = runCatching { StrictJsonParser(text).parse() }
            .getOrElse { throw RouteImportException("The file is not valid GeoJSON.") }
        val lines = mutableListOf<List<GeoPoint>>()
        var name: String? = null
        fun position(value: JsonValue): GeoPoint? {
            val numbers = (value as? JsonValue.Array)?.values?.mapNotNull { (it as? JsonValue.NumberValue)?.value }
            return if (numbers != null && numbers.size >= 2) pointOrNull(numbers[1], numbers[0]) else null
        }
        fun line(value: JsonValue?): List<GeoPoint> = (value as? JsonValue.Array)?.values?.mapNotNull(::position).orEmpty()
        fun visit(value: JsonValue) {
            val obj = value as? JsonValue.Object ?: return
            val type = (obj.values["type"] as? JsonValue.StringValue)?.value
            when (type) {
                "FeatureCollection" -> (obj.values["features"] as? JsonValue.Array)?.values?.forEach(::visit)
                "Feature" -> {
                    val properties = obj.values["properties"] as? JsonValue.Object
                    if (name == null) name = (properties?.values?.get("name") as? JsonValue.StringValue)?.value
                    obj.values["geometry"]?.let(::visit)
                }
                "GeometryCollection" -> (obj.values["geometries"] as? JsonValue.Array)?.values?.forEach(::visit)
                "LineString" -> lines += line(obj.values["coordinates"])
                "MultiLineString" -> (obj.values["coordinates"] as? JsonValue.Array)?.values?.forEach { lines += line(it) }
            }
        }
        visit(root)
        return name to joinLines(lines.filter { it.size >= 2 })
    }

    /** Joins consecutive legs (e.g. a My Maps route with stops); otherwise keeps the longest line. */
    private fun joinLines(lines: List<List<GeoPoint>>): List<GeoPoint> {
        if (lines.isEmpty()) return emptyList()
        val connected = lines.zipWithNext().all { (a, b) -> distanceMetres(a.last(), b.first()) <= JOIN_GAP_METRES }
        return if (connected) lines.flatten() else lines.maxBy { it.pathLength() }
    }

    private fun List<GeoPoint>.pathLength() = zipWithNext().sumOf { (a, b) -> distanceMetres(a, b) }

    private fun pointOrNull(latitude: String?, longitude: String?): GeoPoint? =
        pointOrNull(latitude?.toDoubleOrNull(), longitude?.toDoubleOrNull())

    private fun pointOrNull(latitude: Double?, longitude: Double?): GeoPoint? =
        if (latitude == null || longitude == null) null else runCatching { GeoPoint(latitude, longitude) }.getOrNull()
}
