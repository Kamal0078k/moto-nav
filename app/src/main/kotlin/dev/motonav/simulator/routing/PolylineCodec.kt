package dev.motonav.simulator.routing

object PolylineCodec {
    const val DEFAULT_MAX_POINTS = 20_000

    fun encode(points: List<GeoPoint>): String {
        val result = StringBuilder()
        var previousLatitude = 0L
        var previousLongitude = 0L
        fun component(value: Long) {
            var shifted = if (value < 0) (value shl 1).inv() else value shl 1
            while (shifted >= 0x20) {
                result.append(((0x20 or (shifted and 0x1f).toInt()) + 63).toChar())
                shifted = shifted ushr 5
            }
            result.append((shifted.toInt() + 63).toChar())
        }
        points.forEach { point ->
            val latitude = Math.round(point.latitude * 100_000.0)
            val longitude = Math.round(point.longitude * 100_000.0)
            component(latitude - previousLatitude)
            component(longitude - previousLongitude)
            previousLatitude = latitude
            previousLongitude = longitude
        }
        return result.toString()
    }

    fun decode(encoded: String, maxPoints: Int = DEFAULT_MAX_POINTS): List<GeoPoint> {
        require(maxPoints > 0)
        if (encoded.isEmpty()) return emptyList()

        var index = 0
        var latitude = 0L
        var longitude = 0L
        val result = ArrayList<GeoPoint>()

        fun component(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(index < encoded.length) { "Truncated encoded polyline" }
                val character = encoded[index++].code - 63
                require(character in 0..63) { "Invalid encoded polyline character" }
                require(shift <= 60) { "Encoded polyline component overflow" }
                val payload = (character and 0x1f).toLong()
                require(payload <= (Long.MAX_VALUE ushr shift)) { "Encoded polyline component overflow" }
                value = value or (payload shl shift)
                if (character < 0x20) break
                shift += 5
            }
            return if (value and 1L != 0L) (value ushr 1).inv() else value ushr 1
        }

        while (index < encoded.length) {
            require(result.size < maxPoints) { "Encoded polyline exceeds point limit" }
            latitude = Math.addExact(latitude, component())
            longitude = Math.addExact(longitude, component())
            val lat = latitude / 100_000.0
            val lng = longitude / 100_000.0
            result += GeoPoint(lat, lng)
        }
        return result
    }
}
