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
        north: Double? = null,
        east: Double = 0.0,
    ) =
        order(id, status = status, number = number)
            .copy(street = street, postalCode = postcode, city = "Leipzig", deliveryZone = ring)
            .let { if (north == null) it else it.copy(lat = Driver.SHOP.lat + north, lon = Driver.SHOP.lon + east) }

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
    fun `puts packed bags first, the one waiting longest on top`() {
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
        // Asked for now that the column exists (20261003180000_packed_step.sql).
        assertTrue("packed_at" in StaffOrder.COLUMNS.split(","))
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

    @Test
    fun `reads lat and lon as numbers, null or missing, and a point only with both`() {
        val base = """{"id":"a","order_number":1,"created_at":"2026-09-26T16:00:00+00:00","status":"on_the_way""""
        val read = { tail: String -> json.decodeFromString(StaffOrder.serializer(), "$base$tail}") }
        assertEquals(GeoPoint(51.34, 12.31), read(""","lat":51.34,"lon":12.31""").point)
        assertEquals(GeoPoint(51.0, 12.0), read(""","lat":51,"lon":12""").point)
        assertNull(read(""","lat":null,"lon":null""").point)
        assertNull(read("").point)
        assertNull(read(""","lat":51.34""").point)
        assertEquals(listOf("lat", "lon"), StaffOrder.POINT_COLUMNS.split(",").takeLast(2))
        assertTrue(StaffOrder.POINT_COLUMNS.startsWith(StaffOrder.SOURCE_COLUMNS + ","))
    }

    @Test
    fun `measures as the crow flies`() {
        // A hundredth of a degree north is about 1.1 km anywhere.
        val north = GeoPoint(Driver.SHOP.lat + 0.01, Driver.SHOP.lon)
        assertEquals(1112.0, Driver.SHOP.metresTo(north), 1.0)
        assertEquals(0.0, Driver.SHOP.metresTo(Driver.SHOP))
        assertEquals(Driver.SHOP.metresTo(north), north.metresTo(Driver.SHOP), 1e-9)
        assertEquals("51.33553,12.32958", Driver.SHOP.query)
        assertEquals("1,2 km", Driver.km(1234.0))
        assertEquals("0,3 km", Driver.km(250.0))
    }

    @Test
    fun `rides the nearest stop first from the shop, then the one nearest to that`() {
        // a is 0.6 km north, b 0.7 km south, c 1.5 km north: from a, c is nearer than b.
        val a = stop("a", ring = "near", north = 0.005)
        val b = stop("b", ring = "inner", north = -0.0063)
        val c = stop("c", ring = "far", north = 0.0135)
        assertEquals(listOf("a", "c", "b"), Driver.stops(listOf(b, c, a)).map { it.id })
        assertEquals(listOf("a", "c", "b"), Driver.route(listOf(c, b, a)).map { it.id })
        // Started elsewhere, it is nearest from there.
        assertEquals(listOf("b", "a", "c"), Driver.route(listOf(a, b, c), from = GeoPoint(Driver.SHOP.lat - 0.01, Driver.SHOP.lon)).map { it.id })
    }

    @Test
    fun `settles a tie in distance by ring, then postcode`() {
        val north = stop("north", ring = "near", north = 0.005)
        val south = stop("south", ring = "inner", north = -0.005)
        assertEquals(listOf("south", "north"), Driver.route(listOf(north, south)).map { it.id })
        val same1 = stop("same1", ring = "near", postcode = "04229", north = 0.005)
        val same0 = stop("same0", ring = "near", postcode = "04177", north = 0.005)
        assertEquals(listOf("same0", "same1"), Driver.route(listOf(same1, same0)).map { it.id })
    }

    @Test
    fun `puts the stops without a coordinate after the located ones, by ring then postcode`() {
        val orders =
            listOf(
                stop("inner-unlocated", ring = "inner", postcode = "04229"),
                stop("edge-located", ring = "edge", north = 0.05),
                stop("near-unlocated-b", ring = "near", postcode = "04229"),
                stop("far-located", ring = "far", north = 0.02),
                stop("near-unlocated-a", ring = "near", postcode = "04177"),
            )
        assertEquals(
            listOf("far-located", "edge-located", "inner-unlocated", "near-unlocated-a", "near-unlocated-b"),
            Driver.stops(orders).map { it.id },
        )
        // None located: part 1's order exactly.
        val none = orders.map { it.copy(lat = null, lon = null) }
        assertEquals(none.sortedWith(Driver.stopOrder).map { it.id }, Driver.stops(none).map { it.id })
        assertTrue(Driver.route(emptyList()).isEmpty())
    }

    @Test
    fun `keeps the driver's own order, and rides the rest from the last stop placed`() {
        val a = stop("a", north = 0.005)
        val b = stop("b", north = 0.03)
        val c = stop("c", north = 0.01)
        val d = stop("d", north = -0.02)
        // Placed b (3.3 km north): of the rest, c is nearest to b, then a, then d.
        assertEquals(listOf("b", "c", "a", "d"), Driver.stops(listOf(a, b, c, d), listOf("b")).map { it.id })
        // A manual order sticks whatever the distances say.
        assertEquals(listOf("d", "b", "a", "c"), Driver.stops(listOf(a, b, c, d), listOf("d", "b", "a", "c")).map { it.id })
    }

    @Test
    fun `says how far each stop is from the one before, the first from the shop`() {
        val legs = Driver.legs(listOf(stop("a", north = 0.01), stop("x"), stop("b", north = 0.02), stop("c", north = 0.03)))
        assertEquals(1112.0, legs[0]!!, 1.0)
        assertNull(legs[1])
        // After a stop without a coordinate, there is nothing to measure from.
        assertNull(legs[2])
        assertEquals(1112.0, legs[3]!!, 1.0)
        assertTrue(Driver.legs(emptyList()).isEmpty())
    }

    @Test
    fun `opens Google Maps with the coordinate where a stop has one, the address where not`() {
        val trip =
            listOf(
                stop("a", ring = "near", north = 0.01),
                stop("b", ring = "near", street = "Georg-Schwarz-Straße 3"),
                stop("c", ring = "far", north = 0.02, east = 0.001),
            )
        assertEquals(
            "https://www.google.com/maps/dir/?api=1" +
                "&destination=51.35553%2C12.33058" +
                "&waypoints=51.34553%2C12.32958%7CGeorg-Schwarz-Stra%C3%9Fe%203%2C%2004229%20Leipzig" +
                "&travelmode=driving",
            Driver.googleRouteUrl(trip, TravelMode.BICYCLING),
        )
        // Waze and Apple Maps, one stop at a time, still go by the address.
        assertEquals(
            "https://waze.com/ul?q=Karl-Heine-Str.%2012%2C%2004229%20Leipzig&navigate=yes",
            Driver.routeUrl(trip, NavApp.WAZE, TravelMode.BICYCLING),
        )
    }

    @Test
    fun `splits a long located trip in the riding order, three waypoints a link at most`() {
        val stops = Driver.stops((1..6).map { stop("s$it", number = it.toLong(), north = 0.002 * (7 - it)) })
        assertEquals((6 downTo 1).map { "s$it" }, stops.map { it.id })
        val trips = Driver.trips(stops)
        assertEquals(listOf(4, 2), trips.map { it.size })
        for (trip in trips) {
            val url = Driver.googleRouteUrl(trip, TravelMode.BICYCLING)
            assertTrue(url.substringAfter("&waypoints=", "").substringBefore('&').split("%7C").filter { it.isNotEmpty() }.size <= Driver.MAX_WAYPOINTS)
            assertTrue(url.contains("&destination=${StaffQueue.encodeUriComponent(trip.last().point!!.query)}&"))
        }
    }
}
