package dev.motonav.simulator.protocol

import dev.motonav.simulator.calls.CallPhase
import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.navigation.NormalizedRoutePoint
import dev.motonav.simulator.navigation.RouteContentSource

data class DisplayFrame(
    val sequence: Int,
    val state: NavigationState,
    val maneuver: Maneuver,
    val nextTurnMetres: Int,
    val remainingDistanceMetres: Long?,
    /** Whole travel minutes remaining, used with the synced clock to render ETA. */
    val remainingDurationMinutes: Int? = null,
    val points: List<NormalizedRoutePoint>,
    val mapHeadingDegrees: Int = 0,
    val contentSource: RouteContentSource = RouteContentSource.SYNTHETIC,
    /** Optional display lines; sent as printable ASCII of at most [FrameCodec.MAX_TEXT_LENGTH] characters. */
    val primaryText: String? = null,
    val secondaryText: String? = null,
    /** Incoming/ongoing phone call; the display alternates it with the route on its own. */
    val call: DisplayCall? = null,
    /** Local wall-clock time; the display keeps it ticking between frames. */
    val clock: DisplayClock? = null,
) {
    val hasText: Boolean get() = primaryText != null || secondaryText != null
}

data class DisplayCall(val phase: CallPhase, val caller: String, val status: String)

data class DisplayClock(val hour: Int, val minute: Int, val second: Int, val twentyFourHour: Boolean) {
    init {
        require(hour in 0..23 && minute in 0..59 && second in 0..59)
    }
}

object FrameCodec {
    const val PROTOCOL_VERSION = 3
    const val FRAGMENT_MAGIC = 0xA5
    const val MAX_POINTS = 32
    /** Wire limit; only a scrolling song title uses more than [SHORT_TEXT_LENGTH]. */
    const val MAX_TEXT_LENGTH = 40
    /** Place, destination and caller names, which must fit their screens without scrolling. */
    const val SHORT_TEXT_LENGTH = 20
    /** Largest frame without the optional text block; displays without text support accept this. */
    const val MAX_ROUTE_FRAME_BYTES = 177
    private const val TEXT_BLOCK_BYTES = 2 * (1 + MAX_TEXT_LENGTH)
    private const val CALL_BLOCK_BYTES = 1 + 2 * (1 + MAX_TEXT_LENGTH)
    private const val CLOCK_BLOCK_BYTES = 4
    private const val REMAINING_DURATION_BLOCK_BYTES = 2
    const val MAX_FRAME_BYTES = MAX_ROUTE_FRAME_BYTES + TEXT_BLOCK_BYTES + CALL_BLOCK_BYTES +
        CLOCK_BLOCK_BYTES + REMAINING_DURATION_BLOCK_BYTES
    private const val FRAME_HEADER_BYTES = 15
    private const val POINT_BYTES = 5
    private const val CRC_BYTES = 2

    fun encode(frame: DisplayFrame): ByteArray {
        require(frame.sequence in 0..0xFFFF)
        require(frame.nextTurnMetres in 0..0xFFFF)
        require(frame.remainingDistanceMetres == null || frame.remainingDistanceMetres in 0..0xFFFF_FFFFL)
        require(frame.remainingDurationMinutes == null || frame.remainingDurationMinutes in 0..0xFFFF)
        require(frame.mapHeadingDegrees in 0..359)
        require(frame.points.size <= MAX_POINTS)
        require(frame.points.isEmpty() || frame.points.first().startsPolyline)
        frame.points.forEach { require(it.x in 0..0xFFFF && it.y in 0..0xFFFF) }

        // Idle and live-route screens scroll a long song title, so they may use the full wire length.
        val textLimit = if (frame.state == NavigationState.READY || frame.state == NavigationState.NAVIGATING) {
            MAX_TEXT_LENGTH
        } else SHORT_TEXT_LENGTH
        val text = if (frame.hasText) {
            listOf(displayText(frame.primaryText, textLimit), displayText(frame.secondaryText, textLimit))
        } else emptyList()
        val textBytes = if (text.isEmpty()) 0 else 2 + text.sumOf { it.length }
        val call = frame.call?.let {
            val phase = when (it.phase) {
                CallPhase.RINGING -> 1
                CallPhase.ACTIVE -> 2
                CallPhase.MISSED -> 3
            }
            Triple(phase, displayText(it.caller), displayText(it.status))
        }
        val callBytes = call?.let { 3 + it.second.length + it.third.length } ?: 0
        val clockBytes = if (frame.clock != null) CLOCK_BLOCK_BYTES else 0
        val durationBytes = if (frame.remainingDurationMinutes != null) REMAINING_DURATION_BLOCK_BYTES else 0
        val bytes = ByteArray(
            FRAME_HEADER_BYTES + frame.points.size * POINT_BYTES + textBytes + callBytes + clockBytes +
                durationBytes + CRC_BYTES,
        )
        bytes[0] = PROTOCOL_VERSION.toByte()
        bytes[1] = frame.state.wireValue().toByte()
        bytes[2] = frame.maneuver.wireValue().toByte()
        bytes[3] = ((if (frame.remainingDistanceMetres != null) 1 else 0) or
            (if (frame.contentSource == RouteContentSource.GOOGLE_MAPS) 2 else 0) or
            (if (text.isNotEmpty()) 4 else 0) or
            (if (call != null) 8 else 0) or
            (if (frame.clock != null) 0x10 else 0) or
            (if (frame.remainingDurationMinutes != null) 0x20 else 0)).toByte()
        putU16(bytes, 4, frame.sequence)
        putU16(bytes, 6, frame.nextTurnMetres)
        putU32(bytes, 8, frame.remainingDistanceMetres ?: 0L)
        putU16(bytes, 12, frame.mapHeadingDegrees)
        bytes[14] = frame.points.size.toByte()
        frame.points.forEachIndexed { index, point ->
            val offset = FRAME_HEADER_BYTES + index * POINT_BYTES
            bytes[offset] = ((if (point.startsPolyline) 1 else 0) or (if (point.destination) 2 else 0)).toByte()
            putU16(bytes, offset + 1, point.x)
            putU16(bytes, offset + 3, point.y)
        }
        var textOffset = FRAME_HEADER_BYTES + frame.points.size * POINT_BYTES
        text.forEach { line ->
            bytes[textOffset++] = line.length.toByte()
            line.forEach { bytes[textOffset++] = it.code.toByte() }
        }
        call?.let { (phase, caller, status) ->
            bytes[textOffset++] = phase.toByte()
            listOf(caller, status).forEach { line ->
                bytes[textOffset++] = line.length.toByte()
                line.forEach { bytes[textOffset++] = it.code.toByte() }
            }
        }
        frame.clock?.let {
            bytes[textOffset++] = it.hour.toByte()
            bytes[textOffset++] = it.minute.toByte()
            bytes[textOffset++] = it.second.toByte()
            bytes[textOffset++] = (if (it.twentyFourHour) 1 else 0).toByte()
        }
        frame.remainingDurationMinutes?.let {
            putU16(bytes, textOffset, it)
            textOffset += REMAINING_DURATION_BLOCK_BYTES
        }
        putU16(bytes, bytes.size - CRC_BYTES, crc16CcittFalse(bytes, bytes.size - CRC_BYTES))
        return bytes
    }

    fun decode(bytes: ByteArray): DisplayFrame {
        require(bytes.size in (FRAME_HEADER_BYTES + CRC_BYTES)..MAX_FRAME_BYTES) { "Invalid frame length" }
        require(u8(bytes[0]) == PROTOCOL_VERSION) { "Unsupported protocol version" }
        val state = navigationStateFromWire(u8(bytes[1]))
        val maneuver = Maneuver.entries.getOrNull(u8(bytes[2])) ?: error("Invalid maneuver")
        val flags = u8(bytes[3])
        require(flags and 0xC0 == 0) { "Invalid frame flags" }
        val mapHeadingDegrees = readU16(bytes, 12)
        require(mapHeadingDegrees in 0..359) { "Invalid map heading" }
        val count = u8(bytes[14])
        require(count <= MAX_POINTS)
        var cursor = FRAME_HEADER_BYTES + count * POINT_BYTES
        val end = bytes.size - CRC_BYTES
        fun line(): String {
            require(cursor < end) { "Frame length mismatch" }
            val length = u8(bytes[cursor++])
            require(length <= MAX_TEXT_LENGTH && cursor + length <= end) { "Invalid text" }
            return String(ByteArray(length) { bytes[cursor + it] }, Charsets.US_ASCII).also { cursor += length }
        }
        val text = if (flags and 4 != 0) line() to line() else null
        val call = if (flags and 8 != 0) {
            require(cursor < end) { "Frame length mismatch" }
            val phase = when (u8(bytes[cursor++])) {
                1 -> CallPhase.RINGING
                2 -> CallPhase.ACTIVE
                3 -> CallPhase.MISSED
                else -> error("Invalid call phase")
            }
            DisplayCall(phase, line(), line())
        } else null
        val clock = if (flags and 0x10 != 0) {
            require(cursor + CLOCK_BLOCK_BYTES <= end) { "Frame length mismatch" }
            val options = u8(bytes[cursor + 3])
            require(options and 0xFE == 0) { "Invalid clock options" }
            DisplayClock(u8(bytes[cursor]), u8(bytes[cursor + 1]), u8(bytes[cursor + 2]), options and 1 != 0)
                .also { cursor += CLOCK_BLOCK_BYTES }
        } else null
        val remainingDurationMinutes = if (flags and 0x20 != 0) {
            require(cursor + REMAINING_DURATION_BLOCK_BYTES <= end) { "Frame length mismatch" }
            readU16(bytes, cursor).also { cursor += REMAINING_DURATION_BLOCK_BYTES }
        } else null
        require(cursor == end) { "Frame length mismatch" }
        require(readU16(bytes, bytes.size - CRC_BYTES) == crc16CcittFalse(bytes, bytes.size - CRC_BYTES)) {
            "CRC mismatch"
        }
        val points = (0 until count).map { index ->
            val offset = FRAME_HEADER_BYTES + index * POINT_BYTES
            val pointFlags = u8(bytes[offset])
            require(pointFlags and 0xFC == 0) { "Invalid point flags" }
            NormalizedRoutePoint(
                x = readU16(bytes, offset + 1),
                y = readU16(bytes, offset + 3),
                startsPolyline = pointFlags and 1 != 0,
                destination = pointFlags and 2 != 0,
            )
        }
        require(points.isEmpty() || points.first().startsPolyline) { "First point must start a polyline" }
        return DisplayFrame(
            sequence = readU16(bytes, 4),
            state = state,
            maneuver = maneuver,
            nextTurnMetres = readU16(bytes, 6),
            remainingDistanceMetres = if (flags and 1 != 0) readU32(bytes, 8) else null,
            remainingDurationMinutes = remainingDurationMinutes,
            mapHeadingDegrees = mapHeadingDegrees,
            points = points,
            contentSource = if (flags and 2 != 0) RouteContentSource.GOOGLE_MAPS else RouteContentSource.SYNTHETIC,
            primaryText = text?.first,
            secondaryText = text?.second,
            call = call,
            clock = clock,
        )
    }

    /**
     * The TFT uses the Adafruit ASCII font: strip accents, drop other characters and shorten with a
     * trailing "." so a long place name still reads naturally on a 21-column display.
     */
    fun displayText(value: String?, maxLength: Int = SHORT_TEXT_LENGTH): String {
        val plain = java.text.Normalizer.normalize(value.orEmpty(), java.text.Normalizer.Form.NFD)
            .filter { it.code in 0x20..0x7E }
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (plain.length <= maxLength) plain else plain.take(maxLength - 1).trimEnd() + "."
    }

    fun fragment(frameBytes: ByteArray, sequence: Int, maximumGattWriteBytes: Int = 20): List<ByteArray> {
        require(sequence in 0..0xFFFF)
        require(frameBytes.size <= MAX_FRAME_BYTES)
        require(maximumGattWriteBytes > 8) { "A fragment needs an eight-byte header and payload" }
        val payloadSize = maximumGattWriteBytes - 8
        return frameBytes.indices.step(payloadSize).map { offset ->
            val count = minOf(payloadSize, frameBytes.size - offset)
            ByteArray(8 + count).also { fragment ->
                fragment[0] = FRAGMENT_MAGIC.toByte()
                fragment[1] = PROTOCOL_VERSION.toByte()
                putU16(fragment, 2, sequence)
                putU16(fragment, 4, offset)
                putU16(fragment, 6, frameBytes.size)
                frameBytes.copyInto(fragment, 8, offset, offset + count)
            }
        }
    }

    fun crc16CcittFalse(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0xFFFF
        repeat(length) { index ->
            crc = crc xor (u8(bytes[index]) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1 xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    private fun NavigationState.wireValue() = when (this) {
        NavigationState.DISCONNECTED -> 0
        NavigationState.ACQUIRING_GPS -> 1
        NavigationState.NAVIGATING -> 2
        NavigationState.REROUTING -> 3
        NavigationState.ARRIVED -> 4
        NavigationState.GPS_DEGRADED -> 5
        NavigationState.WAITING_PERMISSION -> 6
        NavigationState.LOCATION_DISABLED -> 7
        NavigationState.READY -> 8
        NavigationState.ROUTE_PREVIEW -> 9
    }

    private fun navigationStateFromWire(value: Int) = when (value) {
        0 -> NavigationState.DISCONNECTED
        1 -> NavigationState.ACQUIRING_GPS
        2 -> NavigationState.NAVIGATING
        3 -> NavigationState.REROUTING
        4 -> NavigationState.ARRIVED
        5 -> NavigationState.GPS_DEGRADED
        6 -> NavigationState.WAITING_PERMISSION
        7 -> NavigationState.LOCATION_DISABLED
        8 -> NavigationState.READY
        9 -> NavigationState.ROUTE_PREVIEW
        else -> error("Invalid navigation state")
    }

    private fun Maneuver.wireValue() = when (this) {
        Maneuver.STRAIGHT -> 0
        Maneuver.LEFT -> 1
        Maneuver.RIGHT -> 2
        Maneuver.ARRIVE -> 3
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int = u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)
    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { result, index -> result or (u8(bytes[offset + index]).toLong() shl (index * 8)) }
    private fun u8(byte: Byte) = byte.toInt() and 0xFF
}
