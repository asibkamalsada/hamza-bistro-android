package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Online payment (hamza-bistro-web#82): held at checkout, taken at the handover. */
class OnlinePaymentTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val staff = SupabaseStaffBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val lostAt = at("2026-11-25T18:20:00Z")
    private val out = order("a", status = OrderStatus.ON_THE_WAY)
    private val held = out.copy(paymentMethod = PaymentMethod.ONLINE)
    private val lost = out.copy(onlineLostAt = lostAt)

    private fun read(tail: String) =
        json.decodeFromString(
            StaffOrder.serializer(),
            """{"id":"a","order_number":57,"created_at":"2026-11-25T18:00:00+00:00","status":"new"$tail}""",
        )

    @Test
    fun `reads when the hold was lost, null, or not there at all`() {
        assertEquals(lostAt, read(""","payment_method":null,"online_lost_at":"2026-11-25T18:20:00+00:00"""").onlineLostAt)
        assertNull(read(""","payment_method":"online","online_lost_at":null""").onlineLostAt)
        assertNull(read("").onlineLostAt)
    }

    @Test
    fun `the queue reads it on top of the route, and without it on a database from before`() = runBlocking<Unit> {
        assertEquals("${StaffOrder.ROUTE_COLUMNS},online_lost_at", StaffOrder.ONLINE_COLUMNS)
        val row = """[{"id":"a","order_number":57,"created_at":"2026-11-25T18:00:00+00:00","status":"new"}]"""
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"42703","message":"column orders.online_lost_at does not exist"}""")
        test.reply(200, row)
        test.reply(200, row)

        assertNull(staff.openOrders().single().onlineLostAt)
        staff.openOrders()

        test.server.takeRequest()
        assertEquals(StaffOrder.ONLINE_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.ROUTE_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.ROUTE_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
    }

    @Test
    fun `an open order says nothing is collected while it is held, and that it is once that was lost`() {
        for (status in listOf(OrderStatus.NEW, OrderStatus.CONFIRMED, OrderStatus.ON_THE_WAY)) {
            assertEquals(OnlineNotice.HELD, Payment.online(held.copy(status = status)))
            assertEquals(OnlineNotice.LOST, Payment.online(lost.copy(status = status)))
            assertNull(Payment.online(out.copy(status = status)))
        }
        for (status in listOf(OrderStatus.DELIVERED, OrderStatus.CANCELLED)) {
            assertNull(Payment.online(held.copy(status = status)))
            assertNull(Payment.online(lost.copy(status = status)))
        }
        // The method decides, as on the site.
        assertEquals(OnlineNotice.HELD, Payment.online(held.copy(onlineLostAt = lostAt)))
    }

    @Test
    fun `no call for a new customer whose money is held, and one again once it is not`() {
        val first = order("a", status = OrderStatus.NEW).copy(returningCustomer = false)
        assertTrue(first.newCustomer)
        assertFalse(first.copy(paymentMethod = PaymentMethod.ONLINE).newCustomer)
        assertTrue(first.copy(onlineLostAt = lostAt).newCustomer)
    }

    @Test
    fun `a lost hold asks Bar or Karte as usual, and sends it`() = runBlocking<Unit> {
        assertTrue(Payment.asks(lost))
        assertEquals(OrderStep.Done(PaymentMethod.CASH, "Ali"), Payment.done(lost, PaymentMethod.CASH, "Ali"))
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"id":"a"}]""")
        staff.move(lost, Payment.done(lost, PaymentMethod.CASH, "Ali"))
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertNull(request.url.queryParameter("payment_method"))
        assertEquals("""{"status":"delivered","payment_method":"cash","delivered_device":"Ali"}""", request.body!!.utf8())
    }

    @Test
    fun `an order held online is handed over without a method, and only while it is held`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"id":"a"}]""")
        test.reply(200, """[{"id":"a"}]""")

        staff.move(held, Payment.done(held, null, "Ali"))
        // Nothing but the handover depends on the hold.
        staff.move(held, OrderStep.Cancel(CancelReason.NO_SHOW))

        test.server.takeRequest()
        val done = test.server.takeRequest()
        assertEquals("eq.on_the_way", done.url.queryParameter("status"))
        assertEquals("eq.online", done.url.queryParameter("payment_method"))
        assertEquals("""{"status":"delivered","delivered_device":"Ali"}""", done.body!!.utf8())
        assertNull(test.server.takeRequest().url.queryParameter("payment_method"))
    }

    @Test
    fun `a hold lost before the handover arrives is said, and nothing is recorded`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "[]")
        test.reply(200, """[{"status":"on_the_way","payment_method":null}]""")

        assertFailsWith<OnlineHoldLostException> { staff.move(held, OrderStep.Done()) }

        test.server.takeRequest()
        test.server.takeRequest()
        val look = test.server.takeRequest()
        assertEquals("GET", look.method)
        assertEquals("eq.a", look.url.queryParameter("id"))
        assertEquals("status,payment_method", look.url.queryParameter("select"))
    }

    @Test
    fun `an order another phone handed over, or that is gone, has only moved on`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        for (now in listOf("""[{"status":"delivered","payment_method":"online"}]""", """[{"status":"cancelled","payment_method":null}]""", "[]")) {
            test.reply(200, "[]")
            test.reply(200, now)
            assertFailsWith<OrderMovedException> { staff.move(held, OrderStep.Done()) }
        }
        // One paid at the door is not looked up at all.
        test.reply(200, "[]")
        assertFailsWith<OrderMovedException> { staff.move(out, OrderStep.Done(PaymentMethod.CASH)) }
    }

    @Test
    fun `only the last step of an order held online needs the hold`() {
        assertTrue(Payment.needsHold(held, OrderStep.Done()))
        assertFalse(Payment.needsHold(held, OrderStep.Out))
        assertFalse(Payment.needsHold(out, OrderStep.Done()))
        assertFalse(Payment.needsHold(lost, OrderStep.Done(PaymentMethod.CARD)))
    }
}
