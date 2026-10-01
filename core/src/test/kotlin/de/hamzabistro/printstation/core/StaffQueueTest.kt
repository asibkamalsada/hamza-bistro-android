package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The cases of src/app/staff-queue.spec.ts in hamza-bistro-web: both have to read a queue alike. */
class StaffQueueTest {
    @Test
    fun `promised for the acceptance plus the minutes given, not the order time plus them`() {
        assertEquals(at("2026-09-26T16:32:00Z"), StaffQueue.promisedAt(order("a")))
    }

    @Test
    fun `promised for the chosen time for a pre-order, accepted or not`() {
        val time = "2026-09-26T17:15:00Z"
        assertEquals(at(time), StaffQueue.promisedAt(order("a", scheduledFor = time)))
        assertNotNull(StaffQueue.promisedAt(order("a", status = OrderStatus.NEW, scheduledFor = time)))
    }

    @Test
    fun `promised nothing while nobody has promised anything`() {
        assertNull(StaffQueue.promisedAt(order("a", status = OrderStatus.NEW, confirmedAt = null)))
        assertNull(StaffQueue.promisedAt(order("a", etaMinutes = null)))
    }

    @Test
    fun `falls back to when it was placed for an order accepted before that was recorded`() {
        assertEquals(at("2026-09-26T16:30:00Z"), StaffQueue.promisedAt(order("a", confirmedAt = null)))
    }

    @Test
    fun `starts a pre-order its lead time before it is due`() {
        val due = order("a", scheduledFor = "2026-09-26T17:15:00Z")
        assertEquals(at("2026-09-26T16:50:00Z"), StaffQueue.cookFrom(due, 25))
        assertNull(StaffQueue.cookFrom(order("a"), 25))
    }

    @Test
    fun `counts whole minutes, and calls a promise late the moment it has passed`() {
        val now = at("2026-09-26T16:00:00Z")
        assertEquals(12, StaffQueue.minutesUntil(at("2026-09-26T16:12:40Z"), now))
        assertEquals(-1, StaffQueue.minutesUntil(at("2026-09-26T15:59:30Z"), now))
        assertEquals(-5, StaffQueue.minutesUntil(at("2026-09-26T15:55:00Z"), now))
    }

    private val asap = order("asap", confirmedAt = "2026-09-26T16:00:00Z")
    private val preorderSoon = order("pre", createdAt = "2026-09-26T12:00:00Z", scheduledFor = "2026-09-26T16:10:00Z")
    private val waiting = order("new-late", status = OrderStatus.NEW, createdAt = "2026-09-26T16:05:00Z", confirmedAt = null)
    private val waitingLonger =
        order("new-early", status = OrderStatus.NEW, createdAt = "2026-09-26T16:01:00Z", confirmedAt = null)
    private val out = order("out", status = OrderStatus.ON_THE_WAY, confirmedAt = "2026-09-26T15:30:00Z")

    @Test
    fun `puts every order waiting for a decision first, the longest-waiting on top`() {
        val sorted = listOf(asap, waiting, out, waitingLonger).sortedWith(StaffQueue.order)
        assertEquals(listOf("new-early", "new-late"), sorted.take(2).map { it.id })
    }

    @Test
    fun `puts a pre-order due in ten minutes above an order for right away due in thirty`() {
        assertEquals(listOf("pre", "asap"), listOf(asap, preorderSoon).sortedWith(StaffQueue.order).map { it.id })
    }

    @Test
    fun `cuts the queue under what the phone holder does next, leaving out empty headings`() {
        val groups = StaffQueue.group(listOf(out, asap, waiting, preorderSoon))
        assertEquals(listOf(QueueGroup.DECIDE, QueueGroup.COOK, QueueGroup.OUT), groups.map { it.first })
        assertEquals(listOf("pre", "asap"), groups[1].second.map { it.id })
        assertEquals(listOf(QueueGroup.COOK), StaffQueue.group(listOf(asap)).map { it.first })
    }

    private val split =
        order("a").copy(
            street = "Karl-Heine-Str. 12",
            postalCode = "04229",
            city = "Leipzig",
            address = "Karl-Heine-Str. 12, Hinterhaus 3. OG, 04229 Leipzig",
            addressNote = "Hinterhaus 3. OG",
        )

    @Test
    fun `asks the map for the street and town, never the note`() {
        assertEquals("Karl-Heine-Str. 12, 04229 Leipzig", StaffQueue.destinationQuery(split))
        assertEquals("Hinterhaus 3. OG", StaffQueue.doorNote(split))
    }

    @Test
    fun `has only the one line for an order from before the address was split`() {
        val old = order("a").copy(street = "", postalCode = "", city = "", address = "Altweg 1, Leipzig")
        assertEquals("Altweg 1, Leipzig", StaffQueue.destinationQuery(old))
        assertEquals("Altweg 1, Leipzig", StaffQueue.addressLine(old))
        assertNull(StaffQueue.doorNote(old))
    }

    @Test
    fun `opens each app with the route already asked for, as the site links it`() {
        // encodeURIComponent('Karl-Heine-Str. 12, 04229 Leipzig')
        val q = "Karl-Heine-Str.%2012%2C%2004229%20Leipzig"
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=bicycling",
            StaffQueue.navigationUrl(split, NavApp.GOOGLE, TravelMode.BICYCLING),
        )
        assertEquals("https://maps.apple.com/?daddr=$q&dirflg=d", StaffQueue.navigationUrl(split, NavApp.APPLE, TravelMode.DRIVING))
        assertEquals("https://maps.apple.com/?daddr=$q", StaffQueue.navigationUrl(split, NavApp.APPLE, TravelMode.BICYCLING))
        assertEquals("https://waze.com/ul?q=$q&navigate=yes", StaffQueue.navigationUrl(split, NavApp.WAZE, TravelMode.DRIVING))
        assertEquals("geo:0,0?q=$q", StaffQueue.navigationUrl(split, NavApp.GEO, TravelMode.BICYCLING))
    }

    @Test
    fun `encodes umlauts as UTF-8, as encodeURIComponent does`() {
        assertEquals("Georg-Schwarz-Stra%C3%9Fe%20(1)", StaffQueue.encodeUriComponent("Georg-Schwarz-Straße (1)"))
    }
}
