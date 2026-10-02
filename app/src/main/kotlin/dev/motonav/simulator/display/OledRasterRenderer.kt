package dev.motonav.simulator.display

import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.NavigationSnapshot
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.navigation.RenderedRoute
import dev.motonav.simulator.navigation.RouteRenderer
import kotlin.math.roundToInt

class MonochromeFrame(
    val width: Int = 128,
    val height: Int = 160,
) {
    internal val pixels = BooleanArray(width * height)

    fun isLit(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && pixels[y * width + x]

    fun set(x: Int, y: Int, lit: Boolean = true) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = lit
    }

    fun line(startX: Int, startY: Int, endX: Int, endY: Int, lit: Boolean = true) {
        var x = startX
        var y = startY
        val dx = kotlin.math.abs(endX - startX)
        val sx = if (startX < endX) 1 else -1
        val dy = -kotlin.math.abs(endY - startY)
        val sy = if (startY < endY) 1 else -1
        var error = dx + dy
        while (true) {
            set(x, y, lit)
            if (x == endX && y == endY) break
            val doubled = 2 * error
            if (doubled >= dy) { error += dy; x += sx }
            if (doubled <= dx) { error += dx; y += sy }
        }
    }

    fun rect(x: Int, y: Int, rectWidth: Int, rectHeight: Int, lit: Boolean = true) {
        line(x, y, x + rectWidth - 1, y, lit)
        line(x, y + rectHeight - 1, x + rectWidth - 1, y + rectHeight - 1, lit)
        line(x, y, x, y + rectHeight - 1, lit)
        line(x + rectWidth - 1, y, x + rectWidth - 1, y + rectHeight - 1, lit)
    }

    fun fillRect(x: Int, y: Int, rectWidth: Int, rectHeight: Int, lit: Boolean = true) {
        for (column in x until x + rectWidth) for (row in y until y + rectHeight) set(column, row, lit)
    }

    fun circle(centerX: Int, centerY: Int, radius: Int, lit: Boolean = true) {
        for (y in centerY - radius..centerY + radius) for (x in centerX - radius..centerX + radius) {
            if ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius) set(x, y, lit)
        }
    }

    fun triangle(a: IntArray, b: IntArray, c: IntArray, fill: Boolean, lit: Boolean = true) {
        if (!fill) {
            line(a[0], a[1], b[0], b[1], lit)
            line(b[0], b[1], c[0], c[1], lit)
            line(c[0], c[1], a[0], a[1], lit)
            return
        }
        val minX = minOf(a[0], b[0], c[0])
        val maxX = maxOf(a[0], b[0], c[0])
        val minY = minOf(a[1], b[1], c[1])
        val maxY = maxOf(a[1], b[1], c[1])
        fun edge(p: IntArray, q: IntArray, x: Int, y: Int) = (x - p[0]) * (q[1] - p[1]) - (y - p[1]) * (q[0] - p[0])
        for (y in minY..maxY) for (x in minX..maxX) {
            val e1 = edge(a, b, x, y)
            val e2 = edge(b, c, x, y)
            val e3 = edge(c, a, x, y)
            if ((e1 >= 0 && e2 >= 0 && e3 >= 0) || (e1 <= 0 && e2 <= 0 && e3 <= 0)) set(x, y, lit)
        }
    }

    /** Adafruit GFX classic-font text: 6×8 cells, optionally scaled like setTextSize(). */
    fun drawText(x: Int, y: Int, text: String, size: Int = 1, lit: Boolean = true) {
        text.forEachIndexed { index, character ->
            ClassicFont.glyph(character).forEachIndexed { column, bits ->
                for (row in 0..7) if (bits and (1 shl row) != 0) {
                    fillRect(x + (index * 6 + column) * size, y + row * size, size, size, lit)
                }
            }
        }
    }

    /** Outline circle using the same midpoint steps as Adafruit GFX drawCircle(). */
    fun circleOutline(centerX: Int, centerY: Int, radius: Int, lit: Boolean = true) {
        var f = 1 - radius
        var ddX = 1
        var ddY = -2 * radius
        var x = 0
        var y = radius
        set(centerX, centerY + radius, lit); set(centerX, centerY - radius, lit)
        set(centerX + radius, centerY, lit); set(centerX - radius, centerY, lit)
        while (x < y) {
            if (f >= 0) { y--; ddY += 2; f += ddY }
            x++; ddX += 2; f += ddX
            for ((dx, dy) in listOf(x to y, y to x)) {
                set(centerX + dx, centerY + dy, lit); set(centerX - dx, centerY + dy, lit)
                set(centerX + dx, centerY - dy, lit); set(centerX - dx, centerY - dy, lit)
            }
        }
    }

    /** Filled rectangle with quarter-circle corners, like Adafruit GFX fillRoundRect(). */
    fun fillRoundRect(x: Int, y: Int, rectWidth: Int, rectHeight: Int, radius: Int, lit: Boolean = true) {
        val r = radius.coerceAtMost(minOf(rectWidth, rectHeight) / 2).coerceAtLeast(0)
        for (row in y until y + rectHeight) for (column in x until x + rectWidth) {
            val cornerX = when {
                column < x + r -> x + r
                column > x + rectWidth - 1 - r -> x + rectWidth - 1 - r
                else -> column
            }
            val cornerY = when {
                row < y + r -> y + r
                row > y + rectHeight - 1 - r -> y + rectHeight - 1 - r
                else -> row
            }
            val dx = column - cornerX
            val dy = row - cornerY
            if (dx * dx + dy * dy <= r * r) set(column, row, lit)
        }
    }

    fun copy(): MonochromeFrame = MonochromeFrame(width, height).also { pixels.copyInto(it.pixels) }

    fun litPixels(): IntArray = pixels.indices.filter { pixels[it] }.toIntArray()

    fun packedPages(): ByteArray = ByteArray(width * ((height + 7) / 8)).also { bytes ->
        for (y in 0 until height) for (x in 0 until width) if (isLit(x, y)) {
            val index = x + (y / 8) * width
            bytes[index] = (bytes[index].toInt() or (1 shl (y and 7))).toByte()
        }
    }
}

object OledRasterRenderer {
    private const val HEADER_CONTENT_Y = 4
    private const val HEADER_BOTTOM = 24
    private const val NAV_STATUS_SIZE = 2
    private const val HERO_BOTTOM = 51
    private const val HERO_TILE_SIZE = 48
    private const val HERO_TILE_X = 3
    private const val HERO_TILE_Y = (HERO_BOTTOM + 1 - HERO_TILE_SIZE) / 2
    private const val HERO_TEXT_LEFT = HERO_TILE_X + HERO_TILE_SIZE + 5
    private const val HERO_TEXT_WIDTH = 128 - HERO_TEXT_LEFT - 2
    private const val FOOTER_TOP = 127
    private const val CALL_PILL_X = 4
    private const val CALL_PILL_Y = FOOTER_TOP + 1
    private const val CALL_PILL_WIDTH = 128 - 2 * CALL_PILL_X
    private const val CALL_PILL_HEIGHT = 160 - CALL_PILL_Y - 2
    private const val NAV_MUSIC_LINE_Y = HEADER_CONTENT_Y
    private const val MARKER_HALF_WIDTH = 8
    private const val MARKER_TIP_OFFSET = 11
    private const val MARKER_BASE_OFFSET = 6

    /** [clock] is the formatted time ("4:05" / "16:05"), or null before it is known. */
    fun render(
        snapshot: NavigationSnapshot,
        route: RenderedRoute,
        clock: String? = null,
    ): MonochromeFrame {
        // Screens and wording mirror drawBleFrame() in arduino-sketch/display2/display2.ino.
        val frame = when (snapshot.state) {
            NavigationState.READY -> readyFrame(clock, snapshot.primaryText, artist = snapshot.secondaryText)
            NavigationState.ROUTE_PREVIEW -> routePreviewFrame(snapshot, clock)
            NavigationState.DISCONNECTED -> messageFrame("Ride over", "Pick a route in", "the app to ride", clock)
            NavigationState.ACQUIRING_GPS -> messageFrame("Starting", "Finding GPS signal", "Ride starts soon", clock)
            NavigationState.GPS_DEGRADED -> messageFrame("GPS weak", "Signal is patchy", "Follow road signs", clock)
            NavigationState.WAITING_PERMISSION -> messageFrame("Location?", "Allow precise", "location in the app", clock)
            NavigationState.LOCATION_DISABLED -> messageFrame("GPS off", "Turn on phone", "location to ride", clock)
            NavigationState.REROUTING -> messageFrame("Rerouting", "Finding a new way", "Keep riding safely", clock)
            NavigationState.ARRIVED -> messageFrame("Arrived!", snapshot.primaryText?.takeIf { it.isNotBlank() } ?: "You made it", "Ride safe", clock)
            NavigationState.NAVIGATING -> navigationFrame(snapshot, route)
        }
        return frame
    }

    /** Mirrors drawCallScreen() in display2.ino: heading, phone tile, scrolling name, status pill. */
    fun callFrame(
        caller: String,
        status: String,
        clock: String? = null,
        phase: dev.motonav.simulator.calls.CallPhase = dev.motonav.simulator.calls.CallPhase.RINGING,
        elapsedSeconds: Long = 0,
        marqueeOffsetPx: Int = 0,
    ): MonochromeFrame = MonochromeFrame().also { frame ->
        val heading = when (phase) {
            dev.motonav.simulator.calls.CallPhase.RINGING -> "INCOMING CALL"
            dev.motonav.simulator.calls.CallPhase.ACTIVE -> "IN CALL"
            dev.motonav.simulator.calls.CallPhase.MISSED -> "MISSED CALL"
        }
        frame.centred(heading, 8)
        iconTile(frame, PHONE_SMALL, 24)
        drawScrollingCentredLine(frame, caller.ifBlank { "Unknown" }, 86, 2, marqueeOffsetPx)
        val label = when (phase) {
            dev.motonav.simulator.calls.CallPhase.ACTIVE -> formatCallDuration(elapsedSeconds)
            dev.motonav.simulator.calls.CallPhase.MISSED -> "MISSED"
            dev.motonav.simulator.calls.CallPhase.RINGING -> "RINGING"
        }
        pillText(frame, 160 - 42, 32, label, 2)
    }

    /** White rounded pill with dark text, the shared look of every non-route screen. */
    private fun pillText(frame: MonochromeFrame, y: Int, height: Int, text: String, size: Int) {
        frame.fillRoundRect(4, y, 120, height, minOf(9, height / 2))
        frame.centred(text, y + (height - 7 * size) / 2, size, lit = false)
    }

    /** 48 x 48 white tile with an 8 x 8 icon knocked out at 4x. */
    private fun iconTile(frame: MonochromeFrame, rows: IntArray, y: Int) {
        val x = (128 - 48) / 2
        frame.fillRoundRect(x, y, 48, 48, 9)
        rows.forEachIndexed { row, bits ->
            for (column in 0..7) if (bits and (0x80 shr column) != 0) {
                frame.fillRect(x + 8 + column * 4, y + 8 + row * 4, 4, 4, false)
            }
        }
    }

    /**
     * Mirrors drawCallHeader() in display2.ino. With a name on the route screen, ringing reads
     * "Ravi... [handset]" and an active call "[handset] Ravi"; otherwise just the handset.
     */
    fun withCallHeader(
        source: MonochromeFrame,
        phase: dev.motonav.simulator.calls.CallPhase,
        caller: String?,
        elapsedSeconds: Long = 0,
    ): MonochromeFrame =
        source.copy().also { frame ->
            val missed = phase == dev.motonav.simulator.calls.CallPhase.MISSED
            val name = when {
                caller == null -> ""
                missed -> "Missed"
                else -> headerCallerName(caller)
            }
            when {
                (phase == dev.motonav.simulator.calls.CallPhase.RINGING || missed) && name.isNotEmpty() -> {
                    val label = if (missed) name else name.take(4) + "..."
                    val iconX = 128 - 16 - 1
                    val textX = iconX - 4 - label.length * 6 * NAV_STATUS_SIZE
                    frame.fillRect(textX - 1, 0, 128 - textX + 1, HEADER_BOTTOM, false)
                    frame.drawText(textX, HEADER_CONTENT_Y, label, NAV_STATUS_SIZE)
                    drawScaledBitmap(frame, iconX, HEADER_CONTENT_Y, PHONE_SMALL, NAV_STATUS_SIZE)
                }
                phase == dev.motonav.simulator.calls.CallPhase.ACTIVE -> {
                    val label = formatCallDuration(elapsedSeconds)
                    val durationWidth = label.length * 6 * NAV_STATUS_SIZE
                    val totalWidth = 16 + 4 + durationWidth
                    val iconX = (128 - totalWidth) / 2
                    val textX = iconX + 16 + 4
                    frame.fillRect(0, 0, 128, HEADER_BOTTOM, false)
                    drawScaledBitmap(frame, iconX, HEADER_CONTENT_Y, PHONE_SMALL, NAV_STATUS_SIZE)
                    frame.drawText(textX, HEADER_CONTENT_Y, label, NAV_STATUS_SIZE)
                }
                else -> {
                    frame.fillRect(128 - 18, 0, 18, HEADER_BOTTOM, false)
                    drawScaledBitmap(frame, 128 - 17, HEADER_CONTENT_Y, PHONE_SMALL, NAV_STATUS_SIZE)
                }
            }
        }

    private fun formatCallDuration(elapsedSeconds: Long): String {
        val seconds = elapsedSeconds.coerceAtLeast(0)
        return if (seconds < 3_600) {
            "%02d:%02d".format(seconds / 60, seconds % 60)
        } else {
            "%d:%02d:%02d".format(seconds / 3_600, (seconds / 60) % 60, seconds % 60)
        }
    }

    /** Mirrors headerCallerName() in display2.ino. */
    fun headerCallerName(caller: String): String {
        if (caller.firstOrNull()?.let { it == '+' || it.isDigit() } == true) {
            val digits = caller.filter { it.isDigit() }.take(20)
            return digits.takeLast(5)
        }
        return caller.substringBefore(' ').take(9)
    }

    private val PHONE_SMALL = intArrayOf(0xC0, 0xE0, 0x60, 0x60, 0x30, 0x1C, 0x0F, 0x06)

    /** Mirrors formatClock() in display2.ino. */
    fun formatClock(time: java.time.LocalTime, twentyFourHour: Boolean): String =
        if (twentyFourHour) "%02d:%02d".format(time.hour, time.minute)
        else "%d:%02d".format((time.hour % 12).let { if (it == 0) 12 else it }, time.minute)

    fun formatDistance(metres: Int): String {
        val nonNegative = metres.coerceAtLeast(0)
        if (nonNegative < 1000) return "$nonNegative m"
        // Whole kilometres from 10 km keep the route header narrow enough for the clock.
        if (nonNegative >= 10_000) return "${(nonNegative + 500) / 1000} km"
        val tenths = (nonNegative + 50) / 100
        return "${tenths / 10}.${tenths % 10} km"
    }

    private fun navigationFrame(snapshot: NavigationSnapshot, route: RenderedRoute): MonochromeFrame {
        val frame = MonochromeFrame()
        val routeHeight = (RouteRenderer.ROUTE_BOTTOM - RouteRenderer.ROUTE_TOP).toDouble()
        val routeBottom = RouteRenderer.ROUTE_BOTTOM
        var previous: Pair<Int, Int>? = null
        route.points.forEach { point ->
            val x = (point.x * 127.0 / 65535.0).roundToInt()
            val y = RouteRenderer.ROUTE_TOP + (point.y * routeHeight / 65535.0).roundToInt()
            if (point.startsPolyline) {
                previous = x to y
            } else {
                previous?.let { (oldX, oldY) ->
                    frame.line(oldX, oldY, x, y)
                    if (oldX > 0 && x > 0) frame.line(oldX - 1, oldY, x - 1, y)
                    if (oldX > 1 && x > 1) frame.line(oldX - 2, oldY, x - 2, y)
                    if (oldX < 127 && x < 127) frame.line(oldX + 1, oldY, x + 1, y)
                    if (oldX < 126 && x < 126) frame.line(oldX + 2, oldY, x + 2, y)
                }
                previous = x to y
            }
            if (point.destination) drawDestination(frame, x, y, routeBottom)
        }
        drawPosition(frame, RouteRenderer.MARKER_Y)
        drawNavigationGuidance(
            frame,
            snapshot.maneuver,
            snapshot.metresToManeuver.roundToInt(),
            snapshot.remainingMetres.roundToInt(),
            snapshot.remainingDurationMillis,
        )
        return frame
    }

    /** Mirrors drawNavigationGuidance() in display2.ino: hero tile + distance, footer stats. */
    private fun drawNavigationGuidance(
        frame: MonochromeFrame,
        maneuver: Maneuver,
        metres: Int,
        remainingMetres: Int,
        remainingDurationMillis: Long?,
    ) {
        frame.fillRect(0, 0, 128, HERO_BOTTOM + 1, false)
        frame.fillRect(0, FOOTER_TOP, 128, 160 - FOOTER_TOP, false)
        frame.fillRoundRect(HERO_TILE_X, HERO_TILE_Y, HERO_TILE_SIZE, HERO_TILE_SIZE, 9)
        drawHeroManeuverIcon(frame, maneuver, HERO_TILE_X + 4, HERO_TILE_Y + 4, lit = false)

        val (number, unit) = splitHeroDistance(metres)
        val numberWidth = number.length * 18
        val unitSize = if (numberWidth + 3 + unit.length * 12 > HERO_TEXT_WIDTH) 1 else 2
        val unitWidth = unit.length * 6 * unitSize
        val textX = HERO_TEXT_LEFT + maxOf(0, (HERO_TEXT_WIDTH - numberWidth - 3 - unitWidth) / 2)
        val numberY = (HERO_BOTTOM + 1 - 21) / 2
        frame.drawText(textX, numberY, number, 3)
        frame.drawText(textX + numberWidth + 3, numberY + 21 - 7 * unitSize, unit, unitSize)

        val minutes = remainingDurationMillis?.let { ((it + 59_999L) / 60_000L).toInt() }
        footerCell(frame, 0, 64, formatRemainingDistance(remainingMetres), "TO GO")
        footerCell(frame, 64, 64, minutes?.let { etaText(it) } ?: "--", "ARRIVE")
    }

    private fun footerCell(frame: MonochromeFrame, left: Int, width: Int, value: String, label: String) {
        frame.drawText(left + (width - value.length * 12) / 2, FOOTER_TOP + 5, value, 2)
        frame.drawText(left + (width - label.length * 6) / 2, FOOTER_TOP + 23, label)
    }

    /** Arrival time from the wall clock; the preview always uses 24-hour time like currentClockText(). */
    private fun etaText(remainingMinutes: Int): String {
        val eta = java.time.LocalTime.now().plusMinutes(remainingMinutes.toLong())
        return formatClock(eta, twentyFourHour = true)
    }

    private fun splitHeroDistance(metres: Int): Pair<String, String> {
        val value = metres.coerceAtLeast(0)
        return when {
            value < 1_000 -> "$value" to "m"
            value < 10_000 -> ((value + 50) / 100).let { "${it / 10}.${it % 10}" } to "km"
            else -> "${(value + 500) / 1_000}" to "km"
        }
    }

    /** Mirrors formatRemainingDistance() in display2.ino. */
    private fun formatRemainingDistance(metres: Int): String {
        val value = metres.coerceAtLeast(0)
        return when {
            value < 1_000 -> "${value}m"
            value < 10_000 -> ((value + 50) / 100).let { "${it / 10}.${it % 10}km" }
            value < 100_000 -> "${(value + 500) / 1_000}km"
            else -> "${(value + 500) / 1_000}k"
        }
    }

    /** Whether a caller name is too wide for the footer pill and must scroll. */
    fun callFooterNeedsScrolling(caller: String) = caller.length * 12 > CALL_PILL_WIDTH - 12

    fun callFooterMarqueeOffset(caller: String, elapsedMillis: Long) = marqueeOffset(caller, elapsedMillis, 2)

    /**
     * Mirrors drawCallFooter() in display2.ino: on the route screen the caller replaces the remaining
     * distance / ETA with a white pill, scrolling the name when it is too wide.
     */
    fun withCallFooter(
        source: MonochromeFrame,
        phase: dev.motonav.simulator.calls.CallPhase,
        caller: String,
        elapsedSeconds: Long = 0,
        marqueeOffsetPx: Int = 0,
    ): MonochromeFrame = source.copy().also { frame ->
        frame.fillRect(0, FOOTER_TOP, 128, 160 - FOOTER_TOP, false)
        frame.fillRoundRect(CALL_PILL_X, CALL_PILL_Y, CALL_PILL_WIDTH, CALL_PILL_HEIGHT, 9)
        val name = caller.ifBlank { "Unknown" }
        val textLeft = CALL_PILL_X + 6
        val textArea = CALL_PILL_WIDTH - 12
        val nameY = CALL_PILL_Y + 5
        val textWidth = name.length * 12
        if (textWidth <= textArea) {
            frame.drawText(textLeft + (textArea - textWidth) / 2, nameY, name, 2, lit = false)
        } else {
            val cycleWidth = textWidth + MARQUEE_SPACING_PX * 2
            frame.drawText(textLeft - marqueeOffsetPx, nameY, name, 2, lit = false)
            frame.drawText(textLeft - marqueeOffsetPx + cycleWidth, nameY, name, 2, lit = false)
            // Text scrolled past the pill lands on black; square off the ends so it never runs under the curves.
            frame.fillRect(0, CALL_PILL_Y, CALL_PILL_X + 6, CALL_PILL_HEIGHT, false)
            frame.fillRect(CALL_PILL_X + CALL_PILL_WIDTH - 6, CALL_PILL_Y, CALL_PILL_X + 6, CALL_PILL_HEIGHT, false)
        }
        val status = when (phase) {
            dev.motonav.simulator.calls.CallPhase.ACTIVE -> "IN CALL ${formatCallDuration(elapsedSeconds)}"
            dev.motonav.simulator.calls.CallPhase.MISSED -> "MISSED CALL"
            dev.motonav.simulator.calls.CallPhase.RINGING -> "INCOMING CALL"
        }
        frame.drawText((128 - status.length * 6) / 2, CALL_PILL_Y + 21, status, lit = false)
    }

    private fun MonochromeFrame.centredIn(text: String, left: Int, areaWidth: Int, y: Int) =
        drawText(left + maxOf(0, (areaWidth - text.length * 6) / 2), y, text)

    private fun MonochromeFrame.centred(text: String, y: Int, size: Int = 1, lit: Boolean = true) =
        drawText(maxOf(0, (128 - text.length * 6 * size) / 2), y, text, size, lit)

    /** Mirrors drawMessageScreen(): the title (9 characters at most) in a white pill, guidance below. */
    private fun messageFrame(title: String, line1: String, line2: String?, clock: String? = null): MonochromeFrame =
        MonochromeFrame().also { frame ->
            clock?.let { frame.centred(it, 14, 2) }
            pillText(frame, 46, 40, title, 2)
            frame.centred(line1, 102)
            line2?.let { frame.centred(it, 120) }
        }

    private const val MUSIC_LINE_Y = 118
    private const val EYE_WIDTH = 25
    private const val EYE_HEIGHT = 30
    private const val EYE_RADIUS = 5
    private const val EYE_TOP = 32
    const val MARQUEE_SPACING_PX = 24
    const val MARQUEE_PAUSE_MS = 1_500L
    const val MARQUEE_STEP_MS = 40L
    private val MUSIC_NOTE = intArrayOf(0x0C, 0x0E, 0x0B, 0x09, 0x08, 0x78, 0xF8, 0x70)

    private fun titleNeedsScrolling(title: String, size: Int) =
        title.length * 6 * size > 128 - (8 * size + 4)

    fun titleNeedsScrolling(title: String) = titleNeedsScrolling(title, 1)

    fun navigationTitleNeedsScrolling(title: String) = titleNeedsScrolling(title, NAV_STATUS_SIZE)

    /** Scroll offset in pixels [elapsedMillis] into the marquee, matching drawMusicLine() in display2.ino. */
    private fun marqueeOffset(title: String, elapsedMillis: Long, size: Int): Int {
        val cycleWidth = title.length * 6 * size + MARQUEE_SPACING_PX * size
        val phase = elapsedMillis % (MARQUEE_PAUSE_MS + cycleWidth * MARQUEE_STEP_MS)
        return if (phase < MARQUEE_PAUSE_MS) 0 else ((phase - MARQUEE_PAUSE_MS) / MARQUEE_STEP_MS).toInt()
    }

    fun marqueeOffset(title: String, elapsedMillis: Long) = marqueeOffset(title, elapsedMillis, 1)

    fun navigationMarqueeOffset(title: String, elapsedMillis: Long) =
        marqueeOffset(title, elapsedMillis, NAV_STATUS_SIZE)

    fun nowPlayingTitleNeedsScrolling(title: String) = titleNeedsScrolling(title, 2)

    fun nowPlayingTitleMarqueeOffset(title: String, elapsedMillis: Long) =
        marqueeOffset(title, elapsedMillis, 2)

    /**
     * Mirrors drawIdleScreen() in display2.ino for a linked phone: eyes on top (still here; the display
     * animates them), the time below, then the note + song title. Happy eyes while music plays.
     */
    fun readyFrame(
        clock: String?,
        title: String?,
        marqueeOffsetPx: Int = 0,
        pose: FacePose = FacePose(),
        artist: String? = null,
        artistMarqueeOffsetPx: Int = 0,
    ): MonochromeFrame = MonochromeFrame().also { frame ->
        val music = !title.isNullOrBlank()
        if (music) {
            iconTile(frame, MUSIC_NOTE, 12)
            frame.centred("NOW PLAYING", 72)
            drawScrollingCentredLine(frame, title!!, 90, 2, marqueeOffsetPx)
            artist?.takeIf { it.isNotBlank() }?.let {
                drawScrollingCentredLine(frame, it, 118, 1, artistMarqueeOffsetPx)
            }
            return@also
        }
        // Same shapes as drawFace() in display2.ino: 25x30 rounded eyes, or 4-pixel bars mid-blink.
        listOf(pose.leftEyeX, pose.rightEyeX).forEach { x ->
            if (pose.blinking) frame.fillRect(x, EYE_TOP + EYE_HEIGHT / 2 - 2, EYE_WIDTH, 4)
            else frame.fillRoundRect(x, EYE_TOP, EYE_WIDTH, EYE_HEIGHT, EYE_RADIUS)
        }
        frame.centred(clock ?: "MotoNav", 82, if (clock != null) 3 else 2)
        if (!music) return@also
    }

    private fun drawScrollingCentredLine(
        frame: MonochromeFrame,
        text: String,
        y: Int,
        size: Int,
        marqueeOffsetPx: Int,
    ) {
        val textWidth = text.length * 6 * size
        if (textWidth <= 128) {
            frame.drawText((128 - textWidth) / 2, y, text, size)
            return
        }
        val cycleWidth = textWidth + MARQUEE_SPACING_PX * size
        frame.drawText(-marqueeOffsetPx, y, text, size)
        frame.drawText(-marqueeOffsetPx + cycleWidth, y, text, size)
    }

    private fun drawMusicLine(
        frame: MonochromeFrame,
        title: String,
        y: Int,
        marqueeOffsetPx: Int = 0,
        size: Int = 1,
    ) {
        val textLeft = 8 * size + 4
        if (!titleNeedsScrolling(title, size)) {
            val x = (128 - (textLeft + title.length * 6 * size)) / 2
            drawScaledBitmap(frame, x, y, MUSIC_NOTE, size)
            frame.drawText(x + textLeft, y, title, size)
            return
        }
        val cycleWidth = title.length * 6 * size + MARQUEE_SPACING_PX * size
        frame.drawText(textLeft - marqueeOffsetPx, y, title, size)
        frame.drawText(textLeft - marqueeOffsetPx + cycleWidth, y, title, size)
        frame.fillRect(0, y, textLeft, 8 * size, false)
        drawScaledBitmap(frame, 0, y, MUSIC_NOTE, size)
    }

    /** Splits a destination over two lines of at most 10 characters, breaking at a space when possible. */
    private fun splitDestination(text: String): Pair<String, String> {
        if (text.length <= 10) return text to ""
        val cut = (10 downTo 1).firstOrNull { text[it] == ' ' } ?: 10
        return text.take(cut) to text.drop(cut).trimStart().take(10)
    }

    /** Mirrors drawRoutePreview(): clock, start, destination in big type, ride distance in a pill. */
    private fun routePreviewFrame(snapshot: NavigationSnapshot, clock: String?): MonochromeFrame = MonochromeFrame().also { frame ->
        frame.centred(clock ?: "ROUTE READY", if (clock != null) 6 else 10, if (clock != null) 2 else 1)
        frame.circleOutline(9, 36, 4)
        frame.drawText(20, 32, (snapshot.primaryText?.takeIf { it.isNotBlank() } ?: "Start").take(18))
        frame.centred("GOING TO", 52)
        val (line1, line2) = splitDestination(snapshot.secondaryText?.takeIf { it.isNotBlank() } ?: "Destination")
        if (line2.isNotEmpty()) {
            frame.centred(line1, 66, 2)
            frame.centred(line2, 84, 2)
        } else {
            frame.centred(line1, 75, 2)
        }
        val (number, unit) = splitHeroDistance(snapshot.remainingMetres.roundToInt())
        frame.fillRoundRect(4, 112, 120, 40, 12)
        val x = (128 - number.length * 18 - 3 - unit.length * 12) / 2
        val y = 112 + (40 - 21) / 2
        frame.drawText(x, y, number, 3, lit = false)
        frame.drawText(x + number.length * 18 + 3, y + 21 - 14, unit, 2, lit = false)
    }

    private fun drawPosition(frame: MonochromeFrame, markerY: Int) {
        val a = intArrayOf(RouteRenderer.MARKER_X, markerY - MARKER_TIP_OFFSET)
        val b = intArrayOf(RouteRenderer.MARKER_X - MARKER_HALF_WIDTH, markerY + MARKER_BASE_OFFSET)
        val c = intArrayOf(RouteRenderer.MARKER_X + MARKER_HALF_WIDTH, markerY + MARKER_BASE_OFFSET)
        frame.triangle(a, b, c, fill = true, lit = false)
        frame.triangle(a, b, c, fill = false)
    }

    private fun drawDestination(frame: MonochromeFrame, anchorX: Int, anchorY: Int, routeBottom: Int) {
        if (anchorX !in 1..126 || anchorY < RouteRenderer.ROUTE_TOP + 10 || anchorY > routeBottom) return
        val flagTop = anchorY - 10
        val flagX = if (anchorX > 119) anchorX - 7 else anchorX
        val backingX = maxOf(0, flagX - 1)
        frame.fillRect(backingX, flagTop - 1, minOf(128 - backingX, 9), 12, false)
        frame.line(anchorX, anchorY, anchorX, flagTop)
        frame.rect(flagX, flagTop, 7, 6)
        frame.fillRect(flagX + 1, flagTop + 1, 2, 2)
        frame.fillRect(flagX + 3, flagTop + 3, 2, 2)
        frame.circle(anchorX, anchorY, 2)
    }

    private fun drawScaledBitmap(frame: MonochromeFrame, x: Int, y: Int, rows: IntArray, scale: Int) {
        rows.forEachIndexed { row, bits ->
            for (column in 0..7) if (bits and (0x80 shr column) != 0) {
                frame.fillRect(x + column * scale, y + row * scale, scale, scale)
            }
        }
    }

    /** 40 x 40 turn arrow, identical to drawHeroManeuverIcon() in display2.ino. */
    private fun drawHeroManeuverIcon(frame: MonochromeFrame, maneuver: Maneuver, x: Int, y: Int, lit: Boolean) {
        when (maneuver) {
            Maneuver.LEFT -> {
                frame.fillRect(x + 23, y + 15, 9, 25, lit)
                frame.fillRect(x + 11, y + 15, 21, 9, lit)
                frame.triangle(intArrayOf(x, y + 19), intArrayOf(x + 14, y + 4), intArrayOf(x + 14, y + 34), true, lit)
            }
            Maneuver.RIGHT -> {
                frame.fillRect(x + 8, y + 15, 9, 25, lit)
                frame.fillRect(x + 8, y + 15, 21, 9, lit)
                frame.triangle(intArrayOf(x + 39, y + 19), intArrayOf(x + 25, y + 4), intArrayOf(x + 25, y + 34), true, lit)
            }
            Maneuver.ARRIVE -> {
                frame.fillRect(x + 8, y, 4, 40, lit)
                for (row in 0 until 4) for (col in 0 until 6) {
                    frame.rect(x + 12 + col * 4, y + row * 4, 5, 5, lit)
                    if ((row + col) % 2 == 0) frame.fillRect(x + 12 + col * 4, y + row * 4, 4, 4, lit)
                }
            }
            Maneuver.STRAIGHT -> {
                frame.fillRect(x + 15, y + 17, 10, 23, lit)
                frame.triangle(intArrayOf(x + 20, y), intArrayOf(x + 3, y + 21), intArrayOf(x + 37, y + 21), true, lit)
            }
        }
    }

    private fun icon(maneuver: Maneuver): IntArray = when (maneuver) {
        Maneuver.STRAIGHT -> intArrayOf(0x18, 0x3C, 0x7E, 0x18, 0x18, 0x18, 0x18, 0x18)
        Maneuver.LEFT -> intArrayOf(0x20, 0x60, 0xE0, 0x60, 0x3E, 0x02, 0x02, 0x02)
        Maneuver.RIGHT -> intArrayOf(0x04, 0x06, 0x07, 0x06, 0x7C, 0x40, 0x40, 0x40)
        Maneuver.ARRIVE -> intArrayOf(0x60, 0x5C, 0x6A, 0x5C, 0x60, 0x40, 0x40, 0x40)
    }
}
