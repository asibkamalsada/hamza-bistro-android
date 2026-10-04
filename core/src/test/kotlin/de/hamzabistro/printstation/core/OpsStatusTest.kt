package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class OpsStatusTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseOpsBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val early = Instant.parse("2026-10-03T17:05:00Z")
    private val late = Instant.parse("2026-10-03T18:32:00Z")

    private fun row(source: String, ok: Instant? = null, error: Instant? = null, why: String? = null, stale: Boolean = false) =
        OpsRow(source, ok, error, why, stale = stale)

    @Test
    fun `reads each row off the newer of its two times`() {
        assertEquals(OpsVerdict.Ok(late, null), OpsHealth.verdict(row("notify-order", ok = late)))
        assertEquals(OpsVerdict.Ok(late, early), OpsHealth.verdict(row("notify-order", ok = late, error = early, why = "x")))
        assertEquals(OpsVerdict.Failing(late, "SMTP 550"), OpsHealth.verdict(row("notify-customer", ok = early, error = late, why = "SMTP 550")))
        assertEquals(OpsVerdict.Failing(late, "?"), OpsHealth.verdict(row("telegram-bot", error = late)))
        // A job that stopped is not working, error or not; a failure says more.
        assertEquals(OpsVerdict.Stale(early), OpsHealth.verdict(row("cron:reminders", ok = early, stale = true)))
        assertEquals(OpsVerdict.Failing(late, "boom"), OpsHealth.verdict(row("cron:reminders", ok = early, error = late, why = "boom", stale = true)))
        assertEquals(OpsVerdict.Stale(null), OpsHealth.verdict(row("cron:retention", stale = true)))
        // Nothing yet is not a failure.
        assertEquals(OpsVerdict.Never, OpsHealth.verdict(row("notify-order")))
        assertEquals(OpsVerdict.NotSetUp, OpsHealth.verdict(row("heartbeat")))
        assertEquals(false, OpsHealth.verdict(row("cron:retention", stale = true)).ok)
        assertEquals(true, OpsHealth.verdict(row("heartbeat")).ok)
    }

    @Test
    fun `sorts what the kitchen notices first, first, and the unknown after, by name`() {
        val rows = listOf(row("heartbeat"), row("zz-new"), row("cron:retention"), row("aa-new"), row("notify-order"))
        assertEquals(listOf("notify-order", "cron:retention", "heartbeat", "aa-new", "zz-new"), OpsHealth.sorted(rows).map { it.source })
    }

    @Test
    fun `says since when notify-order has been failing`() {
        assertEquals(late, OpsHealth.notifyFailingSince(listOf(row("notify-order", ok = early, error = late))))
        assertNull(OpsHealth.notifyFailingSince(listOf(row("notify-order", ok = late, error = early))))
        assertNull(OpsHealth.notifyFailingSince(listOf(row("notify-customer", error = late))))
        assertNull(OpsHealth.notifyFailingSince(null))
    }

    @Test
    fun `reads the overview by its function`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"source":"notify-order","last_ok_at":"2026-10-03T18:32:00+00:00","last_error_at":null,"last_error":null,"is_cron":false,"stale":false},
                {"source":"cron:reminders","last_ok_at":"2026-10-03T17:05:00+00:00","last_error_at":null,"last_error":null,"is_cron":true,"stale":true}]""",
        )

        val rows = backend.overview()!!

        assertEquals(listOf("notify-order", "cron:reminders"), rows.map { it.source })
        assertEquals(late, rows[0].lastOkAt)
        assertEquals(true, rows[1].isCron)
        assertEquals(true, rows[1].stale)
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/rpc/ops_overview", request.url.encodedPath)
        assertEquals("{}", request.body!!.utf8())
    }

    @Test
    fun `reads the table without the refresh, for the poll`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"source":"notify-order","last_ok_at":"2026-10-03T17:05:00+00:00","last_error_at":"2026-10-03T18:32:00+00:00","last_error":"401"}]""")

        val rows = backend.status()!!

        assertEquals(late, OpsHealth.notifyFailingSince(rows))
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/rest/v1/ops_status", request.url.encodedPath)
        assertEquals("source,last_ok_at,last_error_at,last_error", request.url.queryParameter("select"))
    }

    @Test
    fun `reads the failed email, or none`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"order_number":57,"kind":"delay-20","error":"550 mailbox unavailable","failed_at":"2026-10-03T17:05:00+00:00"}]""")
        test.reply(200, "[]")

        assertEquals(FailedEmail(57, "delay-20", "550 mailbox unavailable", early), backend.failedEmail())
        assertNull(backend.failedEmail())
        test.server.takeRequest()
        assertEquals("/rest/v1/rpc/failed_customer_email", test.server.takeRequest().url.encodedPath)
    }

    @Test
    fun `has no data and no error on a database without it`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val function = """{"code":"PGRST202","message":"Could not find the function public.ops_overview without parameters in the schema cache"}"""
        test.reply(404, function)
        test.reply(404, function)
        test.reply(404, """{"code":"PGRST205","message":"Could not find the table 'public.ops_status' in the schema cache"}""")

        assertNull(backend.overview())
        assertNull(backend.failedEmail())
        assertNull(backend.status())
    }

    @Test
    fun `fails on any other refusal`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(500, """{"code":"XX000","message":"boom"}""")

        assertFailsWith<BackendException> { backend.overview() }
    }
}
