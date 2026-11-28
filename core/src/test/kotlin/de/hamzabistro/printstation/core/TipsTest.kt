package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** Tips paid on the card (20261128110000_card_tips.sql in hamza-bistro-web). */
class TipsTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val staff = SupabaseStaffBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val words =
        CashUpWords(
            title = "Kassensturz Sa., 28.11.2026",
            delivered = "{n} geliefert · {total}",
            cash = "Bar",
            card = "Karte",
            online = "Online",
            unknown = "unbekannt",
            nobody = "ohne Zuordnung",
            pickup = "Abholung",
            empty = "An diesem Tag wurde nichts geliefert.",
        )

    private fun eur(amount: Double) = CashUpText.euro(amount)

    @Test
    fun `reads the tip from what S-POS charged, in cents, at most 50 euros on top`() {
        assertEquals(2.5, Tips.from("25", 22.5))
        assertEquals(2.5, Tips.from(" 25,00 € ", 22.5))
        assertEquals(0.0, Tips.from("22.50", 22.5))
        // Doubles would make 0.20000000000000107 of this: cents never do.
        assertEquals(0.2, Tips.from("10,30", 10.1))
        assertEquals(50.0, Tips.from("72,50", 22.5))
        assertNull(Tips.from("72,51", 22.5))
        assertNull(Tips.from("20", 22.5))
        assertNull(Tips.from("25,005", 22.5))
        assertNull(Tips.from("", 22.5))
        assertNull(Tips.from("-1", 22.5))
        assertEquals("22,50", Tips.start(22.5))
    }

    @Test
    fun `the step to delivered carries the tip with Karte, and only with Karte`() {
        val out = order("a", status = OrderStatus.ON_THE_WAY)
        val two = CardTerminal(2, "S-POS 2")
        assertEquals(OrderStep.Done(PaymentMethod.CARD, "Ali", 2, 2.5), Payment.done(out, PaymentMethod.CARD, "Ali", two, 2.5))
        assertEquals(OrderStep.Done(PaymentMethod.CARD, "Ali", 2, null), Payment.done(out, PaymentMethod.CARD, "Ali", two, 0.0))
        assertEquals(OrderStep.Done(PaymentMethod.CASH, "Ali", null, null), Payment.done(out, PaymentMethod.CASH, "Ali", two, 2.5))
        val online = out.copy(paymentMethod = PaymentMethod.ONLINE)
        assertEquals(OrderStep.Done(null, "Ali", null, null), Payment.done(online, PaymentMethod.CARD, "Ali", two, 2.5))
    }

    @Test
    fun `sends the tip with the step, and nothing about it otherwise`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        repeat(3) { test.reply(200, """[{"id":"a"}]""") }
        val out = order("a", status = OrderStatus.ON_THE_WAY)
        staff.move(out, OrderStep.Done(PaymentMethod.CARD, "Ali", 2, 2.5))
        staff.move(out, OrderStep.Done(PaymentMethod.CARD, "Ali", 2))
        // Never with cash, whatever the step says.
        staff.move(out, OrderStep.Done(PaymentMethod.CASH, "Ali", null, 2.5))

        test.server.takeRequest()
        assertEquals(
            """{"status":"delivered","payment_method":"card","card_terminal_id":2,"tip":2.5,"delivered_device":"Ali"}""",
            test.server.takeRequest().body!!.utf8(),
        )
        assertEquals(
            """{"status":"delivered","payment_method":"card","card_terminal_id":2,"delivered_device":"Ali"}""",
            test.server.takeRequest().body!!.utf8(),
        )
        assertEquals("""{"status":"delivered","payment_method":"cash","delivered_device":"Ali"}""", test.server.takeRequest().body!!.utf8())
    }

    @Test
    fun `pays at the counter with the tip, and as before without one`() {
        val device = CounterDevice("device-0001", "Tablet", "Samsung", "Tab", "1")
        assertEquals("1.5", CounterSales.payBody("s-1", CounterPayment.CARD, device, 2, 1.5)["p_tip"].toString())
        assertFalse(CounterSales.payBody("s-1", CounterPayment.CARD, device, 2).containsKey("p_tip"))
        assertFalse(CounterSales.payBody("s-1", CounterPayment.CARD, device, 2, 0.0).containsKey("p_tip"))
        assertFalse(CounterSales.payBody("s-1", CounterPayment.CASH, device, null, 1.5).containsKey("p_tip"))
        assertEquals(CounterError.TIP, CounterError.of("HB507"))
        val paid = json.decodeFromString(CounterSale.serializer(), """{"id":"s-1","number":7,"tip":1.5}""")
        assertEquals(1.5, paid.tip)
    }

    private val answer =
        """{"day":"2026-11-28",
            "totals":{"count":2,"cash":7.0,"card":22.0,"online":0,"unknown":0,"total":29.0,"tips":2.0,"tip_count":1},
            "card_terminals":[
              {"id":1,"name":"S-POS 1","tid":null,"active":true,"count":1,"stornos":0,"card":22.0,"tips":2.0}
            ],
            "drivers":[
              {"delivered_by":"u-1","email":"ali@example.com","device":"Ali","telegram":null,"label":"Ali",
               "count":2,"cash":7.0,"card":22.0,"online":0,"unknown":0,"total":29.0,"tips":2.0,"tip_count":1,
               "orders":[
                 {"id":"a","order_number":41,"delivered_at":null,"total":20.0,"payment_method":"card","card_terminal_id":1,"tip":2.0,"pickup":false},
                 {"id":"c","order_number":43,"delivered_at":null,"total":9.0,"payment_method":"cash","tip":0,"pickup":false}
               ]}
            ]}"""

    @Test
    fun `the Kassensturz has the tips in Karte, out of Bar, and what each person is paid out`() {
        val cashUp = json.decodeFromString(CashUp.serializer(), answer)
        assertEquals(2.0, cashUp.totals.tips)
        assertEquals(2.0, cashUp.cardTerminals[0].tips)
        assertEquals(2.0, cashUp.drivers[0].orders[0].tip)
        val terminals = listOf(CardTerminal(1, "S-POS 1"))
        assertEquals(
            "Bar ${eur(7.0)} · Karte ${eur(22.0)} (davon ${eur(2.0)} Trinkgeld)",
            CashUpText.split(cashUp.totals, words),
        )
        assertEquals("Trinkgeld bar auszahlen: ${eur(2.0)}", CashUpText.tipsOut(cashUp.drivers[0], words))
        assertNull(CashUpText.tipsOut(CashUpTotals(cash = 3.0), words))
        assertEquals(
            "#41 · – · Karte · S-POS 1 · Trinkgeld ${eur(2.0)} · ${eur(20.0)}",
            CashUpText.order(cashUp.drivers[0].orders[0], words, terminals = terminals),
        )
        assertEquals("#43 · – · Bar · ${eur(9.0)}", CashUpText.order(cashUp.drivers[0].orders[1], words))
        val shared = CashUpText.share(cashUp, words, terminals = terminals)
        assertEquals(
            true,
            shared.contains("Ali: 2 geliefert · ${eur(29.0)}\nBar ${eur(7.0)} · Karte ${eur(22.0)} (davon ${eur(2.0)} Trinkgeld)\nTrinkgeld bar auszahlen: ${eur(2.0)}\n"),
            shared,
        )
        assertEquals(true, shared.contains("S-POS 1: ${eur(22.0)} (davon ${eur(2.0)} Trinkgeld)"), shared)

        // A server before the tips: none, and the Kassensturz reads as before.
        val before = json.decodeFromString(CashUp.serializer(), """{"day":"2026-11-27","totals":{"count":1,"cash":5.0}}""")
        assertEquals(0.0, before.totals.tips)
        assertEquals("Bar ${eur(5.0)} · Karte ${eur(0.0)}", CashUpText.split(before.totals, words))
    }

    @Test
    fun `the report reads the tips, and none from a server before them`() {
        val report =
            json.decodeFromString(
                Report.serializer(),
                """{"from":"2026-11-28","to":"2026-11-28","days":1,"tips":{"orders":2,"amount":4.5},
                    "card_terminals":[{"card_terminal_id":1,"name":"S-POS 1","tid":null,"orders":2,"amount":24.5,"tips":4.5}]}""",
            )
        assertEquals(ReportTips(2, 4.5), report.tips)
        assertEquals(4.5, report.cardTerminals[0].tips)
        val before = json.decodeFromString(Report.serializer(), """{"from":"2026-11-28","to":"2026-11-28","days":1}""")
        assertEquals(ReportTips(), before.tips)
    }
}
