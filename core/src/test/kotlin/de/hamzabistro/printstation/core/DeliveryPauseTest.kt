package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Pausing only delivery or the outer rings (web#72), and the weekly delivery breaks (web#112). */
class DeliveryPauseTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseShopBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    /** A Friday, 12:30 in Leipzig. */
    private val now = Instant.parse("2026-10-02T10:30:00Z")

    private fun at(leipzig: String) = Instant.parse("2026-10-02T${leipzig}:00+02:00")

    private fun closure(id: Long, scope: String, rings: List<String>? = null, until: String? = "13:30", by: String? = null) =
        ShopClosure(id, at("12:00"), until?.let(::at), by, scope, rings)

    private val prayer = DeliveryBreak(id = 7, day = 5, starts = 12 * 60 + 45, ends = 14 * 60, label = "Freitagsgebet")

    @Test
    fun `reads the scope of each closure, and the breaks, with the hours`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """{"delivery":[],"open_now":true,"open_until":"2026-10-02T18:00:00+00:00","next_open":null,""" +
                """"closures":[{"id":4,"starts_at":"2026-10-02T10:00:00+00:00","ends_at":"2026-10-02T11:30:00+00:00","by":"koch@example.com","scope":"rings","rings":["edge","far"]}],""" +
                """"delivery_pause":{"scope":"rings","rings":["edge","far"],"until":"2026-10-02T11:30:00+00:00"},""" +
                """"delivery_breaks":[{"id":7,"day":5,"starts":765,"ends":840,"label":"Freitagsgebet","active":true},""" +
                """{"id":8,"day":1,"starts":600,"ends":660,"label":"","active":false}],""" +
                """"delivery_break_lead_minutes":45,"delivery_break":null}""",
        )

        val hours = backend.hours()!!
        assertEquals(ClosureScope.RINGS, hours.closures.single().scope)
        assertEquals(listOf("edge", "far"), hours.closures.single().rings)
        // Paused rings are no closure: the shop is open, for collection and the inner rings.
        assertEquals(ShopState.OPEN, hours.state(now))
        assertNull(hours.closureAt(now))
        val pause = hours.deliveryPauseAt(now)!!
        assertEquals(PauseWhat.FAR, pause.what)
        assertEquals(Instant.parse("2026-10-02T11:30:00Z"), pause.until)
        assertEquals("koch@example.com", pause.by)
        assertEquals(listOf(prayer, DeliveryBreak(8, 1, 600, 660, "", active = false)), hours.deliveryBreaks)
        assertEquals(45, hours.breakLeadMinutes)
    }

    @Test
    fun `a closure from a database without scopes stops everything`() {
        val hours = ShopHours(openNow = false, closures = listOf(ShopClosure(1, at("12:00"), at("13:00"))))
        assertEquals(ShopState.CLOSED, hours.state(now))
        assertNull(hours.deliveryPauseAt(now))
        // And a database without breaks has none, with the usual lead.
        assertEquals(emptyList(), hours.deliveryBreaks)
        assertEquals(30, hours.breakLeadMinutes)
    }

    @Test
    fun `a pause of all delivery outranks a pause of rings`() {
        val hours =
            ShopHours(
                openNow = true,
                closures = listOf(closure(1, "rings", listOf("edge"), until = null), closure(2, "delivery", until = "13:00")),
            )
        val pause = hours.deliveryPauseAt(now)!!
        assertEquals(PauseWhat.DELIVERY, pause.what)
        assertEquals(emptyList(), pause.rings)
        // Its own end, not the open-ended ring pause's.
        assertEquals(at("13:00"), pause.until)
        // Once over, the ring pause is what is left, until further notice.
        val later = hours.deliveryPauseAt(at("13:05"))!!
        assertEquals(PauseWhat.EDGE, later.what)
        assertNull(later.until)
    }

    @Test
    fun `ring pauses running together add up and last until the longest ends`() {
        val hours =
            ShopHours(
                openNow = true,
                closures = listOf(closure(1, "rings", listOf("edge"), until = "13:00"), closure(2, "rings", listOf("near"), until = "14:00")),
            )
        val pause = hours.deliveryPauseAt(now)!!
        assertEquals(listOf("edge", "near"), pause.rings)
        assertEquals(at("14:00"), pause.until)
        // Neither "Weite Ringe" nor "Nur Rand": the line names them.
        assertNull(pause.what)
    }

    @Test
    fun `a pause of delivery ends by itself between two reads, and one planned has not started`() {
        val hours = ShopHours(openNow = true, closures = listOf(closure(1, "delivery", until = "12:45")))
        assertEquals(PauseWhat.DELIVERY, hours.deliveryPauseAt(now)?.what)
        assertNull(hours.deliveryPauseAt(at("12:45")))
        assertNull(hours.deliveryPauseAt(at("11:59")))
    }

    @Test
    fun `everything closed is still a closure while delivery is paused too`() {
        val hours = ShopHours(openNow = false, closures = listOf(closure(1, "delivery", until = null), closure(2, "all", until = "13:00")))
        assertEquals(ShopState.CLOSED, hours.state(now))
        assertEquals(2L, hours.closureAt(now)?.id)
        assertEquals(at("13:00"), hours.reopensAt(now))
    }

    @Test
    fun `pauses only delivery, the far rings or the edge, and everything as before`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        repeat(4) { test.reply(200, """{"open_now":true,"closures":[],"preorders_inside":1}""") }

        assertEquals(1, backend.close(at("13:00"), what = PauseWhat.DELIVERY).preordersInside)
        backend.close(at("13:00"), what = PauseWhat.FAR)
        backend.close(null, what = PauseWhat.EDGE)
        backend.close(at("13:00"))

        test.server.takeRequest()
        val delivery = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/shop_close", delivery.url.encodedPath)
        assertEquals("""{"p_until":"2026-10-02T11:00:00Z","p_scope":"delivery"}""", delivery.body!!.utf8())
        assertEquals(
            """{"p_until":"2026-10-02T11:00:00Z","p_scope":"rings","p_rings":["far","edge"]}""",
            test.server.takeRequest().body!!.utf8(),
        )
        assertEquals("""{"p_until":null,"p_scope":"rings","p_rings":["edge"]}""", test.server.takeRequest().body!!.utf8())
        // Everything sends no scope, which every database takes.
        assertEquals("""{"p_until":"2026-10-02T11:00:00Z"}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `a closure reads as one of the four choices, or as its rings`() {
        assertEquals(PauseWhat.ALL, PauseWhat.of("all", null))
        assertEquals(PauseWhat.DELIVERY, PauseWhat.of("delivery", null))
        assertEquals(PauseWhat.FAR, PauseWhat.of("rings", listOf("edge", "far")))
        assertEquals(PauseWhat.FAR, PauseWhat.of("rings", listOf("far")))
        assertEquals(PauseWhat.EDGE, PauseWhat.of("rings", listOf("edge")))
        assertNull(PauseWhat.of("rings", listOf("near", "edge")))
        // Each choice reads back as itself.
        for (what in PauseWhat.entries) assertEquals(what, PauseWhat.of(what.scope, what.rings))
    }

    @Test
    fun `a break is said while it runs, and from the lead before it`() {
        val hours = ShopHours(openNow = true, deliveryBreaks = listOf(prayer))
        // 12:30, fifteen minutes before: soon.
        assertEquals(BreakNotice(prayer, running = false), hours.breakNotice(now))
        assertEquals(BreakNotice(prayer, running = true), hours.breakNotice(at("12:45")))
        assertEquals(BreakNotice(prayer, running = true), hours.breakNotice(at("13:59")))
        assertNull(hours.breakNotice(at("14:00")))
        // 12:14 is more than the thirty minutes before.
        assertNull(hours.breakNotice(at("12:14")))
        assertEquals(BreakNotice(prayer, running = false), hours.breakNotice(at("12:15")))
        // With no lead, only once it runs.
        assertNull(hours.copy(breakLeadMinutes = 0).breakNotice(now))
    }

    @Test
    fun `a break on another day, switched off, or while the shop is closed is not said`() {
        val hours = ShopHours(openNow = true, deliveryBreaks = listOf(prayer.copy(day = 4), prayer.copy(id = 9, active = false)))
        assertNull(hours.breakNotice(at("13:00")))
        val closed = ShopHours(openNow = true, closures = listOf(closure(1, "all", until = "14:00")), deliveryBreaks = listOf(prayer))
        assertNull(closed.breakNotice(at("13:00")))
        // Outside the hours nobody orders for right now anyway.
        assertNull(ShopHours(openNow = false, deliveryBreaks = listOf(prayer)).breakNotice(at("13:00")))
    }

    @Test
    fun `of two breaks the earliest is said, and a delivery pause does not hide one`() {
        val early = DeliveryBreak(id = 3, day = 5, starts = 12 * 60 + 30, ends = 13 * 60)
        val hours = ShopHours(openNow = true, closures = listOf(closure(1, "delivery")), deliveryBreaks = listOf(prayer, early))
        assertEquals(BreakNotice(early, running = true), hours.breakNotice(at("12:40")))
        // The first one over, the next is said.
        assertEquals(BreakNotice(prayer, running = true), hours.breakNotice(at("13:00")))
        // Before both, the one starting first.
        assertEquals(BreakNotice(early, running = false), hours.breakNotice(at("12:10")))
    }

    @Test
    fun `a break is read on the Leipzig clock, whatever the day is in UTC`() {
        // Saturday 00:30 in Leipzig is still Friday in UTC.
        val late = DeliveryBreak(id = 1, day = 6, starts = 0, ends = 60)
        val hours = ShopHours(openNow = true, deliveryBreaks = listOf(late))
        assertEquals(BreakNotice(late, running = true), hours.breakNotice(Instant.parse("2026-10-02T22:30:00Z")))
        // Sunday is 0.
        val sunday = DeliveryBreak(id = 2, day = 0, starts = 600, ends = 660)
        assertEquals(sunday, ShopHours(openNow = true, deliveryBreaks = listOf(sunday)).breakAt(Instant.parse("2026-10-04T08:15:00Z")))
    }

    @Test
    fun `a break is what the table allows`() {
        assertTrue(prayer.valid)
        assertTrue(DeliveryBreak(day = 0, starts = 1380, ends = 1440).valid)
        assertFalse(prayer.copy(starts = 770).valid)
        assertFalse(prayer.copy(ends = prayer.starts).valid)
        assertFalse(prayer.copy(starts = 840, ends = 765).valid)
        assertFalse(prayer.copy(ends = 1455).valid)
        assertFalse(prayer.copy(day = 7).valid)
        assertFalse(prayer.copy(label = "x".repeat(61)).valid)
        assertTrue(prayer.copy(label = "x".repeat(60) + "  ").valid)
    }

    @Test
    fun `the lead choices include the one saved, and breaks read Monday first`() {
        assertEquals(listOf(0, 15, 30, 45, 60), DeliveryBreak.leadChoices(30))
        assertEquals(listOf(0, 15, 30, 45, 60, 90), DeliveryBreak.leadChoices(90))
        assertEquals(listOf(0, 5, 15, 30, 45, 60), DeliveryBreak.leadChoices(5))
        val sunday = DeliveryBreak(id = 1, day = 0, starts = 600, ends = 660)
        val mondayLate = DeliveryBreak(id = 2, day = 1, starts = 900, ends = 960)
        val mondayEarly = DeliveryBreak(id = 3, day = 1, starts = 600, ends = 660)
        assertEquals(listOf(mondayEarly, mondayLate, prayer, sunday), DeliveryBreak.weekOrdered(listOf(sunday, prayer, mondayLate, mondayEarly)))
    }

    @Test
    fun `adds, changes and deletes a break, and sets the lead`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        repeat(4) { test.reply(200, """{"open_now":true}""") }

        backend.saveBreak(DeliveryBreak.NEW.copy(label = " Freitagsgebet "))
        backend.saveBreak(prayer.copy(active = false))
        backend.deleteBreak(7)
        backend.setBreakLead(45)

        test.server.takeRequest()
        val add = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/delivery_break_save", add.url.encodedPath)
        assertEquals(
            """{"p_id":null,"p_day":5,"p_starts":765,"p_ends":840,"p_label":"Freitagsgebet","p_active":true}""",
            add.body!!.utf8(),
        )
        assertEquals(
            """{"p_id":7,"p_day":5,"p_starts":765,"p_ends":840,"p_label":"Freitagsgebet","p_active":false}""",
            test.server.takeRequest().body!!.utf8(),
        )
        val delete = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/delivery_break_delete", delete.url.encodedPath)
        assertEquals("""{"p_id":7}""", delete.body!!.utf8())
        val lead = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/set_delivery_break_lead", lead.url.encodedPath)
        assertEquals("""{"p_minutes":45}""", lead.body!!.utf8())
    }

    @Test
    fun `a break or a lead the database refuses says so`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB432","message":"a delivery break starts before it ends, on the quarter hour"}""")
        test.reply(400, """{"code":"HB432","message":"the lead before a delivery break is 0 to 120 minutes, on the five"}""")
        test.reply(400, """{"code":"HB432","message":"these are not delivery rings: {moon}"}""")
        assertFailsWith<InvalidHoursException> { backend.saveBreak(prayer.copy(starts = 770)) }
        assertFailsWith<InvalidHoursException> { backend.setBreakLead(7) }
        assertFailsWith<InvalidHoursException> { backend.close(null, what = PauseWhat.EDGE) }
    }

    @Test
    fun `a delivery refused for a pause or a break says which`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB436","message":"no delivery to this address right now"}""")
        test.reply(400, """{"code":"HB437","message":"no delivery at that time: delivery break"}""")
        assertFailsWith<DeliveryPausedException> { backend.hours() }
        assertFailsWith<DeliveryBreakException> { backend.hours() }
    }
}
