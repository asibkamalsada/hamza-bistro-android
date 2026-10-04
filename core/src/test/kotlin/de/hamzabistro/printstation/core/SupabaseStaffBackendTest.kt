package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SupabaseStaffBackendTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseStaffBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    /** The refresh the first call makes, taken off the server's list. */
    private fun signedIn() {
        test.token("access-1", "refresh-1")
    }

    private val row =
        """{"id":"a","order_number":57,"created_at":"2026-09-26T16:00:00.123456+00:00",""" +
            """"confirmed_at":null,"scheduled_for":null,"customer_name":"Erika","phone":"0341 1",""" +
            """"address":"Karl-Heine-Str. 12, 04229 Leipzig","street":"Karl-Heine-Str. 12","postal_code":"04229",""" +
            """"city":"Leipzig","address_note":null,"notes":"ohne Zwiebeln",""" +
            """"items":[{"id":1,"name":"Döner","options":["scharf"],"price":7.5,"qty":2},{"id":3,"name":"Cola","price":2.25,"deposit":0.25,"qty":1}],""" +
            """"total":19.75,"status":"new","eta_minutes":null,"cancel_reason":null,"returning_customer":false,""" +
            """"delivery_fee":2.5,"small_order_fee":0,"discount":0,"discount_code":null,"pickup":false,""" +
            """"pickup_discount":0,"stamp_discount":0,"delivery_zone":"near","delivery_zone_source":"geocoded",""" +
            """"printed_at":null,"some_new_column":true}"""

    @Test
    fun `reads the open orders as the signed-in account, in what the site sends`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, "[$row]")

        val orders = backend.openOrders()
        val order = orders.single()
        assertEquals(57, order.orderNumber)
        assertEquals(OrderStatus.NEW, order.status)
        assertEquals(at("2026-09-26T16:00:00.123456Z"), order.createdAt)
        assertEquals(listOf("scharf"), order.items[0].options)
        assertEquals(0.25, order.depositTotal)
        assertEquals(2.5, order.fees)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/orders", request.url.encodedPath)
        assertEquals("in.(new,confirmed,on_the_way)", request.url.queryParameter("status"))
        assertEquals(StaffOrder.POINT_COLUMNS, request.url.queryParameter("select"))
        assertEquals("Bearer access-1", request.headers["Authorization"])
    }

    @Test
    fun `reads the queue without kitchen_slot on a database without the cap, and keeps to that`() = runBlocking<Unit> {
        signedIn()
        test.reply(400, """{"code":"42703","message":"column orders.kitchen_slot does not exist"}""")
        test.reply(200, "[$row]")
        test.reply(200, "[$row]")

        assertEquals(null, backend.openOrders().single().kitchenSlot)
        backend.openOrders()

        test.server.takeRequest()
        assertEquals(StaffOrder.POINT_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.COLUMNS, test.server.takeRequest().url.queryParameter("select"))
    }

    @Test
    fun `reads where each address is, and the queue without it on a database before coordinates`() = runBlocking<Unit> {
        signedIn()
        val located = row.replace("\"printed_at\":null", "\"printed_at\":null,\"lat\":51.34001,\"lon\":12.3")
        test.reply(200, "[$located]")
        test.reply(400, """{"code":"42703","message":"column orders.lat does not exist"}""")
        test.reply(200, "[$row]")
        test.reply(200, "[$row]")

        assertEquals(GeoPoint(51.34001, 12.3), backend.openOrders().single().point)
        assertNull(backend.openOrders().single().point)
        backend.openOrders()

        test.server.takeRequest()
        assertEquals(StaffOrder.POINT_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        // A database without the coordinates steps down to the columns of before, and stays there.
        assertEquals(StaffOrder.POINT_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.SOURCE_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.SOURCE_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
    }

    @Test
    fun `reads the queue without no_shows_before on a database before no-shows, and keeps to that`() = runBlocking<Unit> {
        signedIn()
        test.reply(400, """{"code":"42703","message":"column orders.no_shows_before does not exist"}""")
        test.reply(200, "[$row]")
        test.reply(200, "[$row]")

        val order = backend.openOrders().single()
        assertNull(order.noShowsBefore)
        assertEquals(false, NoShows.canMark(order.copy(status = OrderStatus.ON_THE_WAY)))
        backend.openOrders()

        test.server.takeRequest()
        assertEquals(StaffOrder.POINT_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.QUEUE_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.QUEUE_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
    }

    @Test
    fun `reads the queue without source columns on a database before phone orders, and keeps to that`() = runBlocking<Unit> {
        signedIn()
        test.reply(400, """{"code":"42703","message":"column orders.source does not exist"}""")
        test.reply(200, "[$row]")
        test.reply(200, "[$row]")

        val order = backend.openOrders().single()
        assertEquals(OrderSource.WEB, order.source)
        assertEquals(false, order.dineIn)
        assertEquals(false, order.feesWaived)
        // A web order still says "Neuer Kunde" as before.
        assertTrue(order.newCustomer)
        backend.openOrders()

        test.server.takeRequest()
        assertEquals(StaffOrder.POINT_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.NO_SHOW_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.NO_SHOW_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
    }

    @Test
    fun `a database without no-shows lacks the source columns too, and is read with the queue's columns`() = runBlocking<Unit> {
        signedIn()
        test.reply(400, """{"code":"42703","message":"column orders.source does not exist"}""")
        test.reply(400, """{"code":"42703","message":"column orders.no_shows_before does not exist"}""")
        test.reply(200, "[$row]")

        backend.openOrders()

        test.server.takeRequest()
        assertEquals(StaffOrder.POINT_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.NO_SHOW_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.QUEUE_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
    }

    @Test
    fun `reads a phone order, its source, eaten here, fees waived, and never a new customer`() = runBlocking<Unit> {
        signedIn()
        val phone =
            row.replace(""""status":"new"""", """"status":"confirmed"""")
                .replace(""""printed_at":null,""", """"printed_at":null,"source":"counter","dine_in":true,"fees_waived":true,""")
        test.reply(200, "[$phone]")

        val order = backend.openOrders().single()
        assertEquals("counter", order.source)
        assertEquals(OrderSource.COUNTER, OrderSource.of(order.source))
        assertTrue(order.dineIn)
        assertTrue(order.feesWaived)
        assertTrue(order.enteredByStaff)
        // returning_customer is false on every email-less order: no flag for it.
        assertEquals(false, order.returningCustomer)
        assertEquals(false, order.newCustomer)
    }

    @Test
    fun `reads the account's no-shows and a no_show reason`() = runBlocking<Unit> {
        signedIn()
        val flagged = row.replace(""""printed_at":null,""", """"printed_at":null,"no_shows_before":1,""")
        val noShow = row.replace(""""id":"a",""", """"id":"b",""").replace(""""status":"new"""", """"status":"cancelled"""")
            .replace(""""cancel_reason":null""", """"cancel_reason":"no_show"""")
        test.reply(200, "[$flagged,$noShow]")

        val (first, second) = backend.openOrders()
        assertEquals(1, first.noShowsBefore)
        assertEquals(1, NoShows.flag(first))
        assertEquals(CancelReason.NO_SHOW, second.cancelReason)
        assertTrue(NoShows.canReset(second))
    }

    @Test
    fun `cancels as not met at the door, and HB466 reads as moved on`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, """[{"id":"a"}]""")
        test.reply(400, """{"code":"HB466","message":"order 57 cannot be cancelled as not met at the door from delivered"}""")

        val out = order("a", status = OrderStatus.ON_THE_WAY)
        backend.move(out, OrderStep.Cancel(CancelReason.NO_SHOW))
        assertFailsWith<OrderMovedException> { backend.move(out, OrderStep.Cancel(CancelReason.NO_SHOW)) }

        test.server.takeRequest()
        val cancel = test.server.takeRequest()
        assertEquals("eq.on_the_way", cancel.url.queryParameter("status"))
        assertEquals("""{"status":"cancelled","cancel_reason":"no_show"}""", cancel.body!!.utf8())
    }

    @Test
    fun `resets an account's no-shows and says what it had`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, "1")
        test.reply(400, """{"code":"P0002","message":"no account is known for order a"}""")
        test.reply(403, """{"code":"42501","message":"staff only"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.reset_no_shows"}""")

        val order = order("a", status = OrderStatus.CANCELLED)
        assertEquals(1, backend.resetNoShows(order))
        assertFailsWith<NoShowAccountGoneException> { backend.resetNoShows(order) }
        assertFailsWith<NotAllowedException> { backend.resetNoShows(order) }
        val update = assertFailsWith<NeedsServerUpdateException> { backend.resetNoShows(order) }
        assertEquals(NoShows.MIGRATION, update.migration)

        test.server.takeRequest()
        val reset = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/reset_no_shows", reset.url.encodedPath)
        assertEquals("""{"p_order_id":"a"}""", reset.body!!.utf8())
    }

    @Test
    fun `moves an order only from where this device saw it`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, """[{"id":"a"}]""")
        test.reply(200, """[{"id":"a"}]""")

        val seen = order("a", status = OrderStatus.NEW, confirmedAt = null)
        backend.move(seen, OrderStep.Accept(30))
        backend.move(seen.copy(status = OrderStatus.CONFIRMED), OrderStep.Cancel(null))

        test.server.takeRequest()
        val accept = test.server.takeRequest()
        assertEquals("PATCH", accept.method)
        assertEquals("eq.a", accept.url.queryParameter("id"))
        assertEquals("eq.new", accept.url.queryParameter("status"))
        assertEquals("return=representation", accept.headers["Prefer"])
        assertEquals("""{"status":"confirmed","eta_minutes":30}""", accept.body!!.utf8())
        val cancel = test.server.takeRequest()
        assertEquals("eq.confirmed", cancel.url.queryParameter("status"))
        assertEquals("""{"status":"cancelled","cancel_reason":null}""", cancel.body!!.utf8())
    }

    @Test
    fun `says so when the order had moved on, or the database refused the step`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, "[]")
        test.reply(400, """{"code":"HB412","message":"order 57 cannot go from delivered to on_the_way"}""")

        val seen = order("a")
        assertFailsWith<OrderMovedException> { backend.move(seen, OrderStep.Out) }
        assertFailsWith<OrderMovedException> { backend.move(seen, OrderStep.Out) }
    }

    @Test
    fun `reads the delay, and an order from before it existed as never delayed`() = runBlocking<Unit> {
        signedIn()
        val delayed = row.replace(""""printed_at":null,""", """"printed_at":null,"delay_minutes":10,"delayed_at":"2026-09-26T16:40:00+00:00",""")
        test.reply(200, "[$delayed,$row]")
        val (first, second) = backend.openOrders()
        assertEquals(10, first.delayMinutes)
        assertEquals(at("2026-09-26T16:40:00Z"), first.delayedAt)
        assertEquals(0, second.delayMinutes)
        assertNull(second.delayedAt)
        assertTrue("delay_minutes" in StaffOrder.COLUMNS.split(","))
    }

    @Test
    fun `delays by the minutes tapped, for the database to add to what is there`() = runBlocking<Unit> {
        signedIn()
        val answer = """{"id":"a","order_number":57,"delay_minutes":20,"delayed_at":"2026-09-26T16:40:00+00:00"}"""
        test.reply(200, answer)
        test.reply(200, answer)

        // Already +10 here: still only the 10 tapped goes, never the 20 this device would make it.
        val seen = order("a", delayMinutes = 10)
        backend.move(seen, OrderStep.Delay(10))
        backend.move(seen.copy(status = OrderStatus.ON_THE_WAY), OrderStep.Delay(20))

        test.server.takeRequest()
        val first = test.server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/rest/v1/rpc/delay_order", first.url.encodedPath)
        assertEquals("""{"p_order_id":"a","p_minutes":10}""", first.body!!.utf8())
        assertEquals("""{"p_order_id":"a","p_minutes":20}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `says so when the order can no longer be delayed`() = runBlocking<Unit> {
        signedIn()
        test.reply(400, """{"code":"HB435","message":"order 57 is delivered: only accepted orders can be delayed"}""")
        test.reply(400, """{"code":"P0002","message":"no such order"}""")
        test.reply(500, """{"code":"XX000","message":"boom"}""")

        assertFailsWith<NotDelayableException> { backend.move(order("a"), OrderStep.Delay(10)) }
        assertFailsWith<NotDelayableException> { backend.move(order("a"), OrderStep.Delay(10)) }
        assertFailsWith<BackendException> { backend.move(order("a"), OrderStep.Delay(10)) }
    }

    @Test
    fun `delays every open order at once and says how many`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, """{"delayed":3,"order_ids":["a","b","c"]}""")
        test.reply(400, """{"code":"HB435","message":"orders can be delayed by 5 to 30 minutes, on the five"}""")
        test.reply(400, """{"code":"P0001","message":"staff only"}""")

        assertEquals(3, backend.delayOpen(15))
        assertFailsWith<InvalidSettingException> { backend.delayOpen(7) }
        assertFailsWith<NotAllowedException> { backend.delayOpen(15) }

        test.server.takeRequest()
        val all = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/delay_open_orders", all.url.encodedPath)
        assertEquals("""{"p_minutes":15}""", all.body!!.utf8())
    }

    @Test
    fun `sends the reason a customer is told`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, """[{"id":"a"}]""")
        backend.move(order("a", status = OrderStatus.NEW), OrderStep.Cancel(CancelReason.SOLD_OUT))
        test.server.takeRequest()
        assertEquals("""{"status":"cancelled","cancel_reason":"sold_out"}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `says it is on shift, and learns when it is no longer staff`() = runBlocking<Unit> {
        signedIn()
        test.reply(204)
        test.reply(400, """{"code":"P0001","message":"staff only"}""")

        val device = DeviceReport("device-1234", "Küche", "loop")
        backend.seen(device)
        assertFailsWith<NotAllowedException> { backend.seen(device) }

        test.server.takeRequest()
        val seen = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_app_seen", seen.url.encodedPath)
        assertEquals("""{"p_device":"device-1234","p_label":"Küche","p_alarm":"loop"}""", seen.body!!.utf8())
    }

    @Test
    fun `without the site's device list, asks whether it is still staff instead`() = runBlocking<Unit> {
        signedIn()
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.staff_app_seen"}""")
        test.reply(200, "true")
        test.reply(200, "true")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.staff_app_seen"}""")
        test.reply(200, "false")

        val device = DeviceReport("device-1234", "Küche", "loop")
        backend.seen(device)
        assertFailsWith<NotAllowedException> { backend.seen(device) }

        test.server.takeRequest()
        test.server.takeRequest()
        assertEquals("/rest/v1/rpc/is_staff", test.server.takeRequest().url.encodedPath)
        assertEquals("/rest/v1/rpc/claim_staff_account", test.server.takeRequest().url.encodedPath)
    }

    @Test
    fun `reads the prep times by dish`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, """[{"id":1,"prep_minutes":5},{"id":3,"prep_minutes":0}]""")
        assertEquals(mapOf(1L to 5, 3L to 0), backend.prepMinutes())
        test.server.takeRequest()
        assertEquals("id,prep_minutes", test.server.takeRequest().url.queryParameter("select"))
    }
}
