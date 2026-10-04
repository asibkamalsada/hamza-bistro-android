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

class ReportTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val history = SupabaseHistoryBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val words =
        ReportWords(
            title = "Auswertung 29.03.2025 – 31.03.2025",
            revenue = "Umsatz",
            orders = "Bestellungen",
            basket = "Ø Warenkorb",
            late = "Verspätet",
            day = "Tag",
            delivered = "Geliefert",
            cancelled = "Storniert",
            delivery = "Lieferung",
            pickup = "Abholung",
            ring = "Ring",
            ringInner = "Innen",
            ringNear = "Nah",
            ringFar = "Weit",
            ringEdge = "Rand",
            deliveryFees = "Liefergebühr",
            smallOrderFees = "Mindermengenzuschlag",
            dish = "Gericht",
            qty = "Menge",
            bestSellers = "Bestseller",
            cash = "Bar",
            card = "Karte",
            online = "Online",
            unknown = "unbekannt",
            byCustomer = "Kunde",
            byStaff = "Personal",
            bySystem = "System",
            reasonBusy = "Zu viel los",
            reasonSoldOut = "Ausverkauft",
            reasonUnreachable = "Nicht erreichbar",
            reasonAddress = "Adresse unklar",
            reasonTimeout = "automatisch abgelehnt",
            reasonNoShow = "Nicht angetroffen",
            reasonNone = "ohne Grund",
            minutes = "{n} Min.",
            empty = "Im Zeitraum wurde nichts geliefert.",
        )

    /** The issue's example: the rehearsal's seeded data, staff_report('2025-03-29', '2025-03-31'), keys as Postgres may order them. */
    private val answer =
        """{
          "from": "2025-03-29", "to": "2025-03-31", "days": 3,
          "totals": {
            "placed": 8, "delivered": 4, "cancelled": 3, "open": 1,
            "revenue": 75.01, "average_basket": 18.75,
            "delivery_fees": 4.47, "small_order_fees": 0.99, "deposits": 1.50
          },
          "by_day": [
            { "day": "2025-03-29", "delivered": 1, "revenue": 21.01, "average_basket": 21.01, "cancelled": 1 },
            { "day": "2025-03-30", "delivered": 2, "revenue": 39.00, "average_basket": 19.50, "cancelled": 1 },
            { "day": "2025-03-31", "delivered": 1, "revenue": 15.00, "average_basket": 15.00, "cancelled": 1 }
          ],
          "fulfilment": {
            "delivery": { "orders": 3, "revenue": 66.01, "average_basket": 22.00 },
            "pickup":   { "orders": 1, "revenue": 9.00,  "average_basket": 9.00 }
          },
          "rings": [
            { "ring": "inner", "orders": 1, "revenue": 21.01, "delivery_fees": 0.99, "small_order_fees": 0 },
            { "ring": "near",  "orders": 1, "revenue": 15.00, "delivery_fees": 1.49, "small_order_fees": 0 },
            { "ring": "far",   "orders": 1, "revenue": 30.00, "delivery_fees": 1.99, "small_order_fees": 0.99 },
            { "ring": "edge",  "orders": 0, "revenue": 0,     "delivery_fees": 0,    "small_order_fees": 0 }
          ],
          "best_sellers": [
            { "item_id": 990002, "name": "Probe Ayran",    "qty": 6, "revenue": 15.02 },
            { "item_id": 990001, "name": "Probe Döner",    "qty": 5, "revenue": 37.50 },
            { "item_id": 990003, "name": "Probe Lahmacun", "qty": 2, "revenue": 13.50 }
          ],
          "hours": [
            { "weekday": 1, "hour": 12, "orders": 1, "revenue": 15.00 },
            { "weekday": 6, "hour": 18, "orders": 1, "revenue": 21.01 },
            { "weekday": 6, "hour": 23, "orders": 1, "revenue": 30.00 },
            { "weekday": 7, "hour": 12, "orders": 1, "revenue": 9.00 }
          ],
          "cancellations": {
            "orders": 3, "value": 25.00,
            "by": [
              { "cancelled_by": "customer", "reason": null,      "orders": 1, "value": 5.00 },
              { "cancelled_by": "staff",    "reason": "busy",    "orders": 1, "value": 8.00 },
              { "cancelled_by": "system",   "reason": "timeout", "orders": 1, "value": 12.00 }
            ]
          },
          "times": {
            "new_to_accepted":       { "orders": 5, "median": 3.0,  "p90": 5.0 },
            "accepted_to_out":       { "orders": 2, "median": 15.0, "p90": 19.0 },
            "out_to_delivered":      { "orders": 3, "median": 20.0, "p90": 24.0 },
            "accepted_to_delivered": { "orders": 2, "median": 37.5, "p90": 43.5 },
            "accepted_to_collected": { "orders": 1, "median": 20.0, "p90": 20.0 }
          },
          "late": {
            "delivery": { "orders": 3, "late": 2, "rate": 0.6667, "median_minutes_late": 7.5, "p90_minutes_late": 9.5 },
            "pickup":   { "orders": 1, "late": 1, "rate": 1.0000, "median_minutes_late": 5.0, "p90_minutes_late": 5.0 }
          },
          "discounts": {
            "total": 10.50,
            "codes":  { "orders": 1, "amount": 3.00, "free_delivery_orders": 1,
                        "by_code": [ { "code": "REPORT-TEST", "orders": 1, "amount": 3.00 } ] },
            "stamps": { "orders": 1, "stamps_spent": 10, "amount": 5.00 },
            "deals":  { "orders": 1, "amount": 1.50, "orders_measured": 3 },
            "pickup": { "orders": 1, "amount": 1.00 }
          },
          "payments": {
            "unknown": { "orders": 1, "amount": 9.00 },
            "online":  { "orders": 1, "amount": 15.00 },
            "card":    { "orders": 1, "amount": 30.00 },
            "cash":    { "orders": 1, "amount": 21.01 }
          }
        }"""

    private val report: Report
        get() = json.decodeFromString(Report.serializer(), answer)

    private fun day(text: String) = LocalDate.parse(text)

    @Test
    fun `reads the issue's example by name`() {
        val r = report
        assertEquals(ReportRange(day("2025-03-29"), day("2025-03-31")), r.range)
        assertEquals(3, r.days)
        assertEquals(ReportTotals(8, 4, 3, 1, 75.01, 18.75, 4.47, 0.99, 1.5), r.totals)
        assertEquals(listOf(21.01, 39.0, 15.0), r.byDay.map { it.revenue })
        assertEquals(day("2025-03-30"), r.byDay[1].date)
        assertEquals(ReportShare(3, 66.01, 22.0), r.fulfilment.delivery)
        assertEquals(listOf("inner", "near", "far", "edge"), r.rings.map { it.ring })
        assertEquals(0.99, r.rings[2].smallOrderFees)
        assertEquals(listOf("Probe Ayran", "Probe Döner", "Probe Lahmacun"), r.bestSellers.map { it.name })
        assertEquals(990002L, r.bestSellers[0].itemId)
        assertEquals(ReportCancellation("system", "timeout", 1, 12.0), r.cancellations.by[2])
        assertNull(r.cancellations.by[0].reason)
        assertEquals(ReportDuration(2, 37.5, 43.5), r.times.acceptedToDelivered)
        assertEquals(0.6667, r.lateRate)
        assertEquals(9.5, r.late.delivery.p90MinutesLate)
        assertEquals(ReportCode("REPORT-TEST", 1, 3.0), r.discounts.codes.byCode.single())
        assertEquals(10, r.discounts.stamps.stampsSpent)
        assertEquals(3, r.discounts.deals.ordersMeasured)
        assertEquals(ReportAmount(1, 1.0), r.discounts.pickup)
        assertEquals(ReportAmount(1, 21.01), r.payments.cash)
        assertEquals(ReportAmount(1, 9.0), r.payments.unknown)
    }

    @Test
    fun `reads the split by source, and none from a server before phone orders`() {
        val r =
            json.decodeFromString(
                Report.serializer(),
                answer.trimEnd().removeSuffix("}") +
                    ""","by_source": [""" +
                    """{"source":"web","placed":40,"cancelled":2,"delivered":37,"revenue":812.4,"average_basket":21.96,"dine_in":0,"fees_waived":0},""" +
                    """{"source":"phone","placed":6,"cancelled":0,"delivered":6,"revenue":101.5,"average_basket":16.92,"dine_in":1,"fees_waived":2},""" +
                    """{"source":"counter","placed":0,"cancelled":0,"delivered":0,"revenue":0,"average_basket":null,"dine_in":0,"fees_waived":0}]}""",
            )
        assertEquals(listOf("web", "phone", "counter"), r.bySource.map { it.source })
        assertEquals(ReportSource("phone", 6, 0, 6, 101.5, 16.92, 1, 2), r.bySource[1])
        assertEquals(null, r.bySource[2].averageBasket)
        assertTrue(r.enteredByStaff)

        val before = json.decodeFromString(Report.serializer(), answer)
        assertEquals(emptyList(), before.bySource)
        assertFalse(before.enteredByStaff)
    }

    /** The issue's answer with the ratings section of 20261004020000_report_ratings.sql. */
    private fun withRatings(ratings: String): Report =
        json.decodeFromString(Report.serializer(), answer.trimEnd().removeSuffix("}") + ""","ratings": $ratings}""")

    @Test
    fun `reads the ratings by name`() {
        val r =
            withRatings(
                """{
                  "recent_comments": [
                    { "at": "2026-10-03T22:30:00+00:00", "comment": "Etwas spät", "stars": 3 },
                    { "stars": 5, "comment": "  ", "at": "2026-10-02T18:00:00.123456+00:00" },
                    { "stars": 1, "comment": null, "at": null }
                  ],
                  "rated_share": 0.8, "average": 3.5, "count": 4,
                  "by_stars": { "5": 1, "4": 1, "3": 1, "2": 1, "1": 0 }
                }"""
            ).ratings!!
        assertEquals(4, r.count)
        assertEquals(3.5, r.average)
        assertEquals(0.8, r.ratedShare)
        assertEquals(listOf(0, 1, 1, 1, 1), (1..5).map(r::withStars))
        assertTrue(r.any)
        assertEquals(3, r.recentComments.size)
        // Given at half past midnight in Leipzig: the next day there.
        assertEquals(day("2026-10-04"), r.recentComments[0].date)
        assertEquals(day("2026-10-02"), r.recentComments[1].date)
        assertNull(r.recentComments[2].date)
        assertEquals(listOf("Etwas spät"), r.comments.map { it.text })
        assertEquals("★★★★☆", ReportText.stars(r.average!!))
        assertEquals("★★★☆☆", ReportText.stars(3.49))
        assertEquals("3,5", ReportText.tenth(r.average))
        assertEquals("80 %", ReportText.percent(r.ratedShare))
    }

    @Test
    fun `no ratings section from an older server, or nothing rated yet`() {
        assertNull(report.ratings)
        assertNull(withRatings("null").ratings)
        val none =
            withRatings(
                """{"count": 0, "average": null, "rated_share": 0.0,
                    "by_stars": {"1": 0, "2": 0, "3": 0, "4": 0, "5": 0}, "recent_comments": []}"""
            ).ratings!!
        assertFalse(none.any)
        assertEquals(0, none.withStars(5))
        // Nothing delivered: no share either.
        val nothing = withRatings("""{"count": 0, "average": null, "rated_share": null, "by_stars": {}, "recent_comments": []}""").ratings!!
        assertFalse(nothing.any)
        assertEquals(0, nothing.withStars(3))
        assertEquals(emptyList(), nothing.comments)
        // A section cut short reads with its defaults.
        val bare = withRatings("{}").ratings!!
        assertFalse(bare.any)
        assertEquals(ReportRatings(), bare)
        // Stars of orders from before the range's comments were cleared: no comments, still an average.
        val cleared = withRatings("""{"count": 2, "average": 4.5, "rated_share": 0.5, "by_stars": {"4": 1, "5": 1}, "recent_comments": []}""").ratings!!
        assertTrue(cleared.any)
        assertEquals(emptyList(), cleared.comments)
    }

    @Test
    fun `an empty range reads with nulls for the averages and zeros elsewhere`() {
        val r =
            json.decodeFromString(
                Report.serializer(),
                """{"from":"2026-10-01","to":"2026-10-01","days":1,
                   "totals":{"placed":0,"delivered":0,"cancelled":0,"open":0,"revenue":0,"average_basket":null,
                             "delivery_fees":0,"small_order_fees":0,"deposits":0},
                   "by_day":[{"day":"2026-10-01","delivered":0,"revenue":0,"average_basket":null,"cancelled":0}],
                   "best_sellers":[],"hours":[],
                   "times":{"new_to_accepted":{"orders":0,"median":null,"p90":null}},
                   "late":{"delivery":{"orders":0,"late":0,"rate":null,"median_minutes_late":null,"p90_minutes_late":null}}}""",
            )
        assertNull(r.totals.averageBasket)
        assertNull(r.lateRate)
        assertNull(r.times.newToAccepted.median)
        assertEquals(0, r.grid.max)
        assertTrue(r.grid.busyHours.isEmpty())
        assertEquals("–", ReportText.percent(r.lateRate))
        assertEquals("–", ReportText.euro(r.totals.averageBasket))
        assertEquals("–", ReportText.minutes(r.times.newToAccepted.median, words))
        assertEquals("﻿Tag;Geliefert;Umsatz;Ø Warenkorb;Storniert\r\n01.10.2026;0;0,00;;0\r\n", ReportCsv.byDay(r, words))
        assertEquals(
            "Auswertung 29.03.2025 – 31.03.2025\nUmsatz ${CashUpText.euro(0.0)}\nBestellungen 0\nØ Warenkorb –\nVerspätet –\n\n" +
                "Im Zeitraum wurde nichts geliefert.",
            ReportText.share(r, words),
        )
    }

    @Test
    fun `the grid fills the empty cells with 0 and shades by the busiest`() {
        val grid = report.grid
        for (weekday in 1..7) for (hour in 0..23) {
            val expected = if ((weekday to hour) in setOf(1 to 12, 6 to 18, 6 to 23, 7 to 12)) 1 else 0
            assertEquals(expected, grid.orders(weekday, hour), "$weekday $hour")
        }
        assertEquals(1, grid.max)
        assertEquals(1.0, grid.shade(6, 23))
        assertEquals(0.0, grid.shade(2, 12))
        assertEquals(21.01, grid.revenue(6, 18))
        assertEquals(12..23, grid.busyHours)

        val busier = BusyGrid.of(listOf(ReportHour(5, 19, 4), ReportHour(5, 20, 1), ReportHour(8, 3, 9), ReportHour(1, 24, 9)))
        assertEquals(4, busier.max)
        assertEquals(0.25, busier.shade(5, 20))
        assertEquals(19..20, busier.busyHours)
    }

    @Test
    fun `best sellers by Menge as sent, or by Umsatz`() {
        assertEquals(listOf("Probe Ayran", "Probe Döner", "Probe Lahmacun"), report.bestSellers(DishOrder.QTY).map { it.name })
        assertEquals(listOf("Probe Döner", "Probe Ayran", "Probe Lahmacun"), report.bestSellers(DishOrder.REVENUE).map { it.name })
    }

    @Test
    fun `the quick ranges on a Leipzig calendar, weeks from Monday`() {
        val friday = day("2026-10-02")
        assertEquals(ReportRange(day("2026-09-28"), friday), ReportPreset.THIS_WEEK.range(friday))
        assertEquals(ReportRange(day("2026-09-21"), day("2026-09-27")), ReportPreset.LAST_WEEK.range(friday))
        assertEquals(ReportRange(day("2026-10-01"), friday), ReportPreset.THIS_MONTH.range(friday))
        assertEquals(ReportRange(day("2026-09-01"), day("2026-09-30")), ReportPreset.LAST_MONTH.range(friday))
        assertNull(ReportPreset.CUSTOM.range(friday))
        // A Monday is its own week; March after a leap February.
        val monday = day("2024-03-04")
        assertEquals(ReportRange(monday, monday), ReportPreset.THIS_WEEK.range(monday))
        assertEquals(ReportRange(day("2024-02-01"), day("2024-02-29")), ReportPreset.LAST_MONTH.range(monday))
        // January's last month is last year's December.
        assertEquals(ReportRange(day("2025-12-01"), day("2025-12-31")), ReportPreset.LAST_MONTH.range(day("2026-01-15")))

        assertEquals(ReportPreset.LAST_WEEK, ReportPreset.of(ReportRange(day("2026-09-21"), day("2026-09-27")), friday))
        assertEquals(ReportPreset.CUSTOM, ReportPreset.of(ReportRange(day("2026-09-20"), day("2026-09-27")), friday))
    }

    @Test
    fun `a chosen range is put in order, kept to today and to 366 days`() {
        val today = day("2026-10-02")
        assertEquals(ReportRange(day("2026-09-01"), day("2026-09-10")), ReportRange.clamp(day("2026-09-10"), day("2026-09-01"), today))
        assertEquals(ReportRange(day("2026-09-01"), today), ReportRange.clamp(day("2026-09-01"), day("2026-12-24"), today))
        assertEquals(ReportRange(today, today), ReportRange.clamp(day("2026-11-01"), day("2026-12-24"), today))

        val long = ReportRange.clamp(day("2020-01-01"), day("2026-09-30"), today)
        assertEquals(366L, long.days)
        assertEquals(day("2025-09-30"), long.from)
        assertEquals(day("2026-09-30"), long.to)
        // 366 days exactly stay as they are.
        assertEquals(ReportRange(day("2025-10-01"), day("2026-10-01")), ReportRange.clamp(day("2025-10-01"), day("2026-10-01"), today))
        assertEquals(366L, ReportRange(day("2025-10-01"), day("2026-10-01")).days)
    }

    @Test
    fun `cancellations read who and why in words`() {
        val by = report.cancellations.by.map { ReportText.cancellation(it, words) }
        assertEquals(listOf("Kunde", "Personal · Zu viel los", "automatisch abgelehnt"), by)
        assertEquals("Personal · ohne Grund", ReportText.cancellation(ReportCancellation("staff", null), words))
        assertEquals("Personal · Ausverkauft", ReportText.cancellation(ReportCancellation("staff", "sold_out"), words))
        assertEquals("Personal · Adresse unklar", ReportText.cancellation(ReportCancellation("staff", "address"), words))
        assertEquals("Personal · Nicht erreichbar", ReportText.cancellation(ReportCancellation("staff", "unreachable"), words))
        // Something newer than the app reads as the database spells it.
        assertEquals("Personal · Nicht angetroffen", ReportText.cancellation(ReportCancellation("staff", "no_show"), words))
        assertEquals("System · lost_in_space", ReportText.cancellation(ReportCancellation("system", "lost_in_space"), words))
        assertEquals("ohne Grund", ReportText.cancellation(ReportCancellation(null, null), words))
    }

    @Test
    fun `numbers in German`() {
        assertEquals("66,7 %", ReportText.percent(0.6667))
        assertEquals("100 %", ReportText.percent(1.0))
        assertEquals("37,5 Min.", ReportText.minutes(37.5, words))
        assertEquals("3 Min.", ReportText.minutes(3.0, words))
        assertEquals("Weit", ReportText.ring("far", words))
        assertEquals("Bar ${CashUpText.euro(21.01)} · Karte ${CashUpText.euro(30.0)} · Online ${CashUpText.euro(15.0)} · unbekannt ${CashUpText.euro(9.0)}",
            ReportText.payments(report.payments, words))
        assertEquals("Bar ${CashUpText.euro(0.0)} · Karte ${CashUpText.euro(0.0)}", ReportText.payments(ReportPayments(), words))
    }

    @Test
    fun `the summary as text to share`() {
        val e = CashUpText::euro
        val expected =
            """
            Auswertung 29.03.2025 – 31.03.2025
            Umsatz ${e(75.01)}
            Bestellungen 4
            Ø Warenkorb ${e(18.75)}
            Verspätet 66,7 %

            Lieferung 3 · ${e(66.01)}
            Abholung 1 · ${e(9.0)}
            Bar ${e(21.01)} · Karte ${e(30.0)} · Online ${e(15.0)} · unbekannt ${e(9.0)}
            Storniert 3 · ${e(25.0)}

            Bestseller:
              6× Probe Ayran · ${e(15.02)}
              5× Probe Döner · ${e(37.5)}
              2× Probe Lahmacun · ${e(13.5)}
            """.trimIndent()
        assertEquals(expected, ReportText.share(report, words))
    }

    @Test
    fun `CSV for German Excel, one file per table`() {
        val files = ReportCsv.files(report, words)
        assertEquals(
            listOf("auswertung_2025-03-29_2025-03-31_tage.csv", "auswertung_2025-03-29_2025-03-31_bestseller.csv", "auswertung_2025-03-29_2025-03-31_ringe.csv"),
            files.map { it.name },
        )
        assertEquals(
            "﻿Tag;Geliefert;Umsatz;Ø Warenkorb;Storniert\r\n" +
                "29.03.2025;1;21,01;21,01;1\r\n30.03.2025;2;39,00;19,50;1\r\n31.03.2025;1;15,00;15,00;1\r\n",
            files[0].text,
        )
        assertEquals(
            "﻿Gericht;Menge;Umsatz\r\nProbe Ayran;6;15,02\r\nProbe Döner;5;37,50\r\nProbe Lahmacun;2;13,50\r\n",
            files[1].text,
        )
        assertEquals(
            "﻿Ring;Bestellungen;Umsatz;Liefergebühr;Mindermengenzuschlag\r\n" +
                "Innen;1;21,01;0,99;0,00\r\nNah;1;15,00;1,49;0,00\r\nWeit;1;30,00;1,99;0,99\r\nRand;0;0,00;0,00;0,00\r\n",
            files[2].text,
        )
        assertEquals("1234,50", ReportCsv.money(1234.5))
        assertEquals("\"Döner; groß\"", ReportCsv.cell("Döner; groß"))
        assertEquals("\"Der \"\"Echte\"\"\"", ReportCsv.cell("Der \"Echte\""))
    }

    @Test
    fun `asks staff_report for the range`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer)

        val read = history.report(ReportRange(day("2025-03-29"), day("2025-03-31")))!!
        assertEquals(75.01, read.totals.revenue)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_report", request.url.encodedPath)
        assertEquals("""{"p_from":"2025-03-29","p_to":"2025-03-31"}""", request.body!!.utf8())
    }

    @Test
    fun `no staff_report yet, a range refused, or not staff`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.staff_report(p_from, p_to)"}""")
        test.reply(400, """{"code":"HB455","message":"a report covers 1 to 366 days, from a day to a day not before it; asked for 2026-10-02 to 2026-10-01"}""")
        test.reply(403, """{"code":"42501","message":"staff only"}""")

        val range = ReportRange(day("2026-10-01"), day("2026-10-02"))
        assertNull(history.report(range))
        assertFailsWith<ReportRangeException> { history.report(range) }
        assertFailsWith<NotAllowedException> { history.report(range) }
    }
}
