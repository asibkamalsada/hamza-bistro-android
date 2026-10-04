package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class OrderRatingsTest {
    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val sessions = SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L }
    private val backend = SupabaseRatingsBackend(test.config, test.client, sessions)

    @AfterTest fun close() = test.close()

    private fun rating(id: String, stars: Int, comment: String = "", at: String = "2026-10-03T18:00:00Z") =
        OrderRating(id = id, orderId = "o-$id", stars = stars, comment = comment, createdAt = Instant.parse(at))

    @Test
    fun `draws the stars and says which ratings are bad news`() {
        assertEquals("★★★★☆", OrderRatings.starsText(4))
        assertEquals("★☆☆☆☆", OrderRatings.starsText(1))
        assertEquals("☆☆☆☆☆", OrderRatings.starsText(-3))
        assertEquals("★★★★★", OrderRatings.starsText(9))

        assertTrue(OrderRatings.isLow(rating("a", 2)))
        assertFalse(OrderRatings.isLow(rating("a", 3)))
        assertTrue(OrderRatings.isAlarming(rating("a", 1, "Kalt angekommen")))
        assertTrue(OrderRatings.isAlarming(rating("a", 2, " zu spät ")))
        // No comment, or a blank one: nothing to call about.
        assertFalse(OrderRatings.isAlarming(rating("a", 1)))
        assertFalse(OrderRatings.isAlarming(rating("a", 1, "  \n ")))
        assertFalse(OrderRatings.isAlarming(rating("a", 3, "Geht so")))

        assertEquals("Kalt", OrderRatings.comment(rating("a", 1, " Kalt ")))
        assertNull(OrderRatings.comment(rating("a", 5, " ")))
    }

    @Test
    fun `looks a day back`() {
        assertEquals(Instant.parse("2026-10-02T18:00:00Z"), OrderRatings.since(Instant.parse("2026-10-03T18:00:00Z")))
    }

    @Test
    fun `reads the ratings on the orders listed, by order`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"id":"r-1","order_id":"o-1","stars":4,"comment":"","created_at":"2026-10-03T19:00:00+00:00"},
                {"id":"r-2","order_id":"o-2","stars":1,"comment":"Kalt","created_at":"2026-10-03T19:05:00+00:00"}]""",
        )

        val read = backend.forOrders(listOf("o-1", "o-2", "o-3"))

        assertEquals(setOf("o-1", "o-2"), read.keys)
        assertEquals(4, read.getValue("o-1").stars)
        assertEquals("Kalt", read.getValue("o-2").comment)
        assertEquals(Instant.parse("2026-10-03T19:05:00Z"), read.getValue("o-2").createdAt)
        assertEquals(emptyMap(), backend.forOrders(emptyList()))

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/order_ratings", request.url.encodedPath)
        assertEquals("id,order_id,stars,comment,created_at", request.url.queryParameter("select"))
        assertEquals("in.(o-1,o-2,o-3)", request.url.queryParameter("order_id"))
    }

    @Test
    fun `reads the last day's ratings, newest first`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(200, """[{"id":"r-2","order_id":"o-2","stars":2,"comment":"Zu spät","created_at":"2026-10-03T19:05:00+00:00"}]""")

        assertEquals(listOf("r-2"), backend.since(Instant.parse("2026-10-02T19:30:00Z"))!!.map { it.id })

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/order_ratings", request.url.encodedPath)
        assertEquals(OrderRatings.COLUMNS, request.url.queryParameter("select"))
        assertEquals("gte.2026-10-02T19:30:00Z", request.url.queryParameter("created_at"))
        assertEquals("created_at.desc", request.url.queryParameter("order"))
    }

    @Test
    fun `is quiet on a database without the ratings`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val missing = """{"code":"PGRST205","message":"Could not find the table 'public.order_ratings' in the schema cache"}"""
        test.reply(404, missing)
        test.reply(404, missing)

        assertEquals(emptyMap(), backend.forOrders(listOf("o-1")))
        assertNull(backend.since(Instant.parse("2026-10-02T19:30:00Z")))
    }

    @Test
    fun `fails on any other refusal, for the caller to shrug off`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(500, """{"code":"XX000","message":"boom"}""")

        assertFailsWith<BackendException> { backend.forOrders(listOf("o-1")) }
    }

    // -----------------------------------------------------------------------
    // The chime
    // -----------------------------------------------------------------------

    private val policy = AlarmPolicy()
    private val now = Instant.parse("2026-10-03T18:00:00Z")
    private val kitchen = AlarmSettings(badRatings = true)

    private fun chimes(ratings: List<OrderRating>?, settings: AlarmSettings = kitchen) =
        policy.decide(emptyList(), false, null, settings, now, ratings = ratings) { 25 }.chimes.filterIsInstance<Chime.BadRating>()

    private val bad = rating("bad", 1, "Kalt und zu spät", "2026-10-03T17:00:00Z")

    @Test
    fun `is on for the kitchen tablet and off for a driver's phone until somebody chooses`() {
        assertEquals(true, AlarmSettings().forDevice(driver = false).badRatings)
        assertEquals(false, AlarmSettings().forDevice(driver = true).badRatings)
        assertEquals(false, AlarmSettings(badRatings = false).forDevice(driver = false).badRatings)
        assertEquals(true, AlarmSettings(badRatings = true).forDevice(driver = true).badRatings)
        // Chosen apart from the reports.
        val chosen = AlarmSettings(issues = true).forDevice(driver = true)
        assertEquals(true, chosen.issues)
        assertEquals(false, chosen.badRatings)
    }

    @Test
    fun `chimes once for a bad rating with a comment, not for what was there on the first read`() {
        assertEquals(emptyList(), chimes(null))
        assertEquals(emptyList(), chimes(listOf(bad)))
        val worse = rating("worse", 2, "Falsches Gericht", "2026-10-03T17:50:00Z")
        assertEquals(listOf(Chime.BadRating(worse, 1)), chimes(listOf(worse, bad)))
        // Read again by Realtime and the poll: once is once.
        assertEquals(emptyList(), chimes(listOf(worse, bad)))
    }

    @Test
    fun `is silent for a good rating, and for a bad one that says nothing`() {
        chimes(emptyList())
        assertEquals(emptyList(), chimes(listOf(rating("good", 5, "Lecker!"), rating("mute", 1), rating("blank", 2, "   "))))
    }

    @Test
    fun `names the newest of several and counts them`() {
        chimes(emptyList())
        val older = rating("older", 2, "Kalt", "2026-10-03T17:10:00Z")
        val newer = rating("newer", 1, "Fehlt was", "2026-10-03T17:40:00Z")
        assertEquals(listOf(Chime.BadRating(newer, 2)), chimes(listOf(newer, rating("fine", 4), older)))
    }

    @Test
    fun `takes in what is there without a word after reading was lost`() {
        chimes(emptyList())
        chimes(null)
        assertEquals(emptyList(), chimes(listOf(bad)))
    }

    @Test
    fun `is silent on a device that does not want it, and stays silent when it is switched on`() {
        val driver = AlarmSettings(badRatings = false)
        chimes(emptyList(), driver)
        assertEquals(emptyList(), chimes(listOf(bad), driver))
        // Switched on: what came in while it was off is not news.
        assertEquals(emptyList(), chimes(listOf(bad)))
        val next = rating("next", 1, "Nie wieder", "2026-10-03T17:55:00Z")
        assertEquals(listOf(Chime.BadRating(next, 1)), chimes(listOf(next, bad)))
    }

    // -----------------------------------------------------------------------
    // The watch
    // -----------------------------------------------------------------------

    private class FakeRatings(var recent: List<OrderRating>?, var failing: Boolean = false) : RatingsBackend {
        var looks = 0
        var asked: Instant? = null

        override suspend fun forOrders(orderIds: Collection<String>): Map<String, OrderRating> = emptyMap()

        override suspend fun since(since: Instant): List<OrderRating>? {
            looks++
            asked = since
            if (failing) throw BackendException(503, "down")
            return recent
        }
    }

    @Test
    fun `reads on a change, keeps the last read when a look fails, and says when there are no ratings`() = runBlocking<Unit> {
        val fake = FakeRatings(listOf(bad))
        val watch =
            RatingWatch(fake, Logger.NONE, now = { now }, poll = kotlin.time.Duration.parse("1h"), debounce = kotlin.time.Duration.ZERO)
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { watch.run(changes) }

        suspend fun until(check: (RatingsState) -> Boolean) =
            kotlinx.coroutines.withTimeout(5_000) { watch.state.first { check(it) } }

        assertEquals(RatingsState(true, listOf(bad)), until { it.available == true })
        assertEquals(Instant.parse("2026-10-02T18:00:00Z"), fake.asked)
        val more = rating("more", 2, "Kalt")
        fake.recent = listOf(more, bad)
        kotlinx.coroutines.withTimeout(5_000) { while (changes.subscriptionCount.value == 0) kotlinx.coroutines.delay(10) }
        changes.emit(Unit)
        until { it.recent.size == 2 }
        fake.failing = true
        changes.emit(Unit)
        kotlinx.coroutines.withTimeout(5_000) { while (fake.looks < 3) kotlinx.coroutines.delay(10) }
        assertEquals(2, watch.state.value.recent.size)
        fake.failing = false
        fake.recent = null
        changes.emit(Unit)
        assertEquals(RatingsState(available = false), until { it.available == false })
        job.cancel()
    }
}
