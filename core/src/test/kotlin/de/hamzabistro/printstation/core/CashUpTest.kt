package de.hamzabistro.printstation.core

import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CashUpTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val history = SupabaseHistoryBackend(test.config, test.client, sessions)
    private val staff = SupabaseStaffBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val words =
        CashUpWords(
            title = "Kassensturz Fr., 02.10.2026",
            delivered = "{n} geliefert · {total}",
            cash = "Bar",
            card = "Karte",
            online = "Online",
            unknown = "unbekannt",
            nobody = "ohne Zuordnung",
            pickup = "Abholung",
            empty = "An diesem Tag wurde nichts geliefert.",
        )

    /** What cash_up() answers, as in the issue's comment. */
    private val answer =
        """{"day":"2026-10-02",
            "totals":{"count":5,"cash":40.0,"card":30.9,"online":0,"unknown":7.5,"total":78.4},
            "drivers":[
              {"delivered_by":"u-1","email":"ali@example.com","device":"Ali","telegram":null,"label":"Ali",
               "count":2,"cash":20.0,"card":15.49,"online":0,"unknown":0,"total":35.49,
               "orders":[
                 {"id":"a","order_number":41,"delivered_at":"2026-10-02T16:32:00+00:00","total":20.0,"payment_method":"cash","pickup":false},
                 {"id":"b","order_number":44,"delivered_at":"2026-10-02T17:05:00.5+00:00","total":15.49,"payment_method":"card","pickup":true}
               ]},
              {"delivered_by":null,"email":null,"device":null,"telegram":null,"label":null,
               "count":1,"cash":0,"card":0,"online":0,"unknown":7.5,"total":7.5,
               "orders":[{"id":"c","order_number":39,"delivered_at":null,"total":7.5,"payment_method":null,"pickup":false}]}
            ]}"""

    private fun eur(amount: Double) = CashUpText.euro(amount)

    @Test
    fun `asks how it was paid on the way, unless it was paid online`() {
        assertTrue(Payment.asks(order("a", status = OrderStatus.ON_THE_WAY)))
        assertTrue(Payment.asks(order("a", status = OrderStatus.ON_THE_WAY, pickup = true)))
        assertFalse(Payment.asks(order("a", status = OrderStatus.ON_THE_WAY).copy(paymentMethod = PaymentMethod.ONLINE)))
        assertFalse(Payment.asks(order("a", status = OrderStatus.CONFIRMED)))
    }

    @Test
    fun `the last step carries Bar or Karte and the device, and never online`() {
        val out = order("a", status = OrderStatus.ON_THE_WAY)
        assertEquals(OrderStep.Done(PaymentMethod.CASH, "Ali"), Payment.done(out, PaymentMethod.CASH, "  Ali "))
        assertEquals(OrderStep.Done(PaymentMethod.CARD, null), Payment.done(out, PaymentMethod.CARD, "  "))
        assertEquals(OrderStep.Done(null, "Ali"), Payment.done(out, PaymentMethod.ONLINE, "Ali"))
        // Paid online already: the one button, and the database keeps "online".
        val online = out.copy(paymentMethod = PaymentMethod.ONLINE)
        assertEquals(OrderStep.Done(null, "Ali"), Payment.done(online, PaymentMethod.CASH, "Ali"))
        assertEquals("x".repeat(40), Payment.done(out, PaymentMethod.CASH, "x".repeat(50)).device)
    }

    @Test
    fun `sends Bar or Karte and the device label with the step to delivered`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"id":"a"}]""")
        test.reply(200, """[{"id":"a"}]""")

        val out = order("a", status = OrderStatus.ON_THE_WAY)
        staff.move(out, OrderStep.Done(PaymentMethod.CASH, "Fahrer 1"))
        staff.move(out, OrderStep.Done())

        test.server.takeRequest()
        val cash = test.server.takeRequest()
        assertEquals("PATCH", cash.method)
        assertEquals("eq.on_the_way", cash.url.queryParameter("status"))
        assertEquals("""{"status":"delivered","payment_method":"cash","delivered_device":"Fahrer 1"}""", cash.body!!.utf8())
        // Without a method nothing is said about it: online stays online.
        assertEquals("""{"status":"delivered"}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `says so when the database refuses how it was paid`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB438","message":"order 57 payment method is set when it is delivered, and only then"}""")
        assertFailsWith<PaymentLockedException> {
            staff.move(order("a", status = OrderStatus.ON_THE_WAY), OrderStep.Done(PaymentMethod.CARD, null))
        }
    }

    @Test
    fun `reads how an order was paid and when it was delivered, and an older order without either`() {
        val paid =
            json.decodeFromString(
                StaffOrder.serializer(),
                """{"id":"a","order_number":57,"created_at":"2026-10-02T16:00:00+00:00","status":"delivered",""" +
                    """"payment_method":"card","delivered_at":"2026-10-02T16:40:00+00:00"}""",
            )
        assertEquals(PaymentMethod.CARD, paid.paymentMethod)
        assertEquals(at("2026-10-02T16:40:00Z"), paid.deliveredAt)
        val older =
            json.decodeFromString(
                StaffOrder.serializer(),
                """{"id":"a","order_number":57,"created_at":"2026-10-02T16:00:00+00:00","status":"delivered","payment_method":null}""",
            )
        assertNull(older.paymentMethod)
        assertNull(older.deliveredAt)
        assertTrue(StaffOrder.COLUMNS.split(",").containsAll(listOf("payment_method", "delivered_at")))
    }

    @Test
    fun `reads the Kassensturz for a day`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer)

        val cashUp = history.cashUp(LocalDate.of(2026, 10, 2))!!
        assertEquals(LocalDate.of(2026, 10, 2), cashUp.date)
        assertEquals(CashUpTotals(5, 40.0, 30.9, 0.0, 7.5, 78.4), cashUp.totals)
        assertEquals(listOf("Ali", null), cashUp.drivers.map { it.label })
        val ali = cashUp.drivers[0]
        assertEquals(listOf(41L, 44L), ali.orders.map { it.orderNumber })
        assertEquals(PaymentMethod.CARD, ali.orders[1].paymentMethod)
        assertTrue(ali.orders[1].pickup)
        assertNull(cashUp.drivers[1].orders.single().deliveredAt)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/cash_up", request.url.encodedPath)
        assertEquals("""{"p_day":"2026-10-02"}""", request.body!!.utf8())
    }

    @Test
    fun `asks for today when no day is given`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"day":"2026-10-03","totals":{"count":0,"cash":0,"card":0,"online":0,"unknown":0,"total":0},"drivers":[]}""")

        val cashUp = history.cashUp(null)!!
        assertEquals(0, cashUp.totals.count)
        assertTrue(cashUp.drivers.isEmpty())
        test.server.takeRequest()
        assertEquals("""{"p_day":null}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `a database without the Kassensturz yet answers nothing, and a non-staff account is refused`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.cash_up(p_day)"}""")
        test.reply(403, """{"code":"42501","message":"staff only"}""")

        assertNull(history.cashUp(null))
        assertFailsWith<NotAllowedException> { history.cashUp(null) }
    }

    @Test
    fun `today is the Leipzig day`() {
        // 00:30 in Leipzig on the 3rd, still the 2nd in UTC.
        assertEquals(LocalDate.of(2026, 10, 3), CashUp.today(at("2026-10-02T22:30:00Z")))
    }

    @Test
    fun `sums read as on the site, cash and card always, the rest only when there is some`() {
        val totals = CashUpTotals(5, 40.0, 30.9, 0.0, 7.5, 78.4)
        assertEquals("5 geliefert · ${eur(78.4)}", CashUpText.totals(totals, words))
        assertEquals("Bar ${eur(40.0)} · Karte ${eur(30.9)} · unbekannt ${eur(7.5)}", CashUpText.split(totals, words))
        assertEquals("Bar ${eur(0.0)} · Karte ${eur(0.0)}", CashUpText.split(CashUpTotals(), words))
        assertEquals(
            "Bar ${eur(0.0)} · Karte ${eur(0.0)} · Online ${eur(9.0)}",
            CashUpText.split(CashUpTotals(1, online = 9.0, total = 9.0), words),
        )
        assertEquals("40,00 €", eur(40.0).replace(' ', ' '))
    }

    @Test
    fun `a driver nobody is known for is ohne Zuordnung`() {
        assertEquals("Ali", CashUpText.driver(CashUpDriver(label = "Ali"), words))
        assertEquals("ohne Zuordnung", CashUpText.driver(CashUpDriver(label = null), words))
        assertEquals("ohne Zuordnung", CashUpText.driver(CashUpDriver(label = " "), words))
    }

    @Test
    fun `shares the day as plain text, driver by driver with the orders behind the sums`() {
        val cashUp = json.decodeFromString(CashUp.serializer(), answer)
        val expected =
            """
            Kassensturz Fr., 02.10.2026
            5 geliefert · ${eur(78.4)}
            Bar ${eur(40.0)} · Karte ${eur(30.9)} · unbekannt ${eur(7.5)}

            Ali: 2 geliefert · ${eur(35.49)}
            Bar ${eur(20.0)} · Karte ${eur(15.49)}
              #41 · 18:32 · Bar · ${eur(20.0)}
              #44 · 19:05 · Karte · Abholung · ${eur(15.49)}

            ohne Zuordnung: 1 geliefert · ${eur(7.5)}
            Bar ${eur(0.0)} · Karte ${eur(0.0)} · unbekannt ${eur(7.5)}
              #39 · – · unbekannt · ${eur(7.5)}
            """
                .trimIndent()
        assertEquals(expected, CashUpText.share(cashUp, words))
    }

    @Test
    fun `a day with nothing delivered says so`() {
        val empty = CashUp(day = "2026-10-03")
        assertEquals("Kassensturz Fr., 02.10.2026\nAn diesem Tag wurde nichts geliefert.", CashUpText.share(empty, words))
    }
}
