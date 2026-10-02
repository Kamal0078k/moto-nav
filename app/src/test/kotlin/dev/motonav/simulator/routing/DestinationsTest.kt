package dev.motonav.simulator.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationsTest {
    @Test fun `coordinate input normalizes decimal separators and rejects incomplete identical ranges`() {
        assertEquals(GeoPoint(12.5, 77.25), CoordinateInput.parse(" 12,5 ", "77,25"))
        assertThrows(IllegalArgumentException::class.java) { CoordinateInput.parse("-", "77") }
        assertThrows(IllegalArgumentException::class.java) { CoordinateInput.parse("91", "77") }
        assertThrows(IllegalArgumentException::class.java) {
            RouteRequest(GeoPoint(12.0, 77.0), GeoPoint(12.0, 77.0))
        }
    }

    @Test fun `saved destinations add rename delete and prevent duplicate labels`() {
        val book = DestinationBook()
        book.add(SavedDestination("Office", GeoPoint(12.0, 77.0)))
        assertThrows(IllegalArgumentException::class.java) {
            book.add(SavedDestination("office", GeoPoint(13.0, 78.0)))
        }
        book.rename("Office", "Work")
        assertEquals("Work", book.all().single().label)
        assertTrue(book.delete("Work"))
        assertTrue(book.all().isEmpty())
    }
}
