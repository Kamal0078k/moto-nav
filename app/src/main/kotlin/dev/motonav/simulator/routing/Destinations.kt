package dev.motonav.simulator.routing

data class SavedDestination(val label: String, val point: GeoPoint) {
    init {
        require(label.trim().isNotEmpty()) { "Destination label is required" }
        require(label == label.trim()) { "Destination label must be trimmed" }
    }
}

object CoordinateInput {
    fun parse(latitude: String, longitude: String): GeoPoint {
        fun decimal(value: String): Double {
            val normalized = value.trim().replace(',', '.')
            require(normalized.isNotEmpty() && normalized !in setOf("-", "+", ".", "-.", "+.")) {
                "Incomplete coordinate"
            }
            return normalized.toDouble()
        }
        return GeoPoint(decimal(latitude), decimal(longitude))
    }
}

class DestinationBook(initial: List<SavedDestination> = emptyList()) {
    private val entries = initial.toMutableList()

    fun all(): List<SavedDestination> = entries.toList()

    fun add(destination: SavedDestination) {
        require(entries.none { it.label.equals(destination.label, ignoreCase = true) }) {
            "A destination with this label already exists"
        }
        entries += destination
    }

    fun rename(oldLabel: String, newLabel: String) {
        val index = entries.indexOfFirst { it.label == oldLabel }
        require(index >= 0) { "Destination not found" }
        val normalized = newLabel.trim()
        require(normalized.isNotEmpty())
        require(entries.none { it.label.equals(normalized, true) && it.label != oldLabel }) {
            "A destination with this label already exists"
        }
        entries[index] = entries[index].copy(label = normalized)
    }

    fun delete(label: String): Boolean = entries.removeAll { it.label == label }
}
