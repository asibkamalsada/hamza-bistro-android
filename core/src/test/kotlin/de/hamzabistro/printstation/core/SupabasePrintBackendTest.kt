package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SupabasePrintBackendTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val warnings = mutableListOf<String>()
    private val logger =
        object : Logger {
            override fun info(message: String) = Unit

            override fun warn(message: String, error: Throwable?) {
                warnings += message
            }
        }
    private val backend = SupabasePrintBackend(test.config, test.client, sessions, logger)

    @AfterTest fun close() = test.close()

    /** The refresh the first call makes, taken off the server's list. */
    private fun signedIn() {
        test.token("access-1", "refresh-1")
    }

    @Test
    fun `reads the accepted, unprinted orders as the signed-in account`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, """[{"id":"a","order_number":57},{"id":"b","order_number":58}]""")

        assertEquals(listOf(QueuedOrder("a", 57), QueuedOrder("b", 58)), backend.openOrders())
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/orders", request.url.encodedPath)
        assertEquals("id,order_number", request.url.queryParameter("select"))
        assertEquals("eq.confirmed", request.url.queryParameter("status"))
        assertEquals("is.null", request.url.queryParameter("printed_at"))
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("anon-key", request.headers["apikey"])
    }

    @Test
    fun `speaks the print protocol`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, "\"claimed\"")
        test.reply(204)
        test.reply(204)
        test.reply(204)

        assertEquals(Claim.CLAIMED, backend.claim("a"))
        backend.finish("a", true)
        backend.seen("station-1", "Android-App")
        backend.off("station-1")

        test.server.takeRequest()
        val claim = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/claim_print", claim.url.encodedPath)
        assertEquals("""{"p_order_id":"a"}""", claim.body!!.utf8())
        assertEquals("""{"p_order_id":"a","p_printed":true}""", test.server.takeRequest().body!!.utf8())
        assertEquals(
            """{"p_station":"station-1","p_label":"Android-App"}""",
            test.server.takeRequest().body!!.utf8(),
        )
        assertEquals("/rest/v1/rpc/print_station_off", test.server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a token refused on the way is refreshed and the call made once more`() = runBlocking<Unit> {
        signedIn()
        test.reply(401, """{"message":"JWT expired"}""")
        test.token("access-2", "refresh-2")
        test.reply(200, "\"busy\"")

        assertEquals(Claim.BUSY, backend.claim("a"))
        test.server.takeRequest()
        assertEquals("Bearer access-1", test.server.takeRequest().headers["Authorization"])
        test.server.takeRequest()
        assertEquals("Bearer access-2", test.server.takeRequest().headers["Authorization"])
        assertEquals("refresh-2", store.stored?.refreshToken)
    }

    @Test
    fun `an account that is not allowed to print is told so`() = runBlocking<Unit> {
        signedIn()
        test.reply(400, """{"code":"P0001","details":null,"hint":null,"message":"staff only"}""")
        assertFailsWith<NotAllowedException> { backend.claim("a") }
    }

    @Test
    fun `fetches the ticket bytes from print-ticket`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, "\u001b@ticket", contentType = "application/octet-stream")

        assertContentEquals("\u001b@ticket".toByteArray(), backend.ticket("a", "de"))
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/functions/v1/print-ticket", request.url.encodedPath)
        assertEquals("""{"order":"a","lang":"de"}""", request.body!!.utf8())
    }

    @Test
    fun `an order no longer there to print has no ticket`() = runBlocking<Unit> {
        signedIn()
        test.reply(404, """{"error":"no such order to print"}""")
        assertNull(backend.ticket("a", "de"))
    }

    @Test
    fun `sends nothing to the printer that is not a ticket`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, "<html>captive portal</html>", contentType = "text/html")
        test.reply(200, "x".repeat(70_000), contentType = "application/octet-stream")

        assertFailsWith<BackendException> { backend.ticket("a", "de") }
        val tooLarge = assertFailsWith<BackendException> { backend.ticket("a", "de") }
        assertTrue(tooLarge.message!!.contains("too large"))
    }

    @Test
    fun `asks for the bag slip with the ticket, in one request`() = runBlocking<Unit> {
        signedIn()
        test.reply(200, "\u001b@ticket+slip", contentType = "application/octet-stream")

        assertContentEquals("\u001b@ticket+slip".toByteArray(), backend.ticket("a", "de", bagSlip = true))
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/functions/v1/print-ticket", request.url.encodedPath)
        assertEquals("""{"order":"a","lang":"de","bagSlip":true}""", request.body!!.utf8())
        assertEquals(2, test.server.requestCount)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a print-ticket that refuses the bag slip still gives the ticket, and says so`() = runBlocking<Unit> {
        signedIn()
        test.reply(400, """{"error":"bagSlip is true or false"}""")
        test.reply(200, "\u001b@ticket", contentType = "application/octet-stream")

        assertContentEquals("\u001b@ticket".toByteArray(), backend.ticket("a", "de", bagSlip = true))
        test.server.takeRequest()
        assertEquals("""{"order":"a","lang":"de","bagSlip":true}""", test.server.takeRequest().body!!.utf8())
        assertEquals("""{"order":"a","lang":"de"}""", test.server.takeRequest().body!!.utf8())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `only a refused request is asked again without the slip`() = runBlocking<Unit> {
        signedIn()
        test.reply(500, """{"error":"could not build the ticket"}""")
        test.reply(404, """{"error":"no such order to print"}""")

        assertFailsWith<BackendException> { backend.ticket("a", "de", bagSlip = true) }
        assertNull(backend.ticket("a", "de", bagSlip = true))
        // The refresh, then one request each: nothing asked twice.
        assertEquals(3, test.server.requestCount)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a plain ticket refused is not asked for again`() = runBlocking<Unit> {
        signedIn()
        test.reply(400, """{"error":"order is an order id"}""")

        assertFailsWith<BackendException> { backend.ticket("a", "de") }
        assertEquals(2, test.server.requestCount)
    }
}
