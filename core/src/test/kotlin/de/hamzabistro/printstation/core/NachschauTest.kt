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

class NachschauTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseNachschauBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val answer =
        """
        {"from": "2027-01-01", "to": "2027-01-31",
         "closings": [
           {"business_date": "2027-01-02", "closing_number": 1, "env": "LIVE", "state": "exported", "failed": false,
            "bucket": "dsfinvk", "path": "live/closings/2027-01-02_Z1.zip", "bytes": 900, "sha256": "c1"},
           {"business_date": "2027-01-03", "closing_number": 2, "env": "LIVE", "state": "built", "failed": false,
            "bucket": "dsfinvk", "path": null, "bytes": null, "sha256": null}],
         "exports": [
           {"id": 8, "date_from": "2027-01-01", "date_to": "2027-01-31", "state": "requested", "failed": false,
            "dismissed": false, "last_error": null, "bucket": "dsfinvk", "path": null, "bytes": null, "sha256": null,
            "created_at": "2027-02-01T09:00:00Z", "updated_at": "2027-02-01T09:00:00Z"}],
         "tars": [
           {"id": 11, "serial": "abab", "env": "LIVE", "vendor": "swissbit", "from_time": null,
            "to_time": "2027-01-15T02:30:00+00:00", "state": "stored", "failed": false, "last_error": null,
            "bucket": "tse-tar", "path": "live/abab/beginn_20270115T023000Z.tar", "bytes": 4096, "sha256": "t1"}],
         "tar_stored_until": "2027-02-01T02:30:00+00:00"}
        """.trimIndent()

    @Test
    fun `reads every file of the range from tse_nachschau`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer)
        val got = backend.load(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31))
        assertEquals(2, got.closings.size)
        assertEquals("2027-01-02_Z1.zip", got.closings[0].fileName)
        assertNull(got.closings[1].path)
        assertTrue(got.making)
        assertEquals(Nachschau.TAR_BUCKET, got.tars.single().bucket)
        assertNull(got.tars.single().fromTime)
        val sent = test.requests().last()
        assertEquals("/rest/v1/rpc/tse_nachschau", sent.url.encodedPath)
        assertEquals("""{"p_from":"2027-01-01","p_to":"2027-01-31"}""", sent.body!!.utf8())
    }

    @Test
    fun `asks for an export and gets its id`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, "12")
        assertEquals(12L, backend.requestExport(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)))
        assertEquals("/rest/v1/rpc/tse_dsfinvk_export", test.requests().last().url.encodedPath)
    }

    @Test
    fun `a refused range is its own error, and staff only is not allowed`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"22023","message":"only closed days can be exported: up to yesterday"}""")
        assertFailsWith<NachschauRangeException> {
            backend.requestExport(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31))
        }
        test.reply(403, """{"code":"42501","message":"staff only"}""")
        assertFailsWith<NotAllowedException> { backend.load(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)) }
    }

    @Test
    fun `a database without the function needs the server update`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.tse_nachschau"}""")
        val e = assertFailsWith<NeedsServerUpdateException> {
            backend.load(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31))
        }
        assertEquals(Nachschau.MIGRATION, e.migration)
    }

    @Test
    fun `signs a link to a kept file for a minute, as the signed-in account`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """{"signedURL":"/object/sign/tse-tar/live/abab/a.tar?token=signed"}""")
        val file = NachschauTar(id = 1, serial = "abab", env = "LIVE", vendor = "swissbit", toTime = "x",
            state = "stored", path = "live/abab/a.tar")
        val url = backend.signedUrl(file)
        assertEquals(test.config.url.resolve("/storage/v1/object/sign/tse-tar/live/abab/a.tar?token=signed").toString(), url)
        val sent = test.requests().last()
        assertEquals("/storage/v1/object/sign/tse-tar/live/abab/a.tar", sent.url.encodedPath)
        assertEquals("""{"expiresIn":60}""", sent.body!!.utf8())
        assertEquals("Bearer access-1", sent.headers["Authorization"])
    }

    @Test
    fun `signs nothing for a file not kept yet`() = runBlocking<Unit> {
        val file = NachschauClosing(businessDate = "2027-01-03", closingNumber = 2, env = "LIVE", state = "built")
        assertFailsWith<IllegalStateException> { backend.signedUrl(file) }
        assertTrue(test.requests().isEmpty())
    }

    @Test
    fun `an export is in progress until it is stored, failed or dismissed`() {
        val row = NachschauExport(id = 1, dateFrom = "a", dateTo = "b", state = "requested")
        assertTrue(row.inProgress)
        assertFalse(row.copy(state = "stored").inProgress)
        assertFalse(row.copy(failed = true).inProgress)
        assertFalse(row.copy(dismissed = true).inProgress)
    }

    @Test
    fun `last month, over the new year and a leap day`() {
        assertEquals(LocalDate.of(2027, 2, 1) to LocalDate.of(2027, 2, 28), Nachschau.previousMonth(LocalDate.of(2027, 3, 15)))
        assertEquals(LocalDate.of(2028, 2, 1) to LocalDate.of(2028, 2, 29), Nachschau.previousMonth(LocalDate.of(2028, 3, 1)))
        assertEquals(LocalDate.of(2026, 12, 1) to LocalDate.of(2026, 12, 31), Nachschau.previousMonth(LocalDate.of(2027, 1, 5)))
    }
}
