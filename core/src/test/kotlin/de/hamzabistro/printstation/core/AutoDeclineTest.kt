package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** declineTiming() of the site's orders-page.ts, and the escalation two minutes before. */
class AutoDeclineTest {
    private val deadline = at("2026-09-26T16:10:00Z")

    private fun waiting(autoDeclineAt: String? = "2026-09-26T16:10:00Z", scheduledFor: String? = null) =
        order("a", status = OrderStatus.NEW, confirmedAt = null, scheduledFor = scheduledFor)
            .copy(autoDeclineAt = autoDeclineAt?.let(::at))

    @Test
    fun `counts down to the server's deadline, red for the last two minutes`() {
        val calm = AutoDecline.countdown(waiting(), at("2026-09-26T16:04:00Z"))!!
        assertEquals(6, calm.minutesLeft)
        assertEquals(DeclineUrgency.CALM, calm.urgency)
        assertFalse(calm.showTime)

        assertEquals(DeclineUrgency.SOON, AutoDecline.countdown(waiting(), at("2026-09-26T16:06:00Z"))!!.urgency)
        // 2:00 left reads "in 2 Min." and is not yet red; 1:59 is.
        assertEquals(DeclineUrgency.SOON, AutoDecline.countdown(waiting(), at("2026-09-26T16:08:00Z"))!!.urgency)
        val red = AutoDecline.countdown(waiting(), at("2026-09-26T16:08:01Z"))!!
        assertEquals(1, red.minutesLeft)
        assertEquals(DeclineUrgency.URGENT, red.urgency)
        // Past it, and the job not round yet: any moment now.
        assertEquals(-1, AutoDecline.countdown(waiting(), at("2026-09-26T16:10:05Z"))!!.minutesLeft)
    }

    @Test
    fun `a pre-order more than an hour off shows its time instead`() {
        val pre = waiting(autoDeclineAt = "2026-09-26T19:00:00Z", scheduledFor = "2026-09-26T19:30:00Z")
        val far = AutoDecline.countdown(pre, at("2026-09-26T16:00:00Z"))!!
        assertTrue(far.showTime)
        assertEquals(at("2026-09-26T19:00:00Z"), far.deadline)
        assertFalse(AutoDecline.countdown(pre, at("2026-09-26T18:00:00Z"))!!.showTime)
    }

    @Test
    fun `nothing to count with auto-decline off, or once the order is answered`() {
        assertNull(AutoDecline.countdown(waiting(autoDeclineAt = null), at("2026-09-26T16:04:00Z")))
        assertNull(AutoDecline.countdown(waiting().copy(status = OrderStatus.CONFIRMED), at("2026-09-26T16:04:00Z")))
    }

    @Test
    fun `escalates from two minutes before until the deadline`() {
        assertFalse(AutoDecline.escalationDue(waiting(), deadline.minusSeconds(121)))
        assertTrue(AutoDecline.escalationDue(waiting(), deadline.minusSeconds(120)))
        assertTrue(AutoDecline.escalationDue(waiting(), deadline.minusSeconds(1)))
        assertFalse(AutoDecline.escalationDue(waiting(), deadline))
        assertFalse(AutoDecline.escalationDue(waiting(autoDeclineAt = null), deadline.minusSeconds(60)))
        assertFalse(AutoDecline.escalationDue(waiting().copy(status = OrderStatus.CANCELLED), deadline.minusSeconds(60)))
    }

    @Test
    fun `offers the site's choices, keeping a saved one that is not among them`() {
        assertEquals(listOf(null, 5, 10, 15, 20, 30), AutoDecline.choices(10))
        assertEquals(listOf(null, 5, 10, 15, 20, 30), AutoDecline.choices(null))
        assertEquals(listOf(null, 5, 7, 10, 15, 20, 30), AutoDecline.choices(7))
    }

    @Test
    fun `a person never gives the database's own reason`() {
        assertFalse(CancelReason.TIMEOUT in CancelReason.CHOSEN)
        // Nor the driver's no-show, nor one this app does not know: everything else is a button.
        assertEquals(CancelReason.entries - listOf(CancelReason.TIMEOUT, CancelReason.NO_SHOW, CancelReason.UNKNOWN), CancelReason.CHOSEN)
    }
}

/** shop_settings() and set_auto_decline_minutes() over PostgREST. */
class ShopSettingsTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseShopBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val settings =
        """{"auto_decline_minutes":10,"auto_decline_preorder_lead_minutes":30,"updated_at":"2026-10-02T16:00:00+00:00",""" +
            """"updated_by":"chef@example.com","busy_extra_minutes":0,"busy_until":null,"busy_set_at":null,"busy_set_by":null}"""

    @Test
    fun `reads the settings and saves the minutes, null being off`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, settings)
        test.reply(200, settings.replace("\"auto_decline_minutes\":10", "\"auto_decline_minutes\":null"))

        val read = backend.settings()!!
        assertEquals(10, read.autoDeclineMinutes)
        assertEquals(30, read.autoDeclinePreorderLeadMinutes)
        assertEquals("chef@example.com", read.updatedBy)
        assertNull(backend.setAutoDecline(null).autoDeclineMinutes)

        test.server.takeRequest()
        assertEquals("/rest/v1/rpc/shop_settings", test.server.takeRequest().url.encodedPath)
        val save = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/set_auto_decline_minutes", save.url.encodedPath)
        assertEquals("""{"p_minutes":null}""", save.body!!.utf8())
    }

    @Test
    fun `minutes the database refuses say so`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB433","message":"auto-decline minutes must be 3 to 60"}""")
        assertFailsWith<InvalidSettingException> { backend.setAutoDecline(61) }
        test.server.takeRequest()
        assertEquals("""{"p_minutes":61}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `a database without settings answers nothing rather than failing`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.shop_settings"}""")
        assertNull(backend.settings())
    }

    @Test
    fun `reads the deadline with the queue and a declined order's reason`() {
        val row =
            """{"id":"a","order_number":57,"created_at":"2026-09-26T16:00:00+00:00","status":"cancelled",""" +
                """"cancel_reason":"timeout","auto_decline_at":null}"""
        val declined = json.decodeFromString(StaffOrder.serializer(), row)
        assertEquals(CancelReason.TIMEOUT, declined.cancelReason)
        val waiting =
            json.decodeFromString(
                StaffOrder.serializer(),
                """{"id":"b","order_number":58,"created_at":"2026-09-26T16:00:00+00:00","status":"new",""" +
                    """"auto_decline_at":"2026-09-26T16:10:00.5+00:00"}""",
            )
        assertEquals(at("2026-09-26T16:10:00.5Z"), waiting.autoDeclineAt)
        assertTrue("auto_decline_at" in StaffOrder.COLUMNS.split(","))
    }
}
