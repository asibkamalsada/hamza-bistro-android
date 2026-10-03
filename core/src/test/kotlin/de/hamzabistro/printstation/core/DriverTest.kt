package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriverTest {
    private fun stop(
        id: String,
        ring: String? = "inner",
        postcode: String = "04229",
        street: String = "Karl-Heine-Str. 12",
        status: OrderStatus = OrderStatus.ON_THE_WAY,
        number: Long = 57,
    ) = order(id, status = status, number = number).copy(street = street, postalCode = postcode, city = "Leipzig", deliveryZone = ring)

    @Test
    fun `reads the ring an order was priced in, and none for an unknown one`() {
        assertEquals(Ring.FAR, Ring.of(stop("a", ring = "far")))
        assertEquals(Ring.EDGE, Ring.of(stop("a", ring = " Edge ")))
        assertNull(Ring.of(stop("a", ring = null)))
        assertNull(Ring.of(stop("a", ring = "outside")))
    }

    @Test
    fun `shows the driver the deliveries in the kitchen and on their way, never a pickup`() {
        val orders =
            listOf(
                order("cooking"),
                order("riding", status = OrderStatus.ON_THE_WAY),
                order("pickup", pickup = true),
                order("new", status = OrderStatus.NEW),
                order("done", status = OrderStatus.DELIVERED),
            )
        assertEquals(listOf("cooking", "riding"), orders.filter(Driver::isForDriver).map { it.id })
        assertEquals(listOf("cooking"), orders.filter(Driver::canTake).map { it.id })
    }

    @Test
    fun `lists the bags to collect by when they are due`() {
        val later = order("later", confirmedAt = "2026-09-26T16:20:00Z")
        val sooner = order("sooner", confirmedAt = "2026-09-26T16:05:00Z")
        val pre = order("pre", scheduledFor = "2026-09-26T16:15:00Z")
        assertEquals(listOf("pre", "sooner", "later"), Driver.toCollect(listOf(later, sooner, pre)).map { it.id })
    }

    @Test
    fun `puts packed bags first, the one waiting longest on top, once packed_at exists`() {
        val dueFirst = order("due-first", confirmedAt = "2026-09-26T15:50:00Z")
        val packedLate = order("packed-late", confirmedAt = "2026-09-26T16:20:00Z").copy(packedAt = at("2026-09-26T16:40:00Z"))
        val packedEarly = order("packed-early", confirmedAt = "2026-09-26T16:30:00Z").copy(packedAt = at("2026-09-26T16:35:00Z"))
        assertEquals(
            listOf("packed-early", "packed-late", "due-first"),
            Driver.toCollect(listOf(dueFirst, packedLate, packedEarly)).map { it.id },
        )
    }

    @Test
    fun `reads packed_at from a row that has it, and does without when it does not`() {
        val base =
            """{"id":"a","order_number":1,"created_at":"2026-09-26T16:00:00+00:00","status":"confirmed""""
        assertNull(json.decodeFromString(StaffOrder.serializer(), "$base}").packedAt)
        assertEquals(
            at("2026-09-26T16:20:00Z"),
            json.decodeFromString(StaffOrder.serializer(), "$base,\"packed_at\":\"2026-09-26T16:20:00+00:00\"}").packedAt,
        )
        // Not asked for until the column exists: PostgREST refuses a select naming an unknown one.
        assertFalse("packed_at" in StaffOrder.COLUMNS)
    }

    @Test
    fun `takes only the ticked orders still in the kitchen, each as this device saw it`() {
        val a = order("a")
        val b = order("b", confirmedAt = "2026-09-26T16:10:00Z")
        val gone = order("gone", status = OrderStatus.ON_THE_WAY)
        val orders = listOf(b, a, gone, order("c"))
        val taken = Driver.batch(setOf("a", "b", "gone"), orders)
        assertEquals(listOf("a", "b"), taken.map { it.id })
        // The status sent along is the one read, so the server refuses a moved one alone.
        assertTrue(taken.all { it.status == OrderStatus.CONFIRMED })
        assertEquals(setOf("a", "b"), Driver.stillChosen(setOf("a", "b", "gone", "unknown"), orders))
    }

    @Test
    fun `rides the stops ring by ring from the shop, then by postcode, then by street`() {
        val stops =
            listOf(
                stop("edge", ring = "edge", postcode = "04103"),
                stop("near-b", ring = "near", postcode = "04229", street = "Zschochersche Str. 1"),
                stop("inner", ring = "inner", postcode = "04229"),
                stop("near-a", ring = "near", postcode = "04229", street = "angerstr. 5"),
                stop("near-early-postcode", ring = "near", postcode = "04177"),
                stop("no-ring", ring = null, postcode = "04003"),
                stop("far", ring = "far", postcode = "04209"),
            )
        assertEquals(
            listOf("inner", "near-early-postcode", "near-a", "near-b", "far", "edge", "no-ring"),
            Driver.stops(stops).map { it.id },
        )
    }

    @Test
    fun `keeps the driver's own order, and puts stops it does not name after it`() {
        val orders = listOf(stop("a", ring = "inner"), stop("b", ring = "near"), stop("c", ring = "far"), stop("d", ring = "edge"))
        assertEquals(listOf("c", "a", "b", "d"), Driver.stops(orders, listOf("c", "a", "delivered-meanwhile")).map { it.id })
        // Only what is on its way is a stop.
        assertEquals(listOf("a"), Driver.stops(listOf(stop("a"), stop("k", status = OrderStatus.CONFIRMED))).map { it.id })
    }

    @Test
    fun `moves a stop up or down one place, and not past either end`() {
        val stops = Driver.stops(listOf(stop("a", ring = "inner"), stop("b", ring = "near"), stop("c", ring = "far")))
        assertEquals(listOf("a", "c", "b"), Driver.move(stops, "c", -1))
        assertEquals(listOf("b", "a", "c"), Driver.move(stops, "a", 1))
        assertEquals(listOf("a", "b", "c"), Driver.move(stops, "a", -1))
        assertEquals(listOf("a", "b", "c"), Driver.move(stops, "c", 1))
        assertEquals(listOf("a", "b", "c"), Driver.move(stops, "nope", 1))
    }

    @Test
    fun `rides by bike to the inner rings and by car as soon as one stop is further out`() {
        assertEquals(TravelMode.BICYCLING, Driver.travelMode(listOf(stop("a", ring = "inner"), stop("b", ring = "near")), TravelMode.DRIVING))
        assertEquals(TravelMode.DRIVING, Driver.travelMode(listOf(stop("a", ring = "inner"), stop("b", ring = "far")), TravelMode.BICYCLING))
        assertEquals(TravelMode.DRIVING, Driver.travelMode(listOf(stop("a", ring = "edge")), TravelMode.BICYCLING))
        // No ring known: as the device is set.
        assertEquals(TravelMode.DRIVING, Driver.travelMode(listOf(stop("a", ring = null)), TravelMode.DRIVING))
        assertEquals(TravelMode.BICYCLING, Driver.travelMode(listOf(stop("a", ring = null), stop("b", ring = "near")), TravelMode.DRIVING))
    }

    @Test
    fun `opens Google Maps with every stop of a trip, the last one the destination`() {
        val trip =
            listOf(
                stop("a", street = "Karl-Heine-Str. 12"),
                stop("b", ring = "near", street = "Georg-Schwarz-Straße 3"),
                stop("c", ring = "near", street = "Lützner Str. 1/2"),
            )
        assertEquals(
            "https://www.google.com/maps/dir/?api=1" +
                "&destination=L%C3%BCtzner%20Str.%201%2F2%2C%2004229%20Leipzig" +
                "&waypoints=Karl-Heine-Str.%2012%2C%2004229%20Leipzig%7CGeorg-Schwarz-Stra%C3%9Fe%203%2C%2004229%20Leipzig" +
                "&travelmode=bicycling",
            Driver.googleRouteUrl(trip, TravelMode.DRIVING),
        )
    }

    @Test
    fun `leaves the waypoints out for a single stop, and never sends the door note`() {
        val one = stop("a", ring = "far").copy(addressNote = "Hinterhaus, 3. OG & klingeln")
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=Karl-Heine-Str.%2012%2C%2004229%20Leipzig&travelmode=driving",
            Driver.googleRouteUrl(listOf(one), TravelMode.BICYCLING),
        )
    }

    @Test
    fun `encodes what would break the link, an ampersand, a hash, a pipe`() {
        val odd = stop("a", street = "Am Markt 1 & 2 #3 | Hof")
        val url = Driver.googleRouteUrl(listOf(odd, odd), TravelMode.BICYCLING)
        assertFalse('#' in url || '|' in url || ' ' in url)
        // ?api=1, destination, waypoints, travelmode: the address's own "&" is not one more.
        assertEquals(4, url.split('&').size)
        assertTrue("Am%20Markt%201%20%26%202%20%233%20%7C%20Hof" in url)
    }

    @Test
    fun `splits a trip past three waypoints into links Google Maps can take`() {
        val stops = (1..9).map { stop("s$it", number = it.toLong()) }
        val trips = Driver.trips(stops)
        assertEquals(listOf(4, 4, 1), trips.map { it.size })
        assertEquals(stops.map { it.id }, trips.flatten().map { it.id })
        assertEquals(1, Driver.trips(stops.take(4)).size)
        assertTrue(Driver.trips(emptyList()).isEmpty())
        for (trip in trips) {
            val waypoints = Driver.googleRouteUrl(trip, TravelMode.BICYCLING).substringAfter("&waypoints=", "").substringBefore('&')
            assertTrue(waypoints.split("%7C").filter { it.isNotEmpty() }.size <= Driver.MAX_WAYPOINTS)
        }
        assertFailsWith<IllegalArgumentException> { Driver.googleRouteUrl(stops.take(5), TravelMode.BICYCLING) }
    }

    @Test
    fun `goes one stop at a time in the apps that take one destination`() {
        val trip = listOf(stop("a", ring = "far"), stop("b"))
        val q = "Karl-Heine-Str.%2012%2C%2004229%20Leipzig"
        assertEquals("https://waze.com/ul?q=$q&navigate=yes", Driver.routeUrl(trip, NavApp.WAZE, TravelMode.BICYCLING))
        assertEquals("https://maps.apple.com/?daddr=$q&dirflg=d", Driver.routeUrl(trip, NavApp.APPLE, TravelMode.BICYCLING))
        assertTrue(Driver.routeUrl(trip, NavApp.GOOGLE, TravelMode.BICYCLING).contains("&waypoints="))
        assertTrue(Driver.multiStop(NavApp.GOOGLE))
        assertFalse(Driver.multiStop(NavApp.WAZE))
        assertFalse(Driver.multiStop(NavApp.APPLE))
    }
}
