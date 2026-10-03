package de.hamzabistro.printstation.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SpecialDaysTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseShopBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private fun date(text: String) = LocalDate.parse(text)

    /** shop_hours() as the API note on hamza-bistro-web#119 shows it. */
    private val answer =
        """{"delivery":[{"day":0,"delivers":false,"opens":660,"closes":1200},{"day":4,"delivers":true,"opens":660,"closes":1200},""" +
            """{"day":6,"delivers":true,"opens":720,"closes":1440}],""" +
            """"closures":[],"open_now":true,"open_until":"2026-10-03T18:00:00+00:00","next_open":null,""" +
            """"special_days":[""" +
            """{"day":"2026-12-24","closed":true,"opens":null,"closes":null,"label":"Heiligabend","by":"koch@example.com"},""" +
            """{"day":"2026-12-31","closed":false,"opens":1020,"closes":1260,"label":"Silvester","by":null}],""" +
            """"today":{"day":"2026-10-03","delivers":true,"opens":720,"closes":1440,"special":false,"label":""}}"""

    @Test
    fun `reads the special days and today's hours with the hours`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer)

        val hours = backend.hours()!!
        val days = hours.specialDays!!
        assertEquals(listOf(date("2026-12-24"), date("2026-12-31")), days.map { it.day })
        assertTrue(days[0].closed)
        assertNull(days[0].hours)
        assertEquals("Heiligabend", days[0].labelText)
        assertEquals("koch@example.com", days[0].by)
        assertEquals(SpecialHours.Open(1020, 1260), days[1].hours)
        assertEquals(DayHours(date("2026-10-03"), true, 720, 1440, special = false, label = ""), hours.today)
    }

    @Test
    fun `a database without special days says none, rather than failing`() {
        val hours = json.decodeFromString(ShopHours.serializer(), """{"open_now":true,"closures":[]}""")
        assertNull(hours.specialDays)
        assertNull(hours.today)
    }

    @Test
    fun `a special day replaces its weekday, and today follows midnight`() {
        val hours = json.decodeFromString(ShopHours.serializer(), answer)
        // Thursday 24.12.: closed, whatever Thursday says.
        assertEquals(DayHours(date("2026-12-24"), false, null, null, special = true, label = "Heiligabend"), hours.hoursOn(date("2026-12-24")))
        // Thursday 31.12.: 17:00–21:00.
        assertEquals(DayHours(date("2026-12-31"), true, 1020, 1260, special = true, label = "Silvester"), hours.hoursOn(date("2026-12-31")))
        // An ordinary Thursday.
        assertEquals(DayHours(date("2026-12-17"), true, 660, 1200), hours.hoursOn(date("2026-12-17")))
        // A Sunday without delivery keeps its times.
        assertEquals(DayHours(date("2026-10-04"), false, 660, 1200), hours.hoursOn(date("2026-10-04")))

        // Saturday 3.10., as read.
        assertEquals(hours.today, hours.todayAt(Instant.parse("2026-10-03T21:59:00Z")))
        // Past midnight in Leipzig (22:00 UTC in summer time): Sunday, worked out.
        assertEquals(DayHours(date("2026-10-04"), false, 660, 1200), hours.todayAt(Instant.parse("2026-10-03T22:00:00Z")))
    }

    @Test
    fun `saves the same hours on several dates, or closed, and back to normal`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer.removeSuffix("}") + ""","preorders_inside":2}""")
        test.reply(200, answer)
        test.reply(200, answer)

        val saved = backend.setSpecialDays(listOf(date("2026-12-25"), date("2026-12-24")), SpecialHours.Closed, " Weihnachten ")
        assertEquals(2, saved.preordersInside)
        backend.setSpecialDays(listOf(date("2026-12-31")), SpecialHours.Open(1020, 1260), "Silvester")
        backend.clearSpecialDays(listOf(date("2026-12-31")))

        test.server.takeRequest()
        val closed = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/special_days_set", closed.url.encodedPath)
        assertEquals(
            """{"p_days":["2026-12-24","2026-12-25"],"p_closed":true,"p_opens":null,"p_closes":null,"p_label":"Weihnachten"}""",
            closed.body!!.utf8(),
        )
        val open = test.server.takeRequest()
        assertEquals(
            """{"p_days":["2026-12-31"],"p_closed":false,"p_opens":1020,"p_closes":1260,"p_label":"Silvester"}""",
            open.body!!.utf8(),
        )
        val clear = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/special_days_clear", clear.url.encodedPath)
        assertEquals("""{"p_days":["2026-12-31"]}""", clear.body!!.utf8())
    }

    @Test
    fun `dates or hours the database refuses say which`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB456","message":"special days: from today to 366 days ahead"}""")
        test.reply(400, """{"code":"HB432","message":"special day hours: on the quarter hour"}""")
        test.reply(400, """{"code":"P0001","message":"staff only"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.special_days_set"}""")

        val date = assertFailsWith<InvalidSpecialDayException> { backend.setSpecialDays(listOf(date("2026-10-01")), SpecialHours.Closed, "") }
        assertEquals(SpecialDayError.DATE, date.reason)
        val hours = assertFailsWith<InvalidSpecialDayException> { backend.setSpecialDays(listOf(date("2026-12-31")), SpecialHours.Open(1020, 1025), "") }
        assertEquals(SpecialDayError.HOURS, hours.reason)
        assertFailsWith<NotAllowedException> { backend.clearSpecialDays(listOf(date("2026-12-31"))) }
        val missing = assertFailsWith<NeedsServerUpdateException> { backend.setSpecialDays(listOf(date("2026-12-31")), SpecialHours.Closed, "") }
        assertEquals("20261003140000_special_days", missing.migration)
    }

    @Test
    fun `a label is cut to sixty characters`() {
        val body = specialDaysSetBody(listOf(date("2026-12-31")), SpecialHours.Closed, "x".repeat(70))
        assertEquals("\"${"x".repeat(60)}\"", body["p_label"].toString())
    }

    @Test
    fun `hours are what the database takes`() {
        assertTrue(SpecialHours.Open(1020, 1260).valid)
        assertTrue(SpecialHours.Open(1080, 1440).valid)
        assertFalse(SpecialHours.Open(1020, 1025).valid)
        assertFalse(SpecialHours.Open(1260, 1020).valid)
        assertFalse(SpecialHours.Open(1020, 1020).valid)
        assertFalse(SpecialHours.Open(1020, 1455).valid)
        assertNull(SpecialForm(opens = 1260, closes = 1020).hours)
        assertEquals(SpecialHours.Closed, SpecialForm(closed = true, opens = 1260, closes = 1020).hours)
    }

    @Test
    fun `the month starts on Monday, with blanks around it`() {
        // 1.12.2026 is a Tuesday; 31.12. a Thursday.
        val december = SpecialCalendar.grid(YearMonth.of(2026, 12))
        assertEquals(5, december.size)
        assertTrue(december.all { it.size == 7 })
        assertEquals(listOf(null, date("2026-12-01")), december[0].take(2))
        assertEquals(listOf(date("2026-12-31"), null, null, null), december[4].drop(3))
        assertEquals(31, december.flatten().count { it != null })

        // February 2027 starts on a Monday and fills four weeks exactly.
        val february = SpecialCalendar.grid(YearMonth.of(2027, 2))
        assertEquals(4, february.size)
        assertEquals(date("2027-02-01"), february[0][0])
        assertEquals(date("2027-02-28"), february[3][6])
    }

    @Test
    fun `a leap year has the 29th of February`() {
        assertEquals(29, SpecialCalendar.grid(YearMonth.of(2028, 2)).flatten().count { it != null })
        assertEquals(28, SpecialCalendar.grid(YearMonth.of(2027, 2)).flatten().count { it != null })
        // From 1.3.2027, 366 days on is 1.3.2028: across 29.2.
        assertEquals(date("2028-03-01"), SpecialCalendar.last(date("2027-03-01")))
        assertEquals(date("2027-10-04"), SpecialCalendar.last(date("2026-10-03")))
    }

    @Test
    fun `today is Leipzig's, whatever the day is in UTC`() {
        assertEquals(date("2026-10-04"), SpecialCalendar.today(Instant.parse("2026-10-03T22:30:00Z")))
        assertEquals(date("2026-10-03"), SpecialCalendar.today(Instant.parse("2026-10-03T21:30:00Z")))
        // Winter time: an hour ahead of UTC.
        assertEquals(date("2026-12-25"), SpecialCalendar.today(Instant.parse("2026-12-24T23:00:00Z")))
        assertEquals(date("2026-12-24"), SpecialCalendar.today(Instant.parse("2026-12-24T22:59:00Z")))
    }

    @Test
    fun `only today to 366 days ahead can be picked, and the arrows stay there`() {
        val today = date("2026-10-03")
        assertFalse(SpecialCalendar.selectable(date("2026-10-02"), today))
        assertTrue(SpecialCalendar.selectable(today, today))
        assertTrue(SpecialCalendar.selectable(date("2027-10-04"), today))
        assertFalse(SpecialCalendar.selectable(date("2027-10-05"), today))

        assertEquals(YearMonth.of(2026, 10), SpecialCalendar.firstMonth(today))
        assertEquals(YearMonth.of(2027, 10), SpecialCalendar.lastMonth(today))
        assertEquals(YearMonth.of(2026, 10), SpecialCalendar.shift(YearMonth.of(2026, 10), -1, today))
        assertEquals(YearMonth.of(2027, 1), SpecialCalendar.shift(YearMonth.of(2026, 12), 1, today))
        assertEquals(YearMonth.of(2027, 10), SpecialCalendar.shift(YearMonth.of(2027, 10), 1, today))
    }

    @Test
    fun `tapping a date picks it, and again takes it out`() {
        val picked = SpecialCalendar.toggle(SpecialCalendar.toggle(emptyList(), date("2026-12-25")), date("2026-12-24"))
        assertEquals(listOf(date("2026-12-24"), date("2026-12-25")), picked)
        assertEquals(listOf(date("2026-12-25")), SpecialCalendar.toggle(picked, date("2026-12-24")))
        assertEquals(0, SpecialCalendar.weekday(date("2026-10-04")))
        assertEquals(4, SpecialCalendar.weekday(date("2026-12-24")))
    }

    @Test
    fun `the first date picked fills the form with its special hours, or its weekday's`() {
        val hours = json.decodeFromString(ShopHours.serializer(), answer)
        assertEquals(SpecialForm(closed = true, label = "Heiligabend"), SpecialForm.from(date("2026-12-24"), hours))
        assertEquals(SpecialForm(closed = false, opens = 1020, closes = 1260, label = "Silvester"), SpecialForm.from(date("2026-12-31"), hours))
        assertEquals(SpecialForm(closed = false, opens = 660, closes = 1200), SpecialForm.from(date("2026-12-17"), hours))
        // Saturday until midnight.
        assertEquals(SpecialForm(closed = false, opens = 720, closes = 1440), SpecialForm.from(date("2026-12-19"), hours))
        // Nothing known: the form's own default.
        assertEquals(SpecialForm(), SpecialForm.from(date("2026-12-19"), null))
    }

    @Test
    fun `the list has the special days and the pauses, soonest first, without what is over`() {
        val now = Instant.parse("2026-12-24T10:00:00Z")
        val running = ShopClosure(1, Instant.parse("2026-12-24T09:00:00Z"), Instant.parse("2026-12-24T11:00:00Z"))
        val over = ShopClosure(2, Instant.parse("2026-12-24T07:00:00Z"), Instant.parse("2026-12-24T08:00:00Z"))
        val planned = ShopClosure(3, Instant.parse("2026-12-30T13:00:00Z"), Instant.parse("2026-12-30T15:00:00Z"))
        val noEnd = ShopClosure(4, Instant.parse("2026-12-20T13:00:00Z"), null)
        val silvester = SpecialDay(date("2026-12-31"), closed = false, opens = 1020, closes = 1260)
        val heiligabend = SpecialDay(date("2026-12-24"), closed = true)
        val gone = SpecialDay(date("2026-12-23"), closed = true)
        val hours = ShopHours(openNow = false, closures = listOf(planned, over, running, noEnd), specialDays = listOf(silvester, gone, heiligabend))
        assertEquals(
            listOf(
                Upcoming.Pause(noEnd),
                Upcoming.Special(heiligabend),
                Upcoming.Pause(running),
                Upcoming.Pause(planned),
                Upcoming.Special(silvester),
            ),
            hours.upcoming(now),
        )
    }
}
