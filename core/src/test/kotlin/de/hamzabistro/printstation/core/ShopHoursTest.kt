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

class ShopHoursTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseShopBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val now = Instant.parse("2026-10-02T10:30:00Z")

    /** What shop_hours() answers, the week included, which the app does not read. */
    private fun answer(openNow: Boolean, closures: String = "[]", extra: String = "") =
        """{"delivery":[{"day":0,"delivers":true,"opens":720,"closes":1200}],""" +
            """"closures":$closures,"open_now":$openNow,""" +
            """"open_until":${if (openNow) "\"2026-10-02T18:00:00+00:00\"" else "null"},""" +
            """"next_open":${if (openNow) "null" else "\"2026-10-02T11:10:00+00:00\""}$extra}"""

    @Test
    fun `reads where the shop stands, as the signed-in account`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer(openNow = true))

        val hours = backend.hours()!!
        assertEquals(ShopState.OPEN, hours.state(now))
        assertEquals(Instant.parse("2026-10-02T18:00:00Z"), hours.openUntil)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/shop_hours", request.url.encodedPath)
        assertEquals("Bearer access-1", request.headers["Authorization"])
    }

    @Test
    fun `a closure running now is the shop closed, and who closed it is said`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            answer(
                openNow = false,
                closures =
                    """[{"id":4,"starts_at":"2026-10-02T10:00:00+00:00","ends_at":"2026-10-02T11:10:00+00:00","by":"koch@example.com"},""" +
                        """{"id":5,"starts_at":"2026-12-24T13:00:00+00:00","ends_at":null,"by":null}]""",
            ),
        )

        val hours = backend.hours()!!
        assertEquals(ShopState.CLOSED, hours.state(now))
        assertEquals(4, hours.closureAt(now)?.id)
        assertEquals("koch@example.com", hours.closureAt(now)?.by)
        assertEquals(Instant.parse("2026-10-02T11:10:00Z"), hours.nextOpen)
    }

    @Test
    fun `outside the hours with nothing closed is neither open nor closed`() {
        val hours = ShopHours(openNow = false, nextOpen = Instant.parse("2026-10-03T09:00:00Z"))
        assertEquals(ShopState.OUTSIDE, hours.state(now))
        assertNull(hours.closureAt(now))
    }

    @Test
    fun `reads the time passed since the last read`() {
        val paused =
            ShopHours(
                openNow = false,
                nextOpen = Instant.parse("2026-10-02T11:10:00Z"),
                closures = listOf(ShopClosure(4, Instant.parse("2026-10-02T10:00:00Z"), Instant.parse("2026-10-02T11:10:00Z"))),
            )
        assertEquals(ShopState.CLOSED, paused.state(now))
        assertEquals(Instant.parse("2026-10-02T11:10:00Z"), paused.reopensAt(now))
        // The pause ran out before the next read.
        assertEquals(ShopState.OPEN, paused.state(Instant.parse("2026-10-02T11:10:05Z")))

        val open =
            ShopHours(
                openNow = true,
                openUntil = Instant.parse("2026-10-02T11:00:00Z"),
                closures = listOf(ShopClosure(5, Instant.parse("2026-10-02T11:00:00Z"), null)),
            )
        assertEquals(ShopState.OPEN, open.state(now))
        // A closure planned for eleven started since: closed, and nobody knows until when.
        val later = Instant.parse("2026-10-02T11:00:10Z")
        assertEquals(ShopState.CLOSED, open.state(later))
        assertNull(open.reopensAt(later))
        // And the day's hours ending since is simply outside them.
        assertEquals(ShopState.OUTSIDE, open.copy(closures = emptyList()).state(later))
    }

    @Test
    fun `of two closures running at once, the one without an end is the one that counts`() {
        val hours =
            ShopHours(
                openNow = false,
                closures =
                    listOf(
                        ShopClosure(1, Instant.parse("2026-10-02T10:00:00Z"), Instant.parse("2026-10-02T12:00:00Z")),
                        ShopClosure(2, Instant.parse("2026-10-02T10:15:00Z"), null),
                    ),
            )
        assertEquals(2, hours.closureAt(now)?.id)
    }

    @Test
    fun `closes until a time, or until further notice, and opens again`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer(openNow = false, extra = ""","preorders_inside":2"""))
        test.reply(200, answer(openNow = false))
        test.reply(200, answer(openNow = true))

        assertEquals(2, backend.close(Instant.parse("2026-10-02T11:10:00Z")).preordersInside)
        backend.close(null)
        assertEquals(ShopState.OPEN, backend.open().state(now))

        test.server.takeRequest()
        val pause = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/shop_close", pause.url.encodedPath)
        assertEquals("""{"p_until":"2026-10-02T11:10:00Z"}""", pause.body!!.utf8())
        val forGood = test.server.takeRequest()
        assertEquals("""{"p_until":null}""", forGood.body!!.utf8())
        assertEquals("/rest/v1/rpc/shop_open", test.server.takeRequest().url.encodedPath)
    }

    @Test
    fun `reads the week the shop delivers in`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer(openNow = true))
        assertEquals(listOf(DeliveryDay(0, delivers = true, opens = 720, closes = 1200)), backend.hours()!!.delivery)
    }

    @Test
    fun `plans a closure ahead, and takes one off the list`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer(openNow = true, extra = ""","preorders_inside":0"""))
        test.reply(200, answer(openNow = true))

        backend.close(until = Instant.parse("2026-12-26T23:00:00Z"), from = Instant.parse("2026-12-24T13:00:00Z"))
        backend.removeClosure(5)

        test.server.takeRequest()
        val plan = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/shop_close", plan.url.encodedPath)
        assertEquals("""{"p_until":"2026-12-26T23:00:00Z","p_from":"2026-12-24T13:00:00Z"}""", plan.body!!.utf8())
        val remove = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/shop_closure_delete", remove.url.encodedPath)
        assertEquals("""{"p_id":5}""", remove.body!!.utf8())
    }

    @Test
    fun `saves the whole week at once`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer(openNow = true))

        backend.saveWeek(listOf(DeliveryDay(1, true, 660, 1200), DeliveryDay(2, false, 660, 1440)))

        test.server.takeRequest()
        val save = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/set_delivery_hours", save.url.encodedPath)
        assertEquals(
            """{"p_hours":[{"day":1,"delivers":true,"opens":660,"closes":1200},{"day":2,"delivers":false,"opens":660,"closes":1440}]}""",
            save.body!!.utf8(),
        )
    }

    @Test
    fun `hours the database refuses say so`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB432","message":"a closure has to end after it starts"}""")
        assertFailsWith<InvalidHoursException> { backend.close(Instant.parse("2026-12-24T12:00:00Z"), Instant.parse("2026-12-24T13:00:00Z")) }
    }

    @Test
    fun `a day is what the table allows`() {
        assertTrue(DeliveryDay(1, true, 660, 1200).valid)
        // Until midnight at the end of the day.
        assertTrue(DeliveryDay(1, true, 1080, 1440).valid)
        assertFalse(DeliveryDay(1, true, 1200, 660).valid)
        assertFalse(DeliveryDay(1, true, 665, 1200).valid)
        assertFalse(DeliveryDay(1, true, 660, 1455).valid)
        // A day off keeps its times, and the table checks them all the same.
        assertFalse(DeliveryDay(1, false, 1200, 1200).valid)
    }

    @Test
    fun `a database without closing answers nothing rather than failing`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.shop_hours"}""")
        assertNull(backend.hours())
    }

    @Test
    fun `an account that is not staff may not close the shop`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"P0001","message":"staff only"}""")
        assertFailsWith<NotAllowedException> { backend.close(null) }
    }

    @Test
    fun `reads busy mode with the hours, and stops counting it once it ran out`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            answer(openNow = true, extra = ""","busy_extra_minutes":30,"busy_until":"2026-10-02T11:15:00+00:00","busy_by":"koch@example.com""""),
        )

        val hours = backend.hours()!!
        assertEquals(Busy(30, Instant.parse("2026-10-02T11:15:00Z"), "koch@example.com"), hours.busyAt(now))
        assertEquals(30, hours.busyMinutes(now))
        // Over by itself, without waiting for the next read.
        assertNull(hours.busyAt(Instant.parse("2026-10-02T11:15:00Z")))
        assertEquals(0, hours.busyMinutes(Instant.parse("2026-10-02T11:20:00Z")))
    }

    @Test
    fun `busy mode off, or from a database without it, is no minutes`() {
        assertEquals(0, ShopHours(openNow = true).busyMinutes(now))
        // Off, as the database says it: 0 and no end.
        assertNull(ShopHours(openNow = true, busyExtraMinutes = 0, busyUntil = null).busyAt(now))
    }

    @Test
    fun `switches busy mode on for a while or the rest of the day, and back to normal`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        repeat(4) { test.reply(200, answer(openNow = true)) }

        backend.busy(30, java.time.Duration.ofMinutes(30))
        backend.busy(15, java.time.Duration.ofHours(1))
        backend.busy(45, null)
        backend.notBusy()

        test.server.takeRequest()
        val half = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/shop_busy", half.url.encodedPath)
        assertEquals("""{"p_minutes":30,"p_for":"PT30M"}""", half.body!!.utf8())
        assertEquals("""{"p_minutes":15,"p_for":"PT1H"}""", test.server.takeRequest().body!!.utf8())
        assertEquals("""{"p_minutes":45,"p_for":null}""", test.server.takeRequest().body!!.utf8())
        assertEquals("/rest/v1/rpc/shop_not_busy", test.server.takeRequest().url.encodedPath)
    }

    @Test
    fun `busy mode the database refuses says so`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB434","message":"busy minutes must be 5 to 60 in steps of 5"}""")
        assertFailsWith<InvalidSettingException> { backend.busy(7, null) }
    }
}
