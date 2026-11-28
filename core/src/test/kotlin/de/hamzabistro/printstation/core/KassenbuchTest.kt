package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KassenbuchTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val device = CounterDevice("device-0001", "Tablet", "HONOR", "Pad X8a", "0.3.1")
    private val backend = SupabaseKassenbuchBackend(test.config, test.client, sessions) { device }

    @AfterTest fun close() = test.close()

    private val entryBody =
        """{"id":"e-1","number":1291,"day":"2026-11-28","kind":"count","amount":-0.37,"counted":120.00,""" +
            """"expected":120.37,"place":null,"note":null,"created_at":"2026-11-28T21:20:00+00:00","created_by":"tresen",""" +
            """"created_on":"Tablet","signing":true,"tse":{"token":"0123456789abcdef0123456789abcdef","state":"pending",""" +
            """"outage":false,"training":false,"env":"TEST","payment_type":"CASH","signed_amounts":[]}}"""

    private val cashUpBody =
        """{"day":"2026-11-28","totals":{"count":3,"cash":35.37},"drivers":[],"card_terminals":[],""" +
            """"drawer":{"day":"2026-11-28","opening":85.00,"cash":35.37,"transit_in":20,"transit_out":-50,""" +
            """"deposits":0,"withdrawals":0,"differences":-0.37,"expected":90.00,""" +
            """"last_count":{"counted":120.00,"expected":120.37,"difference":-0.37,"at":"2026-11-28T21:20:00+00:00","by":"tresen"},""" +
            """"since_count":-30.00,"entries":[$entryBody]}}"""

    private fun drawer(opening: Double? = 85.0, expected: Double = 120.37) =
        CashDrawer(day = "2026-11-28", opening = opening, cash = 35.37, expected = expected)

    private fun sentBody(): JsonObject {
        test.server.takeRequest()
        return json.parseToJsonElement(test.server.takeRequest().body!!.utf8()).jsonObject
    }

    // ---------------------------------------------------------------------
    // The rules
    // ---------------------------------------------------------------------

    @Test
    fun `reads euros the way staff type them, nothing finer than a cent`() {
        assertEquals(85.0, Kassenbuch.parseEuro("85"))
        assertEquals(85.5, Kassenbuch.parseEuro(" 85,5 "))
        assertEquals(1234.5, Kassenbuch.parseEuro("1.234,50"))
        assertEquals(85.0, Kassenbuch.parseEuro("85.00 €"))
        for (bad in listOf("", "abc", "-5", "1,005", "1.2.3", "5,")) assertNull(Kassenbuch.parseEuro(bad), bad)
    }

    @Test
    fun `mirrors the database - 0 to 10 000 euros in cents, 0 only when counted`() {
        assertTrue(Kassenbuch.amountAllowed(CashBookKind.OPENING, 0.0))
        assertTrue(Kassenbuch.amountAllowed(CashBookKind.COUNT, 0.0))
        assertFalse(Kassenbuch.amountAllowed(CashBookKind.TRANSIT_OUT, 0.0))
        assertFalse(Kassenbuch.amountAllowed(CashBookKind.DEPOSIT, 0.0))
        assertTrue(Kassenbuch.amountAllowed(CashBookKind.TRANSIT_IN, 10_000.0))
        assertFalse(Kassenbuch.amountAllowed(CashBookKind.TRANSIT_IN, 10_000.01))
        assertFalse(Kassenbuch.amountAllowed(CashBookKind.COUNT, -1.0))
        assertFalse(Kassenbuch.amountAllowed(CashBookKind.COUNT, 1.005))
        assertFalse(Kassenbuch.amountAllowed(CashBookKind.COUNT, null))
    }

    @Test
    fun `the float once, private money with a note, never more out than in`() {
        assertTrue(Kassenbuch.canBook(drawer(opening = null), CashBookKind.OPENING, 85.0, ""))
        assertFalse(Kassenbuch.canBook(drawer(), CashBookKind.OPENING, 85.0, ""))
        assertEquals(Kassenbuch.KINDS - CashBookKind.OPENING, Kassenbuch.kinds(drawer()))
        assertEquals(Kassenbuch.KINDS, Kassenbuch.kinds(drawer(opening = null)))
        assertFalse(Kassenbuch.canBook(drawer(), CashBookKind.WITHDRAWAL, 5.0, "  "))
        assertTrue(Kassenbuch.canBook(drawer(), CashBookKind.WITHDRAWAL, 5.0, "Brötchen"))
        assertFalse(Kassenbuch.canBook(drawer(), CashBookKind.DEPOSIT, 5.0, ""))
        assertTrue(Kassenbuch.canBook(drawer(), CashBookKind.TRANSIT_OUT, 120.37, ""))
        assertFalse(Kassenbuch.canBook(drawer(), CashBookKind.TRANSIT_OUT, 120.38, ""))
        assertFalse(Kassenbuch.canBook(null, CashBookKind.COUNT, 100.0, ""))
    }

    @Test
    fun `works out the count and the drawer afterwards`() {
        assertEquals(-0.37, Kassenbuch.countDifference(drawer(), 120.0))
        assertEquals(0.63, Kassenbuch.countDifference(drawer(), 121.0))
        assertEquals(120.0, Kassenbuch.drawerAfter(drawer(), CashBookKind.COUNT, 120.0))
        assertEquals(85.0, Kassenbuch.drawerAfter(drawer(), CashBookKind.TRANSIT_OUT, 35.37))
        assertEquals(140.37, Kassenbuch.drawerAfter(drawer(), CashBookKind.TRANSIT_IN, 20.0))
        assertEquals(120.0, Kassenbuch.drawerAfter(drawer(), CashBookKind.WITHDRAWAL, 0.37))
    }

    @Test
    fun `the body says the kind, the amount, a place only for Geldtransit, and the device`() {
        val body = Kassenbuch.body("e-1", CashBookKind.TRANSIT_OUT, 50.0, "  ", CashBookPlace.BANK, device)
        assertEquals("transit_out", body["p_kind"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(50.0), body["p_amount"])
        assertEquals(JsonNull, body["p_note"])
        assertEquals("bank", body["p_place"]!!.jsonPrimitive.content)
        assertEquals("device-0001", body["p_device"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val count = Kassenbuch.body("e-2", CashBookKind.COUNT, 120.0, " passt ", CashBookPlace.SAFE, device)
        assertEquals(JsonNull, count["p_place"])
        assertEquals("passt", count["p_note"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------------
    // The calls
    // ---------------------------------------------------------------------

    @Test
    fun `reads the drawer from cash_up`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, cashUpBody)
        val got = backend.drawer(null)!!
        assertEquals(85.0, got.opening)
        assertEquals(90.0, got.expected)
        assertEquals(-50.0, got.transitOut)
        assertEquals(-0.37, got.lastCount!!.difference)
        assertEquals("tresen", got.lastCount.by)
        assertEquals(-30.0, got.sinceCount)
        val entry = got.entries.single()
        assertEquals(CashBookKind.COUNT, entry.kind)
        assertEquals(120.37, entry.expected)
        assertTrue(entry.receipt!!.pending)
        assertEquals("/rest/v1/rpc/cash_up", test.requests().last().url.encodedPath)
    }

    @Test
    fun `a server without the Kassenbuch says so`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"day":"2026-11-28","totals":{},"drivers":[]}""")
        assertEquals(
            Kassenbuch.MIGRATION,
            assertFailsWith<NeedsServerUpdateException> { backend.drawer(null) }.migration,
        )
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function"}""")
        assertEquals(
            Kassenbuch.MIGRATION,
            assertFailsWith<NeedsServerUpdateException> { backend.add("x", CashBookKind.OPENING, 85.0, null, null) }.migration,
        )
    }

    @Test
    fun `books an entry with its id and reads what was booked`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, entryBody)
        val entry = backend.add("e-1", CashBookKind.COUNT, 120.0, null, null)
        val body = sentBody()
        assertEquals("e-1", body["p_id"]!!.jsonPrimitive.content)
        assertEquals("count", body["p_kind"]!!.jsonPrimitive.content)
        assertEquals(-0.37, entry.amount)
        assertEquals(1291, entry.number)
        assertTrue(entry.signing)
    }

    @Test
    fun `every refusal is known by its code`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val codes =
            mapOf(
                "HB497" to CashBookError.BAD_VALUE,
                "HB508" to CashBookError.BAD_VALUE,
                "HB509" to CashBookError.KEY_REUSED,
                "HB510" to CashBookError.OPENING_TWICE,
                "HB511" to CashBookError.TOO_MUCH,
                "HB512" to CashBookError.NO_NOTE,
            )
        for ((code, reason) in codes) {
            test.reply(400, """{"code":"$code","message":"no"}""")
            assertEquals(
                reason,
                assertFailsWith<CashBookException>(code) { backend.add("x", CashBookKind.OPENING, 85.0, null, null) }.reason,
                code,
            )
        }
        test.reply(403, """{"code":"42501","message":"staff only"}""")
        assertEquals(
            CashBookError.NOT_STAFF,
            assertFailsWith<CashBookException> { backend.add("x", CashBookKind.OPENING, 85.0, null, null) }.reason,
        )
        test.reply(503, "down")
        assertFailsWith<BackendException> { backend.add("x", CashBookKind.OPENING, 85.0, null, null) }
    }
}
