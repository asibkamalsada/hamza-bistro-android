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

/** The kitchen's cap (hamza-bistro-web#73): loadLine() and loadBar() of the site's kitchen.ts. */
class KitchenTest {
    private fun at(text: String): Instant = Instant.parse(text)

    private fun slot(at: String, capacity: Int, dishes: Int) =
        KitchenSlot(at(at), capacity, dishes, if (capacity > 0) maxOf(capacity - dishes, 0) else null)

    @Test
    fun `floors to the quarter hour on a Leipzig clock`() {
        assertEquals(at("2026-10-03T16:00:00Z"), Kitchen.slotOf(at("2026-10-03T16:14:59.999Z")))
        assertEquals(at("2026-10-03T16:15:00Z"), Kitchen.slotOf(at("2026-10-03T16:15:00Z")))
        assertEquals(at("2026-10-03T16:45:00Z"), Kitchen.slotOf(at("2026-10-03T16:59:30Z")))
        // The night the clocks go back: 02:40 summer time, then 02:40 winter time.
        assertEquals(at("2026-10-25T00:30:00Z"), Kitchen.slotOf(at("2026-10-25T00:40:00Z")))
        assertEquals(at("2026-10-25T01:30:00Z"), Kitchen.slotOf(at("2026-10-25T01:40:00Z")))
    }

    @Test
    fun `asks for the next hour from the current quarter hour`() {
        assertEquals(at("2026-10-03T16:00:00Z") to at("2026-10-03T17:00:00Z"), Kitchen.window(at("2026-10-03T16:07:00Z")))
    }

    @Test
    fun `one square per dish of the cap, filled for each taken, the rest as plus`() {
        assertEquals("■■■■□", LoadSlot(at("2026-10-03T16:00:00Z"), 4, 5).bar)
        assertEquals("□□□□□", LoadSlot(at("2026-10-03T16:00:00Z"), 0, 5).bar)
        assertEquals("■■■■■", LoadSlot(at("2026-10-03T16:00:00Z"), 5, 5).bar)
        assertEquals("■■■■■ +2", LoadSlot(at("2026-10-03T16:00:00Z"), 7, 5).bar)
        assertEquals("■■■■■■■■■■", LoadSlot(at("2026-10-03T16:00:00Z"), 10, 10).bar)
        assertEquals("7/12", LoadSlot(at("2026-10-03T16:00:00Z"), 7, 12).bar)
        assertEquals("14/12", LoadSlot(at("2026-10-03T16:00:00Z"), 14, 12).bar)
    }

    @Test
    fun `full or over is drawn in the warn colour`() {
        assertFalse(LoadSlot(at("2026-10-03T16:00:00Z"), 4, 5).full)
        assertTrue(LoadSlot(at("2026-10-03T16:00:00Z"), 5, 5).full)
        assertTrue(LoadSlot(at("2026-10-03T16:00:00Z"), 7, 5).full)
    }

    @Test
    fun `the line is four quarter hours from the current one, a missing one empty`() {
        val slots = listOf(slot("2026-10-03T16:00:00Z", 5, 4), slot("2026-10-03T16:15:00Z", 5, 6), slot("2026-10-03T16:30:00Z", 5, 0))
        val line = Kitchen.line(slots, at("2026-10-03T16:05:00Z"))
        assertEquals(
            listOf(
                LoadSlot(at("2026-10-03T16:00:00Z"), 4, 5),
                LoadSlot(at("2026-10-03T16:15:00Z"), 6, 5),
                LoadSlot(at("2026-10-03T16:30:00Z"), 0, 5),
                LoadSlot(at("2026-10-03T16:45:00Z"), 0, 5),
            ),
            line,
        )
        assertEquals(listOf("■■■■□", "■■■■■ +1", "□□□□□", "□□□□□"), line.map { it.bar })
    }

    @Test
    fun `no line while the cap is off or nothing was read`() {
        assertEquals(emptyList(), Kitchen.line(listOf(slot("2026-10-03T16:00:00Z", 0, 3)), at("2026-10-03T16:05:00Z")))
        assertEquals(emptyList(), Kitchen.line(emptyList(), at("2026-10-03T16:05:00Z")))
        assertEquals(emptyList(), Kitchen.line(null, at("2026-10-03T16:05:00Z")))
    }

    @Test
    fun `offers the site's choices, keeping a saved one that is not among them`() {
        assertEquals(listOf(0, 3, 4, 5, 6, 8, 10, 12, 15), Kitchen.choices(5))
        assertEquals(listOf(0, 3, 4, 5, 6, 7, 8, 10, 12, 15), Kitchen.choices(7))
        assertEquals(listOf(0, 3, 4, 5, 6, 8, 10, 12, 15, 40), Kitchen.choices(40))
    }

    @Test
    fun `reads kitchen_slots' rows`() {
        val rows =
            Kitchen.parse(
                """[{"slot":"2026-10-03T16:00:00+00:00","capacity":5,"dishes":4,"free":1},""" +
                    """{"slot":"2026-10-03T16:15:00+00:00","capacity":0,"dishes":2,"free":null}]"""
            )
        assertEquals(listOf(KitchenSlot(at("2026-10-03T16:00:00Z"), 5, 4, 1), KitchenSlot(at("2026-10-03T16:15:00Z"), 0, 2, null)), rows)
    }
}

/** set_dishes_per_slot(), kitchen_slots() and HB457 over PostgREST. */
class KitchenBackendTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseShopBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val settings =
        """{"auto_decline_minutes":10,"auto_decline_preorder_lead_minutes":30,"updated_at":"2026-10-03T16:00:00+00:00",""" +
            """"updated_by":"chef@example.com","busy_extra_minutes":0,"busy_until":null,"busy_set_at":null,"busy_set_by":null,""" +
            """"dishes_per_slot":5}"""

    @Test
    fun `reads the cap with the settings and saves it`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, settings)
        test.reply(200, settings.replace("\"dishes_per_slot\":5", "\"dishes_per_slot\":0"))

        assertEquals(5, backend.settings()!!.dishesPerSlot)
        assertEquals(0, backend.setDishesPerSlot(0).dishesPerSlot)

        test.server.takeRequest()
        test.server.takeRequest()
        val save = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/set_dishes_per_slot", save.url.encodedPath)
        assertEquals("""{"p_dishes":0}""", save.body!!.utf8())
    }

    @Test
    fun `settings from a database without the cap have none`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, settings.replace(",\"dishes_per_slot\":5", ""))
        assertNull(backend.settings()!!.dishesPerSlot)
    }

    @Test
    fun `a cap the database refuses says so`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB432","message":"dishes per quarter hour are 0 (off) to 50"}""")
        assertFailsWith<InvalidSettingException> { backend.setDishesPerSlot(51) }
    }

    @Test
    fun `reads the slots for a window`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"slot":"2026-10-03T16:00:00+00:00","capacity":5,"dishes":4,"free":1}]""")

        val slots = backend.kitchenSlots(Instant.parse("2026-10-03T16:00:00Z"), Instant.parse("2026-10-03T17:00:00Z"))
        assertEquals(listOf(KitchenSlot(Instant.parse("2026-10-03T16:00:00Z"), 5, 4, 1)), slots)

        test.server.takeRequest()
        val read = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/kitchen_slots", read.url.encodedPath)
        assertEquals("""{"p_from":"2026-10-03T16:00:00Z","p_to":"2026-10-03T17:00:00Z"}""", read.body!!.utf8())
    }

    @Test
    fun `a database without kitchen_slots answers nothing rather than failing`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.kitchen_slots"}""")
        assertNull(backend.kitchenSlots(Instant.parse("2026-10-03T16:00:00Z"), Instant.parse("2026-10-03T17:00:00Z")))
    }

    @Test
    fun `HB457 from any call is its own refusal`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB457","message":"the kitchen is full at that time"}""")
        assertFailsWith<KitchenFullException> {
            backend.setDishesPerSlot(5)
        }
    }
}
