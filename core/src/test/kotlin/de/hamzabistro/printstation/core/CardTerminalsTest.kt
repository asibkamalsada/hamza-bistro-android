package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject

/** The S-POS phones (20261128100000_card_terminals.sql in hamza-bistro-web). */
class CardTerminalsTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val staff = SupabaseStaffBackend(test.config, test.client, sessions)
    private val terminals = SupabaseCardTerminalBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val one = CardTerminal(1, "S-POS 1", tid = "29001234", position = 1)
    private val two = CardTerminal(2, "S-POS 2", position = 2)
    private val old = CardTerminal(3, "S-POS alt", active = false, position = 0)
    private val four = CardTerminal(4, "S-POS 4", position = 4)
    private val list = listOf(four, old, two, one)

    private val words =
        CashUpWords(
            title = "Kassensturz Fr., 27.11.2026",
            delivered = "{n} geliefert · {total}",
            cash = "Bar",
            card = "Karte",
            online = "Online",
            unknown = "unbekannt",
            nobody = "ohne Zuordnung",
            pickup = "Abholung",
            empty = "An diesem Tag wurde nichts geliefert.",
            perTerminal = "Karte je S-POS",
            terminalOff = "nicht mehr in Gebrauch",
        )

    private fun eur(amount: Double) = CashUpText.euro(amount)

    // ---------------------------------------------------------------------
    // What "Karte" does
    // ---------------------------------------------------------------------

    @Test
    fun `offers the phones in use, in the list's order`() {
        assertEquals(listOf(one, two, four), CardTerminals.offered(list))
    }

    @Test
    fun `asks no phone without a list, one tap on the usual phone, and a pick otherwise`() {
        assertEquals(CardChoice.NoList, CardTerminals.choice(emptyList(), null))
        assertEquals(CardChoice.NoList, CardTerminals.choice(listOf(old), old.id))
        assertEquals(CardChoice.Ask(listOf(one, two, four)), CardTerminals.choice(list, null))
        assertEquals(CardChoice.Usual(two, listOf(one, four)), CardTerminals.choice(list, 2))
        // Its usual phone switched off, or gone: the device asks again.
        assertEquals(CardChoice.Ask(listOf(one, two, four)), CardTerminals.choice(list, old.id))
        assertEquals(CardChoice.Ask(listOf(one, two, four)), CardTerminals.choice(list, 99))
    }

    @Test
    fun `names the usual phone on the button`() {
        assertEquals("Karte · S-POS 2", CardTerminals.cardLabel("Karte", CardTerminals.choice(list, 2)))
        assertEquals("Karte", CardTerminals.cardLabel("Karte", CardTerminals.choice(list, null)))
        assertEquals("Karte", CardTerminals.cardLabel("Karte", CardChoice.NoList))
        assertEquals("S-POS alt", CardTerminals.name(list, 3))
        assertNull(CardTerminals.name(list, null))
        assertNull(CardTerminals.name(list, 99))
    }

    // ---------------------------------------------------------------------
    // At the door
    // ---------------------------------------------------------------------

    @Test
    fun `the step to delivered carries the phone with Karte, and only with Karte`() {
        val out = order("a", status = OrderStatus.ON_THE_WAY)
        assertEquals(OrderStep.Done(PaymentMethod.CARD, "Ali", 2), Payment.done(out, PaymentMethod.CARD, "Ali", two))
        assertEquals(OrderStep.Done(PaymentMethod.CARD, "Ali", null), Payment.done(out, PaymentMethod.CARD, "Ali"))
        assertEquals(OrderStep.Done(PaymentMethod.CASH, "Ali", null), Payment.done(out, PaymentMethod.CASH, "Ali", two))
        // Paid online: the one button, no method, no phone.
        val online = out.copy(paymentMethod = PaymentMethod.ONLINE)
        assertEquals(OrderStep.Done(null, "Ali", null), Payment.done(online, PaymentMethod.CARD, "Ali", two))
    }

    @Test
    fun `sends the phone as card_terminal_id, and nothing about it otherwise`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        repeat(3) { test.reply(200, """[{"id":"a"}]""") }
        val out = order("a", status = OrderStatus.ON_THE_WAY)
        staff.move(out, OrderStep.Done(PaymentMethod.CARD, "Ali", 2))
        staff.move(out, OrderStep.Done(PaymentMethod.CARD, "Ali"))
        // Never with cash, whatever the step says.
        staff.move(out, OrderStep.Done(PaymentMethod.CASH, "Ali", 2))

        test.server.takeRequest()
        assertEquals(
            """{"status":"delivered","payment_method":"card","card_terminal_id":2,"delivered_device":"Ali"}""",
            test.server.takeRequest().body!!.utf8(),
        )
        assertEquals("""{"status":"delivered","payment_method":"card","delivered_device":"Ali"}""", test.server.takeRequest().body!!.utf8())
        assertEquals("""{"status":"delivered","payment_method":"cash","delivered_device":"Ali"}""", test.server.takeRequest().body!!.utf8())
    }

    // ---------------------------------------------------------------------
    // At the counter
    // ---------------------------------------------------------------------

    @Test
    fun `pays at the counter on the phone, and as before without one`() {
        val device = CounterDevice("device-0001", "Tablet", "Samsung", "Tab", "1")
        val card = CounterSales.payBody("s-1", CounterPayment.CARD, device, 2)
        assertEquals("2", card["p_card_terminal"].toString())
        assertEquals("\"card\"", card["p_method"].toString())
        assertFalse(CounterSales.payBody("s-1", CounterPayment.CARD, device, null).containsKey("p_card_terminal"))
        assertFalse(CounterSales.payBody("s-1", CounterPayment.CASH, device, 2).containsKey("p_card_terminal"))
        assertEquals("device-0001", CounterSales.payBody("s-1", CounterPayment.CASH, device, null)["p_device"]!!.jsonObject["id"].toString().trim('"'))
        assertEquals(CounterError.CARD_TERMINAL, CounterError.of("HB506"))
    }

    // ---------------------------------------------------------------------
    // The list
    // ---------------------------------------------------------------------

    @Test
    fun `reads the list, switched-off phones included, and none from a database without it`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"id":1,"name":"S-POS 1","tid":"29001234","active":true,"position":1},
               {"id":3,"name":"S-POS alt","tid":null,"active":false,"position":3}]""",
        )
        test.reply(404, """{"code":"PGRST205","message":"Could not find the table 'public.card_terminals' in the schema cache"}""")

        assertEquals(listOf(one, CardTerminal(3, "S-POS alt", null, false, 3)), terminals.list())
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/card_terminals", request.url.encodedPath)
        assertEquals("id,name,tid,active,position", request.url.queryParameter("select"))
        assertEquals(emptyList(), terminals.list())
    }

    // ---------------------------------------------------------------------
    // The Kassensturz
    // ---------------------------------------------------------------------

    private val answer =
        """{"day":"2026-11-27",
            "totals":{"count":3,"stornos":1,"cash":9.0,"card":27.49,"online":0,"unknown":0,"total":36.49},
            "card_terminals":[
              {"id":1,"name":"S-POS 1","tid":"29001234","active":true,"count":2,"stornos":1,"card":20.0},
              {"id":3,"name":"S-POS alt","tid":null,"active":false,"count":0,"stornos":0,"card":0},
              {"id":null,"name":null,"tid":null,"active":null,"count":1,"stornos":0,"card":7.49}
            ],
            "drivers":[
              {"delivered_by":"u-1","email":"ali@example.com","device":"Ali","telegram":null,"label":"Ali",
               "count":3,"cash":9.0,"card":27.49,"online":0,"unknown":0,"total":36.49,
               "orders":[
                 {"id":"a","order_number":41,"delivered_at":null,"total":25.0,"payment_method":"card","card_terminal_id":1,"pickup":false},
                 {"id":"x","order_number":45,"delivered_at":null,"total":-5.0,"payment_method":"card","card_terminal_id":1,"pickup":false,"storno_of":41},
                 {"id":"b","order_number":42,"delivered_at":null,"total":7.49,"payment_method":"card","card_terminal_id":null,"pickup":false},
                 {"id":"c","order_number":43,"delivered_at":null,"total":9.0,"payment_method":"cash","pickup":false}
               ]}
            ]}"""

    @Test
    fun `reads the card money per phone, and a server before it as none`() {
        val cashUp = json.decodeFromString(CashUp.serializer(), answer)
        assertEquals(3, cashUp.cardTerminals.size)
        assertEquals(CashUpTerminal(1, "S-POS 1", "29001234", true, 2, 1, 20.0), cashUp.cardTerminals[0])
        assertNull(cashUp.cardTerminals[2].id)
        assertEquals(1, cashUp.drivers[0].orders[0].cardTerminalId)
        assertNull(cashUp.drivers[0].orders[2].cardTerminalId)
        assertEquals(cashUp.totals.card, cashUp.cardTerminals.sumOf { it.card }, 0.001)

        val before = json.decodeFromString(CashUp.serializer(), """{"day":"2026-11-27","totals":{"count":1,"card":5.0}}""")
        assertEquals(emptyList(), before.cardTerminals)
        assertEquals(emptyList(), CashUpText.terminals(before))
    }

    @Test
    fun `says each phone's line, the switched-off and the unknown ones too`() {
        val cashUp = json.decodeFromString(CashUp.serializer(), answer)
        val rows = CashUpText.terminals(cashUp)
        assertEquals("S-POS 1 (29001234)", CashUpText.terminal(rows[0], words))
        assertEquals("S-POS alt, nicht mehr in Gebrauch", CashUpText.terminal(rows[1], words))
        assertEquals("unbekannt", CashUpText.terminal(rows[2], words))
        assertEquals("S-POS 1 (29001234): ${eur(20.0)}", CashUpText.terminalLine(rows[0], words))
        // A day of cash only, with no list: nothing per phone.
        val cashOnly = CashUp("2026-11-27", CashUpTotals(count = 1, cash = 9.0, total = 9.0))
        assertEquals(emptyList(), CashUpText.terminals(cashOnly))
    }

    @Test
    fun `names the phone behind a card sum, as it is called now`() {
        val cashUp = json.decodeFromString(CashUp.serializer(), answer)
        val orders = cashUp.drivers[0].orders
        assertEquals("#41 · – · Karte · S-POS 1 · ${eur(25.0)}", CashUpText.order(orders[0], words, terminals = list))
        assertEquals("#42 · – · Karte · ${eur(7.49)}", CashUpText.order(orders[2], words, terminals = list))
        assertEquals("#43 · – · Bar · ${eur(9.0)}", CashUpText.order(orders[3], words, terminals = list))
        // Without the list, as before.
        assertEquals("#41 · – · Karte · ${eur(25.0)}", CashUpText.order(orders[0], words))
    }

    @Test
    fun `shares the card money per phone after the day's sums`() {
        val cashUp = json.decodeFromString(CashUp.serializer(), answer)
        val text = CashUpText.share(cashUp, words, terminals = list)
        val expected =
            listOf(
                    "Kassensturz Fr., 27.11.2026",
                    "3 geliefert · ${eur(36.49)}",
                    "Bar ${eur(9.0)} · Karte ${eur(27.49)}",
                    "",
                    "Karte je S-POS:",
                    "  S-POS 1 (29001234): ${eur(20.0)}",
                    "  S-POS alt, nicht mehr in Gebrauch: ${eur(0.0)}",
                    "  unbekannt: ${eur(7.49)}",
                    "",
                    "Ali: 3 geliefert · ${eur(36.49)}",
                    "Bar ${eur(9.0)} · Karte ${eur(27.49)}",
                    "  #41 · – · Karte · S-POS 1 · ${eur(25.0)}",
                    "  #45 · – · Karte · S-POS 1 · ${eur(-5.0)}",
                    "  #42 · – · Karte · ${eur(7.49)}",
                    "  #43 · – · Bar · ${eur(9.0)}",
                )
                .joinToString("\n")
        assertEquals(expected, text)
    }
}
