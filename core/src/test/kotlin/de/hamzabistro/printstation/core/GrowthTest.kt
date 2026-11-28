package de.hamzabistro.printstation.core

import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class GrowthTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val history = SupabaseHistoryBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val range = ReportRange(LocalDate.parse("2019-03-01"), LocalDate.parse("2019-03-31"))

    /** The rehearsal's GR4, staff_growth('2019-03-01', '2019-03-31'), keys as Postgres may order them. */
    private val answer =
        """
        {"months": [
           {"month": "2019-02-01", "orders": 0, "revenue": 0.00, "web_orders": 0, "new_accounts": 1, "new_customers": 0, "accounts_total": 1},
           {"month": "2019-03-01", "orders": 4, "revenue": 100.00, "web_orders": 3, "new_accounts": 2, "new_customers": 2, "accounts_total": 3}],
         "current": {"to": "2019-03-31", "from": "2019-03-01", "orders": 4, "revenue": 100.00, "web_orders": 3, "new_accounts": 2,
                     "new_customers": 2, "average_basket": 25.00, "new_customer_share": 0.6667, "new_customer_revenue": 60.00},
         "previous": {"to": "2019-02-28", "from": "2019-02-01", "orders": 0, "revenue": 0, "web_orders": 0, "new_accounts": 1,
                      "new_customers": 0, "average_basket": null, "new_customer_share": null, "new_customer_revenue": 0},
         "customers": {"registered": 3, "ordered_ever": 2, "registered_now": 8, "active_30_days": 4, "repeat_30_days": 1}}
        """
            .trimIndent()

    @Test
    fun `asks staff_growth for the range and reads it`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer)

        val growth = history.growth(range)!!

        assertEquals(2, growth.current.newCustomers)
        assertEquals(0.6667, growth.current.newCustomerShare)
        assertEquals(ReportRange(LocalDate.parse("2019-02-01"), LocalDate.parse("2019-02-28")), growth.previous.range)
        assertNull(growth.previous.averageBasket)
        assertNull(growth.previous.newCustomerShare)
        assertEquals(GrowthCustomers(3, 8, 2, 4, 1), growth.customers)
        assertEquals(0.25, growth.customers.orderedShare)
        assertEquals(0.25, growth.customers.repeatShare)
        assertEquals(listOf(LocalDate.parse("2019-02-01"), LocalDate.parse("2019-03-01")), growth.months.map { it.date })

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_growth", request.url.encodedPath)
        assertEquals("""{"p_from":"2019-03-01","p_to":"2019-03-31"}""", request.body!!.utf8())
    }

    @Test
    fun `no staff_growth yet, a range refused, or not staff`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.staff_growth(p_from, p_to)"}""")
        test.reply(400, """{"code":"HB455","message":"a report covers 1 to 366 days"}""")
        test.reply(403, """{"code":"42501","message":"staff only"}""")

        assertNull(history.growth(range))
        assertFailsWith<ReportRangeException> { history.growth(range) }
        assertFailsWith<NotAllowedException> { history.growth(range) }
    }

    @Test
    fun `no shares of nothing`() {
        assertNull(GrowthCustomers().orderedShare)
        assertNull(GrowthCustomers().repeatShare)
        assertNull(GrowthMonth("2019-02-01").newCustomerShare)
    }

    @Test
    fun `the change against the span before`() {
        assertEquals("+25 %", GrowthText.change(125.0, 100.0))
        assertEquals("−12,5 %", GrowthText.change(87.5, 100.0))
        assertEquals("±0 %", GrowthText.change(100.01, 100.0))
        assertNull(GrowthText.change(5.0, 0.0))
        assertNull(GrowthText.change(null, 100.0))
        assertNull(GrowthText.change(100.0, null))
        assertEquals("+3 · +25 %", GrowthText.count(15, 12))
        assertEquals("−1 · −50 %", GrowthText.count(1, 2))
        assertEquals("+4", GrowthText.count(4, 0))
        assertEquals("±0", GrowthText.count(0, 0))
        assertEquals("+5 Pkt.", GrowthText.points(0.40, 0.35, "Pkt."))
        assertNull(GrowthText.points(0.40, null, "Pkt."))
        assertEquals(1, GrowthText.direction("+4"))
        assertEquals(-1, GrowthText.direction(GrowthText.count(1, 2)))
        assertEquals(0, GrowthText.direction("±0 %"))
        assertEquals(0, GrowthText.direction(null))
    }

    @Test
    fun `the months by each figure`() {
        val month = GrowthMonth("2019-03-01", orders = 4, revenue = 100.0, webOrders = 3, newCustomers = 2, newAccounts = 2, accountsTotal = 3)
        assertEquals(listOf(100.0, 4.0, 2.0, 3.0), GrowthMetric.entries.map { it.of(month) })
        assertEquals(listOf(ReportText.euro(100.0), "4", "2", "3"), GrowthMetric.entries.map { it.text(month) })
    }

    @Test
    fun `the months as CSV for German Excel`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer)

        val file =
            GrowthCsv.months(
                history.growth(range)!!,
                GrowthWords("Monat", "Bestellungen", "Umsatz", "Online-Bestellungen", "Neukunden", "Neukunden %", "Neu registriert", "Registriert"),
            )

        assertEquals("auswertung_2019-03-01_2019-03-31_monate.csv", file.name)
        assertEquals(
            "${ReportCsv.BOM}Monat;Bestellungen;Umsatz;Online-Bestellungen;Neukunden;Neukunden %;Neu registriert;Registriert\r\n" +
                "01.02.2019;0;0,00;0;0;;1;1\r\n01.03.2019;4;100,00;3;2;66,7;2;3\r\n",
            file.text,
        )
    }
}
