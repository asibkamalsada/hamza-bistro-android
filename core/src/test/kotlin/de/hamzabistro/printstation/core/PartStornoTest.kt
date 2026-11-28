package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PartStornoTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val device = CounterDevice("device-0001", "Tablet", "HONOR", "Pad X8a", "0.3.1")
    private val backend = SupabasePartStornoBackend(test.config, test.client, sessions) { device }

    @AfterTest fun close() = test.close()

    private val partBody =
        """{"id":"p-1","number":61,"order_number":42,"is_test":false,"amount":6.15,"refund_method":"cash",""" +
            """"reason":"Fehlte","lines":[{"line":1,"qty":1}],"fees":["delivery"],"goodwill":0,""" +
            """"created_at":"2026-11-28T12:00:00+00:00","created_by":"koch","created_on":"Tablet","part":true,""" +
            """"signing":true,"tse":{"token":"0123456789abcdef0123456789abcdef","state":"pending","outage":false,""" +
            """"training":false,"env":"TEST","payment_type":"CASH","signed_amounts":[]}}"""

    private val quoteBody =
        """{"order_number":42,"total":18.00,"payment_method":"card","stornoed":false,"before":6.15,"left":11.85,""" +
            """"amount":0,"parts":{},"goodwill_max":8.27,""" +
            """"lines":[{"line":1,"name":"Döner","options":["scharf"],"qty":2,"taken":1,"price":7.00,"deposit":0,"drink":false},""" +
            """{"line":2,"name":"Cola","options":[],"qty":1,"taken":0,"price":2.50,"deposit":0.08,"drink":true}],""" +
            """"fees":[{"fee":"delivery","amount":2.50,"taken":true},{"fee":"small_order","amount":1.00,"taken":false}]}"""

    private fun sentBody(): JsonObject {
        test.server.takeRequest()
        return json.parseToJsonElement(test.server.takeRequest().body!!.utf8()).jsonObject
    }

    private fun order(status: OrderStatus) =
        json.decodeFromString(
            StaffOrder.serializer(),
            """{"id":"o-1","order_number":42,"created_at":"2026-11-27T11:00:00+00:00","status":"${status.name.lowercase()}",""" +
                """"total":18.00,"payment_method":"cash"}""",
        )

    // ---------------------------------------------------------------------
    // The rules
    // ---------------------------------------------------------------------

    @Test
    fun `part of a delivered order is taken back, not after the whole Storno, and then no whole one`() {
        val whole = StornoMark("o-1", 57)
        val part = PartStornoMark("p-1", "o-1", 61)
        assertTrue(PartStornos.can(order(OrderStatus.DELIVERED), null))
        assertFalse(PartStornos.can(order(OrderStatus.DELIVERED), whole))
        assertFalse(PartStornos.can(order(OrderStatus.ON_THE_WAY), null))
        assertTrue(Stornos.can(order(OrderStatus.DELIVERED), null))
        assertFalse(Stornos.can(order(OrderStatus.DELIVERED), null, listOf(part)))
    }

    @Test
    fun `only lines with something asked are sent, by line`() {
        assertEquals(
            listOf(PartStornoLine(1, 2), PartStornoLine(3, 1)),
            PartStornos.lines(mapOf(3 to 1, 1 to 2, 2 to 0)),
        )
    }

    @Test
    fun `a goodwill amount is read as typed, to the cent`() {
        assertEquals(0.0, PartStornos.goodwill(""))
        assertEquals(3.5, PartStornos.goodwill(" 3,5 "))
        assertEquals(12.34, PartStornos.goodwill("12.34"))
        assertNull(PartStornos.goodwill("1,234"))
        assertNull(PartStornos.goodwill("-1"))
        assertNull(PartStornos.goodwill("drei"))
    }

    // ---------------------------------------------------------------------
    // The calls
    // ---------------------------------------------------------------------

    @Test
    fun `asks what a choice gives back, and reads what is left of each line`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, quoteBody)
        val quote = backend.quote(42, listOf(PartStornoLine(2, 1)), setOf(PartStornoFee.SMALL_ORDER), 1.5)
        val body = sentBody()
        assertEquals(JsonPrimitive(42), body["p_order_number"])
        assertEquals(
            JsonArray(listOf(JsonObject(mapOf("line" to JsonPrimitive(2), "qty" to JsonPrimitive(1))))),
            body["p_lines"],
        )
        assertEquals(listOf("small_order"), body["p_fees"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(1.5), body["p_goodwill"])
        assertEquals(1, quote.lines[0].left)
        assertEquals("Döner (scharf)", quote.lines[0].label)
        assertTrue(quote.fees[0].taken)
        assertEquals(PartStornoFee.SMALL_ORDER, quote.fees[1].fee)
        assertFalse(quote.nothingLeft)
    }

    @Test
    fun `takes part back with its id, the choice, the way back, the reason and the device`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, partBody)
        val part =
            backend.partStorno("p-1", 42, listOf(PartStornoLine(1, 1)), listOf(PartStornoFee.DELIVERY), 0.0, RefundMethod.CASH, "  Fehlte  ")
        val body = sentBody()
        assertEquals("p-1", body["p_id"]!!.jsonPrimitive.content)
        assertEquals("cash", body["p_refund_method"]!!.jsonPrimitive.content)
        assertEquals("Fehlte", body["p_reason"]!!.jsonPrimitive.content)
        assertEquals(listOf("delivery"), body["p_fees"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("device-0001", body["p_device"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(61, part.number)
        assertEquals(listOf(PartStornoLine(1, 1)), part.lines)
        assertEquals(listOf(PartStornoFee.DELIVERY), part.fees)
        assertEquals("https://www.hamzabistro.de/beleg/0123456789abcdef0123456789abcdef", part.receipt!!.link)
    }

    @Test
    fun `every refusal is known by its code`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val codes =
            mapOf(
                "HB500" to StornoError.KEY_REUSED,
                "HB503" to StornoError.ALREADY,
                "HB505" to StornoError.UNSIGNED,
                "HB540" to StornoError.AFTER_PART,
                "HB541" to StornoError.LINE,
                "HB542" to StornoError.FEE,
                "HB543" to StornoError.GOODWILL,
                "HB544" to StornoError.NOTHING,
                "HB545" to StornoError.LINES_UNKNOWN,
            )
        for ((code, reason) in codes) {
            test.reply(400, """{"code":"$code","message":"no"}""")
            val e = assertFailsWith<StornoException>(code) { backend.partStorno("x", 1, emptyList(), emptyList(), 1.0, RefundMethod.CASH, "x") }
            assertEquals(reason, e.reason, code)
        }
    }

    @Test
    fun `a database without the Teilstorno marks nothing, and says so when asked for one`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST205","message":"Could not find the table"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function"}""")
        assertEquals(emptyMap(), backend.marks(listOf("o-1")))
        val e = assertFailsWith<NeedsServerUpdateException> { backend.quote(1, emptyList(), emptyList(), 0.0) }
        assertEquals(PartStornos.MIGRATION, e.migration)
    }

    @Test
    fun `reads the Teilstornos of the orders, by order, oldest first`() = runBlocking<Unit> {
        assertEquals(emptyMap(), backend.marks(emptyList()))
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"id":"p-1","order_id":"o-1","storno_number":61,"created_at":"2026-11-28T12:00:00+00:00","refund_method":"cash","amount":6.15},""" +
                """{"id":"p-2","order_id":"o-1","storno_number":63,"created_at":"2026-11-28T12:05:00+00:00","refund_method":"card","amount":2.5},""" +
                """{"id":"p-3","order_id":null,"storno_number":64,"created_at":"2026-11-28T12:06:00+00:00","refund_method":"cash","amount":1}]""",
        )
        assertEquals(mapOf("o-1" to listOf(61L, 63L)), backend.marks(listOf("o-1", "o-2")).mapValues { (_, v) -> v.map { it.number } })
        test.server.takeRequest()
        val url = test.server.takeRequest().url
        assertEquals("in.(o-1,o-2)", url.queryParameter("order_id"))
        assertEquals("created_at", url.queryParameter("order"))
    }

    @Test
    fun `prints a Teilstorno's receipt, and never another in its place`() = runBlocking<Unit> {
        val printer = SupabasePrintBackend(test.config, test.client, sessions)
        test.token("access-1", "refresh-1")
        test.server.enqueue(
            mockwebserver3.MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream").setHeader("X-Ticket", "part-storno-receipt").body("teil").build()
        )
        assertContentEquals("teil".toByteArray(), printer.partStornoReceipt("o-1", "p-1", "de"))
        val body = sentBody()
        assertEquals(JsonPrimitive(true), body["receipt"])
        assertEquals("p-1", body["part_storno"]!!.jsonPrimitive.content)

        // A print-ticket from before the Teilstorno sends the order's own receipt.
        test.server.enqueue(
            mockwebserver3.MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream").setHeader("X-Ticket", "receipt").body("beleg").build()
        )
        assertFailsWith<NeedsServerUpdateException> { printer.partStornoReceipt("o-1", "p-1", "de") }
        test.reply(404, """{"error":"this order has no such signed Storno"}""")
        assertNull(printer.partStornoReceipt("o-1", "p-1", "de"))
    }
}
