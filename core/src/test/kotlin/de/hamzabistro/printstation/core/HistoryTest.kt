package de.hamzabistro.printstation.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class HistoryTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseHistoryBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    @Test
    fun `reads the recent orders, newest first, whatever became of them`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        // Older than the retention job's month: the name and phone are gone.
        test.reply(
            200,
            """[{"id":"b","order_number":58,"created_at":"2026-09-26T17:00:00+00:00","status":"delivered","total":12.5,""" +
                """"customer_name":"","phone":"","items":[]},""" +
                """{"id":"a","order_number":57,"created_at":"2026-09-26T16:00:00+00:00","status":"cancelled","total":9,""" +
                """"cancel_reason":"busy","items":[]}]""",
        )

        val orders = backend.recent()
        assertEquals(listOf(58L, 57L), orders.map { it.orderNumber })
        assertEquals(CancelReason.BUSY, orders[1].cancelReason)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/orders", request.url.encodedPath)
        assertEquals("created_at.desc", request.url.queryParameter("order"))
        assertEquals("50", request.url.queryParameter("limit"))
        assertEquals(StaffOrder.HISTORY_COLUMNS, request.url.queryParameter("select"))
        // Every order, not only the open ones.
        assertEquals(null, request.url.queryParameter("status"))
    }

    @Test
    fun `reads the recent orders without the source columns on a database before phone orders, and keeps to that`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"42703","message":"column orders.dine_in does not exist"}""")
        test.reply(200, """[{"id":"a","order_number":57,"created_at":"2026-09-26T16:00:00+00:00","status":"delivered","items":[]}]""")
        test.reply(200, "[]")

        assertEquals(OrderSource.WEB, backend.recent().single().source)
        backend.recent()

        test.server.takeRequest()
        assertEquals(StaffOrder.HISTORY_COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.COLUMNS, test.server.takeRequest().url.queryParameter("select"))
        assertEquals(StaffOrder.COLUMNS, test.server.takeRequest().url.queryParameter("select"))
    }

    @Test
    fun `adds up today's delivered orders from midnight in Leipzig, to the cent`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"total":19.9},{"total":0.1},{"total":23.45}]""")

        // 12:30 in Leipzig (summer time): the day began at 22:00 UTC the day before.
        val takings = backend.takings(at("2026-10-02T10:30:00Z"))
        assertEquals(Takings(3, 43.45), takings)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("total", request.url.queryParameter("select"))
        assertEquals("eq.delivered", request.url.queryParameter("status"))
        assertEquals("gte.2026-10-01T22:00:00Z", request.url.queryParameter("created_at"))
    }

    @Test
    fun `an evening with nothing delivered comes to nothing`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "[]")
        assertEquals(Takings(0, 0.0), backend.takings(at("2026-12-24T20:00:00Z")))
        test.server.takeRequest()
        // Winter time: midnight in Leipzig is 23:00 UTC.
        assertEquals("gte.2026-12-23T23:00:00Z", test.server.takeRequest().url.queryParameter("created_at"))
    }
}
