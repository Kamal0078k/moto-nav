package dev.motonav.simulator.protocol

import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.navigation.NormalizedRoutePoint
import dev.motonav.simulator.navigation.RouteContentSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameCodecTest {
    private val goldenFrame = DisplayFrame(
        sequence = 4660,
        state = NavigationState.NAVIGATING,
        maneuver = Maneuver.RIGHT,
        nextTurnMetres = 120,
        remainingDistanceMetres = 1500,
        mapHeadingDegrees = 90,
        points = listOf(
            NormalizedRoutePoint(32768, 65535, startsPolyline = true),
            NormalizedRoutePoint(32768, 30000),
            NormalizedRoutePoint(50000, 1000, destination = true),
        ),
    )

    @Test fun `encoding and fragmentation match the shared golden vectors`() {
        val json = File(System.getProperty("repositoryRoot"), "docs/protocol/golden-frames.json").readText()
        val expectedFrame = Regex("\\\"frameHex\\\"\\s*:\\s*\\\"([0-9a-f]+)\\\"").find(json)!!.groupValues[1]
        val expectedFragments = Regex("\\\"fragmentsFor20ByteGattWrite\\\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(json)!!.groupValues[1].let { block -> Regex("\\\"([0-9a-f]+)\\\"").findAll(block).map { it.groupValues[1] }.toList() }
        val encoded = FrameCodec.encode(goldenFrame)
        assertEquals(expectedFrame, encoded.hexString())
        assertEquals(expectedFragments, FrameCodec.fragment(encoded, goldenFrame.sequence).map { it.hexString() })
    }

    @Test fun `Google frames carry explicit attribution source flag`() {
        val encoded = FrameCodec.encode(goldenFrame.copy(contentSource = RouteContentSource.GOOGLE_MAPS))
        assertEquals(3, encoded[3].toInt())
        assertEquals(RouteContentSource.GOOGLE_MAPS, FrameCodec.decode(encoded).contentSource)
        assertEquals(RouteContentSource.SYNTHETIC, FrameCodec.decode(FrameCodec.encode(goldenFrame)).contentSource)
    }

    @Test fun `live location states round trip without changing existing wire values`() {
        assertEquals(2, FrameCodec.encode(goldenFrame)[1].toInt())
        listOf(
            NavigationState.GPS_DEGRADED to 5,
            NavigationState.WAITING_PERMISSION to 6,
            NavigationState.LOCATION_DISABLED to 7,
        ).forEach { (state, wire) ->
            val encoded = FrameCodec.encode(goldenFrame.copy(state = state))
            assertEquals(wire, encoded[1].toInt())
            assertEquals(state, FrameCodec.decode(encoded).state)
        }
    }

    @Test fun `decode round trips and rejects crc corruption`() {
        val encoded = FrameCodec.encode(goldenFrame)
        assertEquals(goldenFrame, FrameCodec.decode(encoded))
        encoded[14] = (encoded[14].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.decode(encoded) }
    }

    @Test fun `map heading round trips and rejects values outside a circle`() {
        assertEquals(90, FrameCodec.decode(FrameCodec.encode(goldenFrame)).mapHeadingDegrees)
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(goldenFrame.copy(mapHeadingDegrees = 360))
        }
    }

    @Test fun `codec enforces protocol point limit`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(goldenFrame.copy(points = List(33) { NormalizedRoutePoint(it, it, startsPolyline = it == 0) }))
        }
    }

    @Test fun `fragment size follows negotiated gatt payload`() {
        val encoded = FrameCodec.encode(goldenFrame)
        val defaultFragments = FrameCodec.fragment(encoded, goldenFrame.sequence, 20)
        val mtu185Fragments = FrameCodec.fragment(encoded, goldenFrame.sequence, 182)
        assertEquals(3, defaultFragments.size)
        assertEquals(1, mtu185Fragments.size)
        assertEquals(encoded.size + 8, mtu185Fragments.single().size)
    }

    private fun ByteArray.hexString() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
