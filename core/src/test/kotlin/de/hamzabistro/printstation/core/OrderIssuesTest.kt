package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class OrderIssuesTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseIssuesBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    /** An open report as the issue's API comment reads it. */
    private val answer =
        """[{"id":"i-1","order_id":"o-1","kind":"missing","lines":[0,2],"comment":"Der Ayran fehlt",
             "created_at":"2026-10-03T18:10:00+00:00","resolved_at":null,"resolution":null,
             "voucher_code":null,"voucher_amount":null,
             "orders":{"order_number":57,"customer_name":"Mia","phone":"0341 123","address":"Georg-Schwarz-Str. 12, 04177 Leipzig",
                       "pickup":false,"total":24.9,"delivered_at":"2026-10-03T17:55:00+00:00",
                       "items":[{"id":1,"name":"Ayran","price":2.3,"deposit":0.25,"qty":3},
                                {"id":2,"name":"Döner","price":7.5,"qty":2},
                                {"id":3,"name":"Pommes","options":["groß"],"price":4.1,"qty":1}]}},
           {"id":"i-2","order_id":"o-2","kind":"cold_late","lines":[],"comment":"",
             "created_at":"2026-10-03T18:20:00+00:00","orders":{"order_number":58,"pickup":true,"items":[],"total":9.0}}]"""

    private fun issue(kind: String, lines: List<Int>, items: List<OrderItem>) =
        OrderIssue(id = "i", kindWire = kind, lines = lines, orders = IssueOrder(items = items))

    private fun item(name: String, price: Double, qty: Int) = OrderItem(id = 1, name = name, price = price, qty = qty)

    @Test
    fun `reads the kinds, and an unknown one as Anderes`() {
        assertEquals(IssueKind.MISSING, IssueKind.of("missing"))
        assertEquals(IssueKind.WRONG, IssueKind.of("wrong"))
        assertEquals(IssueKind.COLD_LATE, IssueKind.of("cold_late"))
        assertEquals(IssueKind.OTHER, IssueKind.of("other"))
        assertEquals(IssueKind.OTHER, IssueKind.of("something_new"))
        assertEquals(IssueKind.OTHER, IssueKind.of(null))
    }

    @Test
    fun `names the missing lines and prices them in cents, only for Etwas fehlt`() {
        val items = listOf(item("Ayran", 2.3, 3), item("Döner", 7.5, 2), item("Pommes", 4.1, 1))
        val missing = issue("missing", listOf(0, 2), items)
        assertEquals("3× Ayran, 1× Pommes", OrderIssues.linesLabel(missing))
        // 3 × 2,30 € + 4,10 € = 11,00 €, not 10,999….
        assertEquals(1100L, OrderIssues.missingCents(missing))
        // A line the order does not have is left out, not a crash.
        assertEquals(listOf("Döner"), OrderIssues.missingLines(issue("missing", listOf(1, 9), items)).map { it.name })
        // The other kinds have no lines to price, whatever was stored.
        assertNull(OrderIssues.missingCents(issue("wrong", listOf(0), items)))
        assertEquals("", OrderIssues.linesLabel(issue("other", listOf(0), items)))
        assertNull(OrderIssues.missingCents(issue("missing", listOf(0), listOf(item("Wasser", 0.0, 1)))))
    }

    @Test
    fun `offers 3, 5 and the missing price when the database would take it`() {
        val cheap = issue("missing", listOf(0), listOf(item("Ayran", 2.3, 3)))
        assertEquals(listOf(300L, 500L, 690L), OrderIssues.voucherChoices(cheap))
        // The same as a fixed step: once.
        assertEquals(listOf(300L, 500L), OrderIssues.voucherChoices(issue("missing", listOf(0), listOf(item("Döner", 5.0, 1)))))
        // Over 100 €: not offered, "Anderer Betrag" is.
        assertEquals(listOf(300L, 500L), OrderIssues.voucherChoices(issue("missing", listOf(0), listOf(item("Platte", 60.0, 2)))))
        assertEquals(listOf(300L, 500L), OrderIssues.voucherChoices(issue("cold_late", emptyList(), emptyList())))
    }

    @Test
    fun `reads another amount as typed, between 0,01 and 100 euros`() {
        assertEquals(700L, OrderIssues.parseAmount("7"))
        assertEquals(750L, OrderIssues.parseAmount("7,50"))
        assertEquals(750L, OrderIssues.parseAmount("7.5"))
        assertEquals(750L, OrderIssues.parseAmount(" 7,50 € "))
        assertEquals(1L, OrderIssues.parseAmount("0,01"))
        assertEquals(10_000L, OrderIssues.parseAmount("100"))
        assertNull(OrderIssues.parseAmount("100,01"))
        assertNull(OrderIssues.parseAmount("0"))
        assertNull(OrderIssues.parseAmount("0,00"))
        assertNull(OrderIssues.parseAmount("-5"))
        assertNull(OrderIssues.parseAmount("7,505"))
        assertNull(OrderIssues.parseAmount("1.000"))
        assertNull(OrderIssues.parseAmount("abc"))
        assertNull(OrderIssues.parseAmount(""))
    }

    @Test
    fun `trims the note and keeps it to 300 characters`() {
        assertNull(OrderIssues.note("   "))
        assertNull(OrderIssues.note(null))
        assertEquals("Angerufen", OrderIssues.note("  Angerufen "))
        assertTrue(OrderIssues.noteFits(" " + "x".repeat(300) + " "))
        assertTrue(OrderIssues.noteFits(null))
        assertEquals(false, OrderIssues.noteFits("x".repeat(301)))
    }

    @Test
    fun `builds the bodies of issue_voucher and resolve_issue`() {
        assertEquals("""{"p_issue_id":"i-1","p_amount":5.00}""", OrderIssues.voucherBody("i-1", 500).toString())
        assertEquals("""{"p_issue_id":"i-1","p_amount":6.90}""", OrderIssues.voucherBody("i-1", 690).toString())
        assertEquals("""{"p_issue_id":"i-1","p_resolution":"Wir haben dich angerufen."}""", OrderIssues.resolveBody("i-1", " Wir haben dich angerufen. ").toString())
        assertEquals("""{"p_issue_id":"i-1","p_resolution":null}""", OrderIssues.resolveBody("i-1", "  ").toString())
    }

    @Test
    fun `maps the database's refusals`() {
        assertEquals(IssueError.ALREADY_RESOLVED, OrderIssues.error("HB461", true, "x")?.reason)
        assertEquals(IssueError.NOT_FOUND, OrderIssues.error("P0002", false, "x")?.reason)
        assertEquals(IssueError.INVALID_AMOUNT, OrderIssues.error("HB462", true, "x")?.reason)
        assertEquals(IssueError.NOTE_TOO_LONG, OrderIssues.error("HB462", false, "x")?.reason)
        assertEquals(IssueError.NO_EMAIL, OrderIssues.error("HB463", true, "x")?.reason)
        assertNull(OrderIssues.error("42501", true, "x"))
        assertNull(OrderIssues.error(null, true, "x"))
    }

    @Test
    fun `reads the count from Content-Range`() {
        assertEquals(3, OrderIssues.count("0-2/3", 0))
        assertEquals(0, OrderIssues.count("*/0", 5))
        assertEquals(2, OrderIssues.count(null, 2))
        assertEquals(2, OrderIssues.count("0-1/*", 2))
    }

    @Test
    fun `reads the open reports, oldest first, with their orders`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, answer)

        val open = backend.open()!!

        assertEquals(listOf("i-1", "i-2"), open.map { it.id })
        val first = open.first()
        assertEquals(IssueKind.MISSING, first.kind)
        assertEquals(57L, first.orders?.orderNumber)
        assertEquals("0341 123", first.orders?.phone)
        assertEquals(Instant.parse("2026-10-03T18:10:00Z"), first.createdAt)
        assertEquals("3× Ayran, 1× Pommes", OrderIssues.linesLabel(first))
        assertEquals(1100L, OrderIssues.missingCents(first))
        assertEquals(IssueKind.COLD_LATE, open[1].kind)
        assertEquals(true, open[1].orders?.pickup)

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/order_issues", request.url.encodedPath)
        assertEquals(OrderIssues.COLUMNS, request.url.queryParameter("select"))
        assertEquals("is.null", request.url.queryParameter("resolved_at"))
        assertEquals("created_at.asc", request.url.queryParameter("order"))
    }

    @Test
    fun `counts the open reports exactly, and marks the orders that had one`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.server.enqueue(
            mockwebserver3.MockResponse.Builder()
                .code(206)
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Range", "0-1/2")
                .body("""[{"id":"i-1"},{"id":"i-2"}]""")
                .build()
        )
        test.reply(200, """[{"order_id":"o-1"}]""")

        assertEquals(OpenIssues(listOf("i-1", "i-2"), 2), backend.openCount())
        assertEquals(setOf("o-1"), backend.reported(listOf("o-1", "o-2")))
        assertEquals(emptySet(), backend.reported(emptyList()))

        test.server.takeRequest()
        val count = test.server.takeRequest()
        assertEquals("count=exact", count.headers["Prefer"])
        assertEquals("id", count.url.queryParameter("select"))
        assertEquals("is.null", count.url.queryParameter("resolved_at"))
        val reported = test.server.takeRequest()
        assertEquals("order_id", reported.url.queryParameter("select"))
        assertEquals("in.(o-1,o-2)", reported.url.queryParameter("order_id"))
    }

    @Test
    fun `is quiet on a database without the reports`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val missing = """{"code":"PGRST205","message":"Could not find the table 'public.order_issues' in the schema cache"}"""
        test.reply(404, missing)
        test.reply(404, missing)
        test.reply(404, missing)

        assertNull(backend.open())
        assertNull(backend.openCount())
        assertEquals(emptySet(), backend.reported(listOf("o-1")))
    }

    @Test
    fun `answers by the database's functions and returns the code that was sent`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """{"id":"i-1","order_id":"o-1","kind":"missing","lines":[0],"comment":"","created_at":"2026-10-03T18:10:00+00:00",
                "resolved_at":"2026-10-03T18:30:00+00:00","resolution":null,"voucher_code":"SORRY-7K2QXA","voucher_amount":5.0}""",
        )
        test.reply(200, """[{"id":"i-2","kind":"other","resolved_at":"2026-10-03T18:31:00+00:00","resolution":"Angerufen"}]""")

        val voucher = backend.sendVoucher("i-1", 500)
        val done = backend.resolve("i-2", " Angerufen ")

        assertEquals("SORRY-7K2QXA", voucher.voucherCode)
        assertEquals(5.0, voucher.voucherAmount)
        assertEquals("Angerufen", done.resolution)
        test.server.takeRequest()
        val sent = test.server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/rest/v1/rpc/issue_voucher", sent.url.encodedPath)
        assertEquals("""{"p_issue_id":"i-1","p_amount":5.00}""", sent.body!!.utf8())
        val resolved = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/resolve_issue", resolved.url.encodedPath)
        assertEquals("""{"p_issue_id":"i-2","p_resolution":"Angerufen"}""", resolved.body!!.utf8())
    }

    @Test
    fun `says why an answer was refused`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(400, """{"code":"HB461","message":"report i-1 is already resolved"}""")
        test.reply(400, """{"code":"HB462","message":"a voucher is between 0,01 and 100 euros, not 200"}""")
        test.reply(400, """{"code":"HB463","message":"the order of report i-1 has no email address any more"}""")
        test.reply(400, """{"code":"HB462","message":"a resolution is at most 300 characters"}""")
        test.reply(500, """{"code":"XX000","message":"boom"}""")

        assertEquals(IssueError.ALREADY_RESOLVED, assertFailsWith<IssueActionException> { backend.sendVoucher("i-1", 500) }.reason)
        assertEquals(IssueError.INVALID_AMOUNT, assertFailsWith<IssueActionException> { backend.sendVoucher("i-1", 20_000) }.reason)
        assertEquals(IssueError.NO_EMAIL, assertFailsWith<IssueActionException> { backend.sendVoucher("i-1", 500) }.reason)
        assertEquals(IssueError.NOTE_TOO_LONG, assertFailsWith<IssueActionException> { backend.resolve("i-1", "x") }.reason)
        assertFailsWith<BackendException> { backend.resolve("i-1", null) }
    }

    // -----------------------------------------------------------------------
    // The chime
    // -----------------------------------------------------------------------

    private val policy = AlarmPolicy()
    private val now = at("2026-10-03T18:00:00Z")
    private val kitchen = AlarmSettings(issues = true)

    private fun chimes(issues: List<String>?, settings: AlarmSettings = kitchen) =
        policy.decide(emptyList(), false, null, settings, now, issues = issues) { 25 }.chimes.filterIsInstance<Chime.Issue>()

    @Test
    fun `is on for the kitchen tablet and off for a driver's phone until somebody chooses`() {
        assertEquals(true, AlarmSettings().forDevice(driver = false).issues)
        assertEquals(false, AlarmSettings().forDevice(driver = true).issues)
        assertEquals(false, AlarmSettings(issues = false).forDevice(driver = false).issues)
        assertEquals(true, AlarmSettings(issues = true).forDevice(driver = true).issues)
        // The two choices are made apart.
        val chosen = AlarmSettings(packed = false).forDevice(driver = true)
        assertEquals(false, chosen.packed)
        assertEquals(false, chosen.issues)
    }

    @Test
    fun `chimes once for a new report, not for what was open when counting started`() {
        assertEquals(emptyList(), chimes(null))
        assertEquals(emptyList(), chimes(listOf("a")))
        assertEquals(listOf(Chime.Issue(added = 1, open = 2)), chimes(listOf("a", "b")))
        assertEquals(emptyList(), chimes(listOf("a", "b")))
        // Answered elsewhere: no word, and a new one still chimes.
        assertEquals(emptyList(), chimes(listOf("b")))
        assertEquals(listOf(Chime.Issue(added = 2, open = 3)), chimes(listOf("b", "c", "d")))
    }

    @Test
    fun `takes in what is open without a word after the count was lost`() {
        chimes(listOf("a"))
        chimes(null)
        assertEquals(emptyList(), chimes(listOf("a", "b")))
        assertEquals(listOf(Chime.Issue(1, 3)), chimes(listOf("a", "b", "c")))
    }

    @Test
    fun `is silent on a device that does not want it, and stays silent when it is switched on`() {
        val driver = AlarmSettings(issues = false)
        chimes(listOf("a"), driver)
        assertEquals(emptyList(), chimes(listOf("a", "b"), driver))
        // Switched on: what came in while it was off is not news.
        assertEquals(emptyList(), chimes(listOf("a", "b")))
        assertEquals(listOf(Chime.Issue(1, 3)), chimes(listOf("a", "b", "c")))
    }

    // -----------------------------------------------------------------------
    // The count
    // -----------------------------------------------------------------------

    private class FakeIssues(var open: OpenIssues?, var failing: Boolean = false) : IssuesBackend {
        var looks = 0

        override suspend fun open(): List<OrderIssue>? = null

        override suspend fun openCount(): OpenIssues? {
            looks++
            if (failing) throw BackendException(503, "down")
            return open
        }

        override suspend fun reported(orderIds: Collection<String>): Set<String> = emptySet()

        override suspend fun sendVoucher(issueId: String, cents: Long) = error("not here")

        override suspend fun resolve(issueId: String, note: String?) = error("not here")
    }

    @Test
    fun `counts on a change, keeps the last count when a look fails, and says when there are no reports`() = runBlocking<Unit> {
        val fake = FakeIssues(OpenIssues(listOf("a"), 1))
        val watch = IssueWatch(fake, Logger.NONE, poll = kotlin.time.Duration.parse("1h"), debounce = kotlin.time.Duration.ZERO)
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { watch.run(changes) }

        suspend fun until(check: (IssuesState) -> Boolean) =
            kotlinx.coroutines.withTimeout(5_000) { watch.state.first { check(it) } }

        assertEquals(IssuesState(true, listOf("a"), 1), until { it.available == true })
        fake.open = OpenIssues(listOf("a", "b"), 2)
        changes.emit(Unit)
        until { it.count == 2 }
        fake.failing = true
        watch.refresh()
        kotlinx.coroutines.withTimeout(5_000) { while (fake.looks < 3) kotlinx.coroutines.delay(10) }
        assertEquals(2, watch.state.value.count)
        fake.failing = false
        fake.open = null
        watch.refresh()
        assertEquals(IssuesState(available = false), until { it.available == false })
        job.cancel()
    }
}
