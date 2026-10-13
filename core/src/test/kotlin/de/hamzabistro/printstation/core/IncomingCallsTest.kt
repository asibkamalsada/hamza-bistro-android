package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class IncomingCallsTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseIncomingCallsBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private val now = Instant.parse("2026-10-13T17:00:00Z")

    private fun call(id: String, phone: String, secondsAgo: Long) = IncomingCall(id, phone, now.minusSeconds(secondsAgo))

    // -----------------------------------------------------------------------
    // Which calls the tablet shows
    // -----------------------------------------------------------------------

    private fun shown(calls: List<IncomingCall>, dismissed: List<String> = emptyList()) = IncomingCalls.toShow(calls, dismissed, now).map { it.id }

    @Test
    fun `shows the last three minutes' calls, the latest first`() {
        val calls = listOf(call("old", "+491761111111", 181), call("first", "+491762222222", 170), call("second", "+491763333333", 20))
        assertEquals(listOf("second", "first"), shown(calls))
        // To the second: three minutes ago is gone, a second less is there.
        assertEquals(emptyList(), shown(listOf(call("edge", "+491761111111", 180))))
        assertEquals(listOf("edge"), shown(listOf(call("edge", "+491761111111", 179))))
        // The database's clock a little ahead of the tablet's.
        assertEquals(listOf("ahead"), shown(listOf(call("ahead", "+491761111111", -30))))
        assertEquals(emptyList(), shown(emptyList()))
    }

    @Test
    fun `somebody who rings twice is one caller`() {
        val calls = listOf(call("again", "+491761234567", 10), call("before", "0176 1234567", 90), call("other", "+491769999999", 60))
        assertEquals(listOf("again", "other"), shown(calls))
    }

    @Test
    fun `a call put away stays away until the number rings again`() {
        val before = call("before", "+491761234567", 90)
        val other = call("other", "+491769999999", 60)
        assertEquals(listOf("other"), shown(listOf(other, before), dismissed = listOf("before")))
        // The earlier call does not come back in its place...
        val again = call("again", "+491761234567", 10)
        assertEquals(listOf("other"), shown(listOf(again, other, before), dismissed = listOf("again")))
        // ...but ringing again after it was put away shows.
        assertEquals(listOf("again", "other"), shown(listOf(again, other, before), dismissed = listOf("before")))
    }

    // -----------------------------------------------------------------------
    // The database
    // -----------------------------------------------------------------------

    @Test
    fun `files a call with its number and nothing else`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(201)

        backend.report("+491761234567")

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/incoming_calls", request.url.encodedPath)
        assertEquals("""{"phone":"+491761234567"}""", request.body!!.utf8())
        assertEquals("Bearer access-1", request.headers["Authorization"])
    }

    @Test
    fun `a report the database refuses fails, for the phone to shrug off`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(403, """{"code":"42501","message":"new row violates row-level security policy for table \"incoming_calls\""}""")

        assertFailsWith<BackendException> { backend.report("+491761234567") }
    }

    @Test
    fun `reads the calls since a moment ago, the latest first`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"id":"c-2","phone":"+491761234567","created_at":"2026-10-13T16:59:40.123456+00:00"},
                {"id":"c-1","phone":"+4934112345","created_at":"2026-10-13T16:58:00+00:00","created_by":"user-9"}]""",
        )

        val read = backend.since(Instant.parse("2026-10-13T16:56:00Z"))!!

        assertEquals(listOf("c-2", "c-1"), read.map { it.id })
        assertEquals("+491761234567", read[0].phone)
        assertEquals(Instant.parse("2026-10-13T16:58:00Z"), read[1].createdAt)
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/incoming_calls", request.url.encodedPath)
        assertEquals("id,phone,created_at", request.url.queryParameter("select"))
        assertEquals("gte.2026-10-13T16:56:00Z", request.url.queryParameter("created_at"))
        assertEquals("created_at.desc", request.url.queryParameter("order"))
    }

    @Test
    fun `asks where a number had deliveries before`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"street":"Karl-Heine-Straße 9","postal_code":"04229","city":"Leipzig","address_note":"Hinterhaus","last_ordered":"2026-10-11T18:00:00+00:00","count":2},
                {"street":"Lützner Straße 5","postal_code":"04177","city":"Leipzig","address_note":"","last_ordered":"2026-10-02T18:00:00+00:00","count":1}]""",
        )

        val found = backend.recentAddresses("0176 1234567")

        assertEquals(listOf(RecentAddress("Karl-Heine-Straße 9", "04229", "Leipzig", "Hinterhaus"), RecentAddress("Lützner Straße 5", "04177", "Leipzig")), found)
        assertEquals("Karl-Heine-Straße 9, 04229 Leipzig", found[0].line)
        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/rpc/staff_recent_addresses", request.url.encodedPath)
        assertEquals("""{"p_phone":"0176 1234567"}""", request.body!!.utf8())
    }

    @Test
    fun `is quiet on a database without the calls`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(404, """{"code":"PGRST205","message":"Could not find the table 'public.incoming_calls' in the schema cache"}""")
        test.reply(404, """{"code":"PGRST202","message":"Could not find the function public.staff_recent_addresses(p_phone) in the schema cache"}""")
        test.reply(200, "[]")

        assertNull(backend.since(now))
        assertEquals(emptyList(), backend.recentAddresses("0176 1234567"))
        // A number nobody has ordered with.
        assertEquals(emptyList(), backend.recentAddresses("0176 1234567"))
    }

    @Test
    fun `fails on any other refusal, for the caller to shrug off`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(500, """{"code":"XX000","message":"boom"}""")
        test.reply(403, """{"code":"42501","message":"staff only"}""")

        assertFailsWith<BackendException> { backend.since(now) }
        assertFailsWith<NotAllowedException> { backend.recentAddresses("0176 1234567") }
    }

    // -----------------------------------------------------------------------
    // The watch
    // -----------------------------------------------------------------------

    private class Fake(var calls: List<IncomingCall>?) : IncomingCallsBackend {
        val asked = mutableListOf<Instant>()
        var failing = false

        override suspend fun report(phone: String) = error("the tablet reports no calls")

        override suspend fun since(since: Instant): List<IncomingCall>? {
            asked += since
            if (failing) throw BackendException(500, "boom")
            return calls
        }

        override suspend fun recentAddresses(phone: String): List<RecentAddress> = emptyList()
    }

    private fun watch(fake: Fake) = CallWatch(fake, Logger.NONE, now = { now }, poll = 1.hours, debounce = Duration.ZERO)

    @Test
    fun `reads at once, and again when Realtime says the phone rang`() = runBlocking<Unit> {
        val first = call("first", "+491761111111", 60)
        val fake = Fake(listOf(first))
        val watch = watch(fake)
        val rang = MutableSharedFlow<Unit>()
        val running = launch { watch.run(rang) }

        assertEquals(CallsState(available = true, calls = listOf(first)), watch.state.first { it.available != null })
        // A little further back than is shown, for clocks that disagree.
        assertEquals(now.minusSeconds(240), fake.asked.single())

        val second = call("second", "+491762222222", 0)
        fake.calls = listOf(second, first)
        rang.subscriptionCount.first { it > 0 }
        rang.emit(Unit)
        assertEquals(listOf("second", "first"), watch.state.first { it.calls.size == 2 }.calls.map { it.id })

        // Unreadable for a moment: the calls read last stay up.
        fake.failing = true
        rang.emit(Unit)
        while (fake.asked.size < 3) kotlinx.coroutines.yield()
        assertEquals(2, watch.state.value.calls.size)

        watch.reset()
        assertEquals(CallsState(), watch.state.value)
        running.cancel()
    }

    @Test
    fun `says so on a database without the calls`() = runBlocking<Unit> {
        val watch = watch(Fake(null))
        val running = launch { watch.run(MutableSharedFlow()) }

        assertEquals(CallsState(available = false), watch.state.first { it.available != null })
        running.cancel()
    }
}
