package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(StaffOrder.COLUMNS, request.url.queryParameter("select"))
        assertEquals("Bearer access-1", request.headers["Authorization"])
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
