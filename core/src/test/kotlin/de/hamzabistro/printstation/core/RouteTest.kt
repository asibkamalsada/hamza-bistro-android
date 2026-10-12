package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTest {
    private val base = """{"id":"a","order_number":1,"created_at":"2026-09-26T16:00:00+00:00","status":"new""""

    private fun read(tail: String): StaffOrder = json.decodeFromString(StaffOrder.serializer(), "$base$tail}")

    @Test
    fun `reads the ride as the database worked it out, and how sure it is`() {
        assertEquals(
            Ride(2.8, 11, estimated = false),
            read(""","route_km":2.8,"ride_minutes":11,"route_source":"routed"""").ride,
        )
        assertEquals(
            Ride(3.3, 10, estimated = true),
            read(""","route_km":3.3,"ride_minutes":10,"route_source":"estimated"""").ride,
        )
        // The postcode decided the ring: minutes, and no km to show.
        assertEquals(
            Ride(null, 15, estimated = true),
            read(""","route_km":null,"ride_minutes":15,"route_source":"estimated"""").ride,
        )
    }

    @Test
    fun `no ride for a pickup, or an order from before the columns`() {
        assertNull(read(""","pickup":true,"ride_minutes":5,"route_source":"estimated"""").ride)
        assertNull(read("").ride)
        assertNull(read(""","route_km":null,"ride_minutes":null,"route_source":null""").ride)
    }

    @Test
    fun `the queue reads the route on top of the points`() {
        assertEquals(
            listOf("route_km", "ride_minutes", "route_source"),
            StaffOrder.ROUTE_COLUMNS.split(",").takeLast(3),
        )
        assertTrue(StaffOrder.ROUTE_COLUMNS.startsWith(StaffOrder.POINT_COLUMNS + ","))
    }
}
