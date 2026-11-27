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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StornoTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val device = CounterDevice("device-0001", "Tablet", "HONOR", "Pad X8a", "0.3.1")
    private val backend = SupabaseStornoBackend(test.config, test.client, sessions) { device }

    @AfterTest fun close() = test.close()

    private val stornoBody =
        """{"id":"x-1","number":57,"order_number":42,"is_test":false,"amount":14.49,"refund_method":"card",""" +
            """"reason":"Reklamation","created_at":"2026-11-27T12:00:00+00:00","created_by":"koch","created_on":"Tablet",""" +
            """"signing":true,"tse":{"token":"0123456789abcdef0123456789abcdef","state":"pending","outage":false,""" +
            """"training":false,"env":"TEST","payment_type":"NON_CASH","signed_amounts":[]}}"""

    private fun sentBody(): JsonObject {
        test.server.takeRequest()
        return json.parseToJsonElement(test.server.takeRequest().body!!.utf8()).jsonObject
    }

    private fun order(status: OrderStatus, paid: PaymentMethod? = null) =
        json.decodeFromString(
            StaffOrder.serializer(),
            """{"id":"o-1","order_number":42,"created_at":"2026-11-27T11:00:00+00:00","status":"${status.name.lowercase()}",""" +
                """"total":14.49,"payment_method":${paid?.let { "\"${it.wire}\"" } ?: "null"}}""",
        )

    // ---------------------------------------------------------------------
    // The rules
    // ---------------------------------------------------------------------

    @Test
    fun `only a delivered order is taken back, and once`() {
        val mark = StornoMark("o-1", 57)
        assertTrue(Stornos.can(order(OrderStatus.DELIVERED), null))
        assertFalse(Stornos.can(order(OrderStatus.DELIVERED), mark))
        assertFalse(Stornos.can(order(OrderStatus.ON_THE_WAY), null))
        assertFalse(Stornos.can(order(OrderStatus.CANCELLED), null))
    }

    @Test
    fun `the money goes back the way it was paid, or in cash`() {
        assertEquals(listOf(RefundMethod.CARD, RefundMethod.CASH), Stornos.refundMethods(PaymentMethod.CARD))
        assertEquals(listOf(RefundMethod.ONLINE, RefundMethod.CASH), Stornos.refundMethods(PaymentMethod.ONLINE))
        assertEquals(listOf(RefundMethod.CASH), Stornos.refundMethods(PaymentMethod.CASH))
        assertEquals(listOf(RefundMethod.CASH), Stornos.refundMethods(null))
    }

    // ---------------------------------------------------------------------
    // The calls
    // ---------------------------------------------------------------------

    @Test
    fun `takes a sale back with its id, the order, the way back, the reason and the device`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, stornoBody)
        val storno = backend.storno("x-1", 42, RefundMethod.CARD, "  Reklamation  ")
        val body = sentBody()
        assertEquals("x-1", body["p_id"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(42), body["p_order_number"])
        assertEquals("card", body["p_refund_method"]!!.jsonPrimitive.content)
        assertEquals("Reklamation", body["p_reason"]!!.jsonPrimitive.content)
        assertEquals("device-0001", body["p_device"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(57, storno.number)
        assertEquals(RefundMethod.CARD, storno.refundMethod)
        assertTrue(storno.signing)
        assertEquals("https://www.hamzabistro.de/beleg/0123456789abcdef0123456789abcdef", storno.receipt!!.link)
    }

    @Test
    fun `resumes a Storno by its id, and none is none`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, stornoBody)
        test.reply(200, "null")
        assertEquals("x-1", backend.get("x-1")!!.id)
        assertNull(backend.get("x-2"))
    }

    @Test
    fun `every refusal is known by its code`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val codes =
            mapOf(
                "HB497" to StornoError.BAD_VALUE,
                "HB499" to StornoError.BAD_VALUE,
                "HB500" to StornoError.KEY_REUSED,
                "HB501" to StornoError.NO_REASON,
                "HB502" to StornoError.NOT_DELIVERED,
                "HB503" to StornoError.ALREADY,
                "HB504" to StornoError.METHOD,
                "HB505" to StornoError.UNSIGNED,
            )
        for ((code, reason) in codes) {
            test.reply(400, """{"code":"$code","message":"no"}""")
            assertEquals(reason, assertFailsWith<StornoException>(code) { backend.storno("x", 1, RefundMethod.CASH, "x") }.reason, code)
        }
        test.reply(403, """{"code":"42501","message":"staff only"}""")
        assertEquals(StornoError.NOT_STAFF, assertFailsWith<StornoException> { backend.get("x") }.reason)
        test.reply(503, "down")
        assertFailsWith<BackendException> { backend.get("x") }
    }

    @Test
    fun `a database without the Storno marks nothing, and says so when asked for one`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST205","message":"Could not find the table"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function"}""")
        assertEquals(emptyMap(), backend.marks(listOf("o-1")))
        val e = assertFailsWith<NeedsServerUpdateException> { backend.storno("x", 1, RefundMethod.CASH, "x") }
        assertEquals(Stornos.MIGRATION, e.migration)
    }

    @Test
    fun `reads which orders were taken back, by order id`() = runBlocking<Unit> {
        assertEquals(emptyMap(), backend.marks(emptyList()))
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"order_id":"o-1","storno_number":57,"created_at":"2026-11-27T12:00:00+00:00","refund_method":"cash"},""" +
                """{"order_id":null,"storno_number":58,"created_at":"2026-11-27T12:05:00+00:00","refund_method":"cash"}]""",
        )
        assertEquals(mapOf("o-1" to 57L), backend.marks(listOf("o-1", "o-2")).mapValues { it.value.number })
        test.server.takeRequest()
        val url = test.server.takeRequest().url
        assertEquals("in.(o-1,o-2)", url.queryParameter("order_id"))
    }

    @Test
    fun `prints the Storno's receipt, and never the sale's in its place`() = runBlocking<Unit> {
        val printer = SupabasePrintBackend(test.config, test.client, sessions)
        test.token("access-1", "refresh-1")
        test.server.enqueue(
            mockwebserver3.MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream").setHeader("X-Ticket", "storno-receipt").body("storno").build()
        )
        assertContentEquals("storno".toByteArray(), printer.stornoReceipt("o-1", "de"))
        val body = sentBody()
        assertEquals(JsonPrimitive(true), body["receipt"])
        assertEquals(JsonPrimitive(true), body["storno"])

        // A print-ticket from before the Storno sends the order's own receipt.
        test.server.enqueue(
            mockwebserver3.MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream").setHeader("X-Ticket", "receipt").body("beleg").build()
        )
        assertFailsWith<NeedsServerUpdateException> { printer.stornoReceipt("o-1", "de") }
        test.reply(404, """{"error":"this order has no signed Storno"}""")
        assertNull(printer.stornoReceipt("o-1", "de"))
    }
}
