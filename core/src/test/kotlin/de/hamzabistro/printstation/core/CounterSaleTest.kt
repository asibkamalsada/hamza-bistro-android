package de.hamzabistro.printstation.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CounterSaleTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val device = CounterDevice("device-0001", "Tablet", "HONOR", "Pad X8a", "0.3.1")
    private val backend = SupabaseCounterBackend(test.config, test.client, sessions) { device }

    @AfterTest fun close() = test.close()

    private fun dish(id: Long, name: String, price: Double, deposit: Double = 0.0, pickupDiscount: Double = 0.0) =
        MenuDish(id, "Döner", name, "", price, 8, 0, "", true, emptyList(), deposit = deposit, pickupDiscount = pickupDiscount)

    private val doener = dish(13, "Döner", 8.5, pickupDiscount = 1.0)
    private val cola = dish(48, "Cola", 2.5, deposit = 0.25)
    private val halloumi = MenuOption(13, "Halloumi", 0.5, true, emptyList())
    private val lamb = MenuOption(14, "Lamm", 1.0, true, emptyList())

    private val saleBody =
        """{"id":"s-1","number":321,"state":"open","is_test":false,"dine_in":true,"items":[""" +
            """{"id":13,"name":"Döner","options":["Halloumi"],"price":8.0,"deposit":0,"qty":2}],"total":16.0,"deal_discount":0,""" +
            """"pending_method":null,"pending_since":null,"payment_method":null,"opened_at":"2026-11-23T11:00:00+00:00",""" +
            """"opened_by":"koch","opened_on":"Tablet","closed_at":null,"closed_by":null,"order_id":null,"stale":false,""" +
            """"signing":true,"started":false,"tse":null}"""

    private fun sentBody(): JsonObject {
        test.server.takeRequest()
        return json.parseToJsonElement(test.server.takeRequest().body!!.utf8()).jsonObject
    }

    // ---------------------------------------------------------------------
    // The sums
    // ---------------------------------------------------------------------

    @Test
    fun `the board's price is the site's less the collection discount, never into the Pfand`() {
        val menu = CounterSales.boardMenu(OrderMenu(listOf(doener, cola, dish(5, "Wasser", 1.0, deposit = 0.25, pickupDiscount = 1.0)), emptyList()))
        assertEquals(listOf(7.5, 2.5, 0.25), menu.dishes.map { it.price })
    }

    @Test
    fun `the same dish with the same choices is one line`() {
        var lines = CounterSales.add(emptyList(), BasketLine(doener, 1, listOf(halloumi)))
        lines = CounterSales.add(lines, BasketLine(doener, 2, listOf(halloumi)))
        lines = CounterSales.add(lines, BasketLine(doener, 1, listOf(lamb)))
        assertEquals(listOf(3, 1), lines.map { it.qty })
        assertEquals(99, CounterSales.add(lines, BasketLine(doener, 99, listOf(halloumi)))[0].qty)
        assertEquals(listOf(1), CounterSales.setQty(lines, 0, 0).map { it.qty })
        assertEquals(listOf(3, 5), CounterSales.setQty(lines, 1, 5).map { it.qty })
        assertEquals(3 * 900L + 950L, CounterSales.subtotal(lines))
    }

    @Test
    fun `reads an amount as it is typed`() {
        assertEquals(2000L, CounterSales.parse("20"))
        assertEquals(2050L, CounterSales.parse(" 20,5 "))
        assertEquals(2050L, CounterSales.parse("20.50 €"))
        assertEquals(2000L, CounterSales.parse("20,"))
        for (no in listOf("", "abc", "-5", "1,234", "20,50,1")) assertNull(CounterSales.parse(no), no)
    }

    @Test
    fun `works out the change in whole cents`() {
        assertEquals(430L, CounterSales.change(1570, 2000))
        assertEquals(0L, CounterSales.change(1570, 1570))
        assertNull(CounterSales.change(1570, 1569))
        // 0.1 + 0.2 is not 0.3 in euros; in cents it is.
        assertEquals(30L, CounterSales.cents(0.1 + 0.2))
    }

    @Test
    fun `offers the notes somebody would hand over`() {
        assertEquals(listOf(2000L, 5000L, 10000L), CounterSales.notes(1570))
        assertEquals(listOf(500L, 1000L, 2000L, 5000L), CounterSales.notes(250))
        // Exactly a note: that is "passend", so the next one up.
        assertEquals(listOf(1500L, 2000L, 5000L, 10000L), CounterSales.notes(1000))
    }

    // ---------------------------------------------------------------------
    // The calls
    // ---------------------------------------------------------------------

    @Test
    fun `rings up with the id, the basket, the device and what the screen showed`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, saleBody)
        val sale =
            backend.open("s-1", listOf(BasketLine(doener, 2, listOf(halloumi), "ohne Zwiebeln"), BasketLine(cola, 1)), dineIn = true, training = false, expectedTotal = 16.0)
        assertEquals(321L, sale.number)
        assertEquals(SaleState.OPEN, sale.state)
        assertTrue(sale.dineIn && sale.signing && !sale.cardPending && !sale.stale)
        assertEquals(listOf("Halloumi"), sale.lines.single().options)
        assertEquals("Tablet", sale.openedOn)
        assertNull(sale.receipt)

        val body = sentBody()
        assertEquals(setOf("p_id", "p_fulfilment", "p_items", "p_device", "p_expected_total", "p_test"), body.keys)
        assertEquals("s-1", body["p_id"]!!.jsonPrimitive.content)
        assertEquals("dine_in", body["p_fulfilment"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(16.0), body["p_expected_total"])
        assertEquals(JsonPrimitive(false), body["p_test"])
        val items = body["p_items"]!!.jsonArray.map { it.jsonObject }
        // No note: there is no ticket to read it on.
        assertEquals(setOf("id", "qty", "options"), items[0].keys)
        assertEquals(listOf("13"), items[0]["options"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("device-0001", body["p_device"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Pad X8a", body["p_device"]!!.jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a takeaway without a price yet sends none`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, saleBody)
        backend.open("s-1", listOf(BasketLine(cola, 1)), dineIn = false, training = true, expectedTotal = null)
        val body = sentBody()
        assertEquals("pickup", body["p_fulfilment"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["p_expected_total"])
        assertEquals(JsonPrimitive(true), body["p_test"])
    }

    @Test
    fun `pays, waits for the card and aborts by the sale's id`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val paid =
            saleBody.replace(""""state":"open"""", """"state":"paid"""").replace(""""payment_method":null""", """"payment_method":"card"""")
                .replace(""""order_id":null""", """"order_id":"o-1"""")
                .replace(""""tse":null""", """"tse":{"token":"abc","state":"pending","outage":false,"training":false,"env":null}""")
        test.reply(200, saleBody.replace(""""pending_method":null""", """"pending_method":"card""""))
        test.reply(200, paid)
        test.reply(200, saleBody.replace(""""state":"open"""", """"state":"aborted""""))

        assertTrue(backend.cardPending("s-1", true).cardPending)
        val body = sentBody()
        assertEquals("card", body["p_method"]!!.jsonPrimitive.content)

        val sale = backend.pay("s-1", CounterPayment.CARD)
        assertEquals(SaleState.PAID, sale.state)
        assertEquals(CounterPayment.CARD, sale.payment)
        assertEquals("o-1", sale.orderId)
        assertTrue(sale.receipt!!.pending)
        assertEquals("https://www.hamzabistro.de/beleg/abc", sale.receipt.link)
        val payRequest = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/counter_sale_pay", payRequest.url.encodedPath)
        val pay = json.parseToJsonElement(payRequest.body!!.utf8()).jsonObject
        assertEquals("card", pay["p_method"]!!.jsonPrimitive.content)
        assertEquals("device-0001", pay["p_device"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        assertEquals(SaleState.ABORTED, backend.abort("s-1").state)
        assertEquals("/rest/v1/rpc/counter_sale_abort", test.server.takeRequest().url.encodedPath)
    }

    @Test
    fun `reads the status, the price and the open sales`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"enabled":true,"open":2,"stale":1}""")
        test.reply(200, """{"items":[],"total":16.0,"deal_discount":0.5}""")
        test.reply(200, "[$saleBody]")
        assertEquals(CounterStatus(true, 2, 1), backend.status())
        assertEquals(CounterPrice(16.0, 0.5), backend.price(listOf(BasketLine(doener, 2))))
        assertEquals(listOf("s-1"), backend.openSales().map { it.id })
    }

    @Test
    fun `a database without the till has none, and says so when asked for a sale`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function"}""")
        assertNull(backend.status())
        val e = assertFailsWith<NeedsServerUpdateException> { backend.get("s-1") }
        assertEquals(CounterSales.MIGRATION, e.migration)
    }

    @Test
    fun `every refusal is known by its code`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val codes =
            mapOf(
                "HB409" to CounterError.TOTAL_CHANGED,
                "HB467" to CounterError.BAD_VALUE,
                "HB471" to CounterError.BASKET,
                "HB490" to CounterError.OFF,
                "HB491" to CounterError.GONE,
                "HB492" to CounterError.ALREADY,
                "HB493" to CounterError.KEY_REUSED,
                "HB494" to CounterError.STALE,
                "HB495" to CounterError.NO_SPLIT,
                "HB496" to CounterError.DISMISSED,
                "HB497" to CounterError.BAD_VALUE,
                "HB498" to CounterError.BAD_VALUE,
            )
        for ((code, reason) in codes) {
            test.reply(400, """{"code":"$code","message":"no"}""")
            assertEquals(reason, assertFailsWith<CounterException>(code) { backend.get("s-1") }.reason, code)
        }
        test.reply(403, """{"code":"42501","message":"staff only"}""")
        assertEquals(CounterError.NOT_STAFF, assertFailsWith<CounterException> { backend.get("s-1") }.reason)
        test.reply(503, "down")
        assertFailsWith<BackendException> { backend.get("s-1") }
    }

    // ---------------------------------------------------------------------
    // The receipt
    // ---------------------------------------------------------------------

    @Test
    fun `prints the receipt, and never a kitchen ticket in its place`() = runBlocking<Unit> {
        val printer = SupabasePrintBackend(test.config, test.client, sessions)
        test.token("access-1", "refresh-1")
        test.server.enqueue(
            mockwebserver3.MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream").setHeader("X-Ticket", "receipt").body("beleg").build()
        )
        assertContentEquals("beleg".toByteArray(), printer.receipt("o-1", "de"))
        val body = sentBody()
        assertEquals(JsonPrimitive(true), body["receipt"])
        assertEquals("o-1", body["order"]!!.jsonPrimitive.content)

        // A print-ticket from before the receipt: the flag means nothing to it.
        test.reply(200, "kitchen", "application/octet-stream")
        assertFailsWith<NeedsServerUpdateException> { printer.receipt("o-1", "de") }
        test.reply(404, """{"error":"no receipt for this order"}""")
        assertNull(printer.receipt("o-1", "de"))
    }

    @Test
    fun `the QR code reads back as the link`() {
        val link = CounterReceipt(token = "0123456789abcdef0123456789abcdef").link
        val qr = Qr.of(link)
        assertTrue(qr.size in 21..57)
        val scale = 4
        val border = 4
        val side = (qr.size + 2 * border) * scale
        val source =
            object : LuminanceSource(side, side) {
                override fun getRow(y: Int, row: ByteArray?): ByteArray = ByteArray(side) { x -> pixel(x, y) }

                override fun getMatrix(): ByteArray = ByteArray(side * side) { i -> pixel(i % side, i / side) }

                private fun pixel(x: Int, y: Int): Byte {
                    val row = y / scale - border
                    val column = x / scale - border
                    val dark = row in 0 until qr.size && column in 0 until qr.size && qr.dark(row, column)
                    return if (dark) 0 else -1
                }
            }
        assertEquals(link, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        assertFalse(qr.dark(qr.size - 1, 0).not() && qr.dark(0, qr.size - 1).not(), "the corners are finder patterns")
    }
}
