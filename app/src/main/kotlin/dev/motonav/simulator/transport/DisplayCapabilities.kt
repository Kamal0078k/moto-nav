package dev.motonav.simulator.transport

import dev.motonav.simulator.protocol.FrameCodec as FrameCodecLimits

data class DisplayCapabilities(
    val protocolVersion: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val maximumPoints: Int,
    val maximumFrameBytes: Int,
    val flags: Int,
    /** Optional tenth capability byte; older firmware sends nine bytes, so this is 0. */
    val extraFlags: Int = 0,
) {
    val supportsRemainingDistance get() = flags and 0x01 != 0
    val supportsPolylineBreaks get() = flags and 0x02 != 0
    val supportsDestinationMarkers get() = flags and 0x04 != 0
    val supportsGoogleMapsAttribution get() = flags and 0x08 != 0
    val supportsLiveLocationStates get() = flags and 0x10 != 0
    val supportsHeadingUpMap get() = flags and 0x20 != 0
    /** Rider-facing Ready / Route-preview screens and the optional From / To text block. */
    val supportsTextScreens get() = flags and 0x40 != 0
    /** Incoming and active-call presentation. */
    val supportsCallAlerts get() = flags and 0x80 != 0
    /** Shows the time, kept ticking between frames. */
    val supportsClock get() = extraFlags and 0x01 != 0
    /** Accepts the optional remaining-duration block used to calculate ETA. */
    val supportsRemainingDuration get() = extraFlags and 0x02 != 0

    fun requireCompatible() {
        require(protocolVersion == 3) { "Display protocol $protocolVersion is unsupported" }
        require(screenWidth == 128 && screenHeight == 160) { "Display is ${screenWidth}×$screenHeight, expected 128×160" }
        require(maximumPoints >= 32) { "Display supports only $maximumPoints route points" }
        require(maximumFrameBytes >= FrameCodecLimits.MAX_ROUTE_FRAME_BYTES) { "Display frame limit is only $maximumFrameBytes bytes" }
        require(supportsRemainingDistance && supportsPolylineBreaks && supportsDestinationMarkers && supportsHeadingUpMap) {
            "Display is missing protocol-v3 capabilities"
        }
    }

    fun requireGoogleMapsAttribution() {
        requireCompatible()
        require(supportsGoogleMapsAttribution) {
            "Display firmware cannot render required Google Maps attribution"
        }
    }

    fun requireLiveLocationStates() {
        requireCompatible()
        require(supportsLiveLocationStates) {
            "Display firmware cannot render live location states"
        }
    }

    companion object {
        fun decode(bytes: ByteArray): DisplayCapabilities {
            require(bytes.size == 9 || bytes.size == 10) { "Capability value must contain 9 or 10 bytes" }
            fun u8(index: Int) = bytes[index].toInt() and 0xff
            fun u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
            return DisplayCapabilities(
                protocolVersion = u8(0),
                screenWidth = u16(1),
                screenHeight = u16(3),
                maximumPoints = u8(5),
                maximumFrameBytes = u16(6),
                flags = u8(8),
                extraFlags = if (bytes.size > 9) u8(9) else 0,
            )
        }
    }
}
