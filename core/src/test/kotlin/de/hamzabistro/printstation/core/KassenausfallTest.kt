package de.hamzabistro.printstation.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KassenausfallTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val device = CounterDevice("device-0001", "Tablet", "HONOR", "Pad X8a", "0.3.1")
    private val backend = SupabaseKassenausfallBackend(test.config, test.client, sessions) { device }

    @AfterTest fun close() = test.close()

    private val day = LocalDate.parse("2026-11-28")

    // 14:00 in Leipzig (CET).
    private val now = Instant.parse("2026-11-28T13:00:00Z")

    private val cola = BasketLine(MenuDish(48, "Getränke", "Cola", "", 2.5, 0, 0, "", true, emptyList(), deposit = 0.25), 2)

    private val outageBody =
        """{"id":"o-1","number":7,"day":"2026-11-28","started_at":"2026-11-28T11:05:00+00:00",""" +
            """"ended_at":"2026-11-28T12:10:00+00:00","cause":"Internet weg","note":"Hotspot ohne Netz",""" +
            """"slips_first":1,"slips_last":4,"void":[3],"entered":[{"slip":1,"number":440,""" +
            """"at":"2026-11-28T11:30:00+00:00","entered_at":"2026-11-28T12:20:00+00:00","total":5.00,""" +
            """"payment_method":"cash","by":"tresen"}],"missing":[2,4],"created_at":"2026-11-28T12:15:00+00:00",""" +
            """"created_by":"tresen","created_on":"Tablet","corrections":1}"""

    private fun outage() = json.decodeFromString(TillOutage.serializer(), outageBody)

    private fun draft(
        from: String = "12:05",
        to: String = "13:10",
        cause: String = "Internet weg",
        first: String = "1",
        last: String = "4",
        void: String = "",
        note: String = "",
    ) = OutageDraft(from, to, cause, first, last, void, note)

    private fun sentBody(): JsonObject {
        test.server.takeRequest()
        return json.parseToJsonElement(test.server.takeRequest().body!!.utf8()).jsonObject
    }

    // ---------------------------------------------------------------------
    // The rules
    // ---------------------------------------------------------------------

    @Test
    fun `reads a Leipzig wall time in winter and in summer`() {
        assertEquals(Instant.parse("2026-11-28T11:05:00Z"), Kassenausfall.instant(day, "12:05"))
        assertEquals(Instant.parse("2026-07-01T10:05:00Z"), Kassenausfall.instant(LocalDate.parse("2026-07-01"), "12:05"))
        assertEquals(Instant.parse("2026-11-28T08:30:00Z"), Kassenausfall.instant(day, "9.30"))
        for (bad in listOf("", "24:00", "12", "12:60", "zwölf")) assertNull(Kassenausfall.instant(day, bad), bad)
        assertEquals("12:05", Kassenausfall.clock(Instant.parse("2026-11-28T11:05:00Z")))
        assertEquals(day, Kassenausfall.today(Instant.parse("2026-11-28T23:30:00Z").minusSeconds(3600)))
    }

    @Test
    fun `reads Notbeleg numbers`() {
        assertEquals(emptyList(), Kassenausfall.parseSlips(" "))
        assertEquals(listOf(3, 7, 9), Kassenausfall.parseSlips("7, 3 3;9"))
        assertNull(Kassenausfall.parseSlips("3, x"))
        assertNull(Kassenausfall.parseSlips("-1"))
    }

    @Test
    fun `mirrors the database's outage note - from-to today, a cause, a range`() {
        assertEquals(
            OutageRequest(
                Instant.parse("2026-11-28T11:05:00Z"),
                Instant.parse("2026-11-28T12:10:00Z"),
                "Internet weg",
                1,
                4,
                listOf(3),
                "Router",
            ),
            Kassenausfall.request(draft(void = "3", note = " Router "), day, now),
        )
        val none = Kassenausfall.request(draft(first = "", last = ""), day, now)!!
        assertNull(none.slipsFirst)
        assertEquals(emptyList(), none.void)
        val bad =
            listOf(
                draft(from = "13:10", to = "12:05"),
                draft(to = "14:30"),
                draft(from = ""),
                draft(cause = " "),
                draft(cause = "x".repeat(81)),
                draft(first = "0"),
                draft(first = "5"),
                draft(last = ""),
                draft(last = "1001"),
                draft(void = "5"),
                draft(void = "drei"),
                draft(first = "", last = "", void = "1"),
                draft(note = "x".repeat(501)),
            )
        for (d in bad) assertNull(Kassenausfall.request(d, day, now), d.toString())
    }

    @Test
    fun `a correction starts from what the note says`() {
        assertEquals(draft(void = "3", note = "Hotspot ohne Netz"), Kassenausfall.draftOf(outage()))
    }

    @Test
    fun `a Notbeleg's time is inside the outage and not after now`() {
        val o = outage()
        assertEquals(Instant.parse("2026-11-28T11:30:00Z"), Kassenausfall.slipTime(o, day, "12:30", now))
        assertEquals(Instant.parse("2026-11-28T11:05:00Z"), Kassenausfall.slipTime(o, day, "12:05", now))
        assertEquals(Instant.parse("2026-11-28T12:10:00Z"), Kassenausfall.slipTime(o, day, "13:10", now))
        assertNull(Kassenausfall.slipTime(o, day, "12:04", now))
        assertNull(Kassenausfall.slipTime(o, day, "13:11", now))
        assertNull(Kassenausfall.slipTime(o, day, "12:30", Instant.parse("2026-11-28T11:20:00Z")))
        val late = o.copy(startedAt = Instant.parse("2026-11-28T11:05:30Z"))
        assertEquals(late.startedAt, Kassenausfall.slipTime(late, day, "12:05", now))
    }

    @Test
    fun `reads a note with what is entered and missing`() {
        val o = outage()
        assertEquals("1–4", o.range)
        assertEquals(listOf(3), o.void)
        assertEquals(listOf(2, 4), o.missing)
        assertEquals(440, o.entered.single().number)
        assertEquals(Instant.parse("2026-11-28T11:30:00Z"), o.entered.single().at)
        assertEquals(listOf(2, 4, 9), Kassenausfall.missing(listOf(o, o.copy(missing = listOf(9)))))
        assertEquals("", o.copy(slipsFirst = null, slipsLast = null).range)
        assertEquals("5", o.copy(slipsFirst = 5, slipsLast = 5).range)
    }

    // ---------------------------------------------------------------------
    // The backend
    // ---------------------------------------------------------------------

    @Test
    fun `reports a note with its times, numbers and the device`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, outageBody)
        val got = backend.report("o-1", Kassenausfall.request(draft(void = "3"), day, now)!!)
        val body = sentBody()
        assertEquals("o-1", body["p_id"]!!.jsonPrimitive.content)
        assertEquals("2026-11-28T11:05:00Z", body["p_started_at"]!!.jsonPrimitive.content)
        assertEquals("2026-11-28T12:10:00Z", body["p_ended_at"]!!.jsonPrimitive.content)
        assertEquals("1", body["p_slips_first"]!!.jsonPrimitive.content)
        assertEquals(listOf("3"), body["p_void"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, body["p_note"])
        assertEquals("device-0001", body["p_device"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(listOf(2, 4), got.missing)
    }

    @Test
    fun `enters a Notbeleg at the paper's time, paid as the paper says`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """{"id":"s-1","number":441,"state":"paid","payment_method":"card","total":5.0,""" +
                """"late":{"slip":2,"outage":"o-1","entered_at":"2026-11-28T12:30:00+00:00"}}""",
        )
        val sale =
            backend.late(
                "s-1",
                "o-1",
                2,
                Instant.parse("2026-11-28T11:40:00Z"),
                true,
                listOf(cola),
                CounterPayment.CARD,
                5.0,
                3,
            )
        val body = sentBody()
        assertEquals("o-1", body["p_outage"]!!.jsonPrimitive.content)
        assertEquals("2", body["p_slip"]!!.jsonPrimitive.content)
        assertEquals("2026-11-28T11:40:00Z", body["p_at"]!!.jsonPrimitive.content)
        assertEquals("dine_in", body["p_fulfilment"]!!.jsonPrimitive.content)
        assertEquals("card", body["p_method"]!!.jsonPrimitive.content)
        assertEquals("5.0", body["p_expected_total"]!!.jsonPrimitive.content)
        assertEquals("3", body["p_card_terminal"]!!.jsonPrimitive.content)
        assertEquals("48", body["p_items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(2, sale.late!!.slip)
        assertEquals(CounterPayment.CARD, sale.payment)
        // Cash names no phone.
        assertEquals(
            JsonNull,
            Kassenausfall.lateBody("s", "o", 1, now, false, listOf(cola), CounterPayment.CASH, 5.0, device, 3)["p_card_terminal"],
        )
    }

    @Test
    fun `reads the day's notes, and a server without them says so`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "[$outageBody]")
        assertEquals("o-1", backend.outages(day)!!.single().id)
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function"}""")
        assertNull(backend.outages(null))
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function"}""")
        assertEquals(
            Kassenausfall.MIGRATION,
            assertFailsWith<NeedsServerUpdateException> {
                backend.report("o-1", Kassenausfall.request(draft(), day, now)!!)
            }.migration,
        )
    }

    @Test
    fun `every refusal is known by its code`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val req = Kassenausfall.request(draft(), day, now)!!
        for (reason in OutageError.entries.filter { it != OutageError.NOT_STAFF }) {
            test.reply(400, """{"code":"${reason.code}","message":"no"}""")
            assertEquals(reason, assertFailsWith<OutageException>(reason.code) { backend.report("x", req) }.reason)
        }
        test.reply(403, """{"code":"42501","message":"staff only"}""")
        assertEquals(OutageError.NOT_STAFF, assertFailsWith<OutageException> { backend.report("x", req) }.reason)
        test.reply(400, """{"code":"HB518","message":"Notbelege 2 are not entered yet"}""")
        assertEquals(
            CashBookError.COUNT_WAITS,
            assertFailsWith<CashBookException> {
                SupabaseKassenbuchBackend(test.config, test.client, sessions) { device }.add("c", CashBookKind.COUNT, 1.0, null, null)
            }.reason,
        )
    }
}
