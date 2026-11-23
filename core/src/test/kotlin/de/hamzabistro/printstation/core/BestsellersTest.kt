package de.hamzabistro.printstation.core

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class BestsellersTest {
    private val friday = LocalDate.parse("2026-11-27")

    private fun sales(day: LocalDate, vararg dishes: Long, counter: Boolean = false, times: Int = 1) =
        List(times) { DishSales(day.atTime(18, 0).atZone(AlarmPolicy.LEIPZIG).toInstant(), counter, dishes.toSet()) }

    private fun dish(id: Long) = MenuDish(id, "Döner", "Dish $id", "", 5.0, 10, id.toInt(), "", true, emptyList())

    @Test
    fun `what is in most orders comes first`() {
        val day = friday.minusDays(1)
        val ranked = Bestsellers.rank(sales(day, 1, times = 3) + sales(day, 2, times = 5) + sales(day, 3, times = 4), friday)
        assertEquals(listOf(2L, 3L, 1L), ranked)
    }

    @Test
    fun `the same day of the week counts for more than the others`() {
        val onFridays = (1L..3L).flatMap { sales(friday.minusWeeks(it), 1, times = 4) }
        val onSaturdays = (1L..3L).flatMap { sales(friday.minusWeeks(it).plusDays(1), 2, times = 6) }
        assertEquals(listOf(1L, 2L), Bestsellers.rank(onFridays + onSaturdays, friday))
        assertEquals(listOf(2L, 1L), Bestsellers.rank(onFridays + onSaturdays, friday.plusDays(1)))
    }

    @Test
    fun `a day of the week without orders of its own is ranked by the other days`() {
        val ranked = Bestsellers.rank(sales(friday.minusDays(2), 1, times = 3) + sales(friday.minusDays(3), 2, times = 9), friday)
        assertEquals(listOf(2L, 1L), ranked)
    }

    @Test
    fun `what is asked for at the counter counts for more than a delivery`() {
        val day = friday.minusDays(1)
        val ranked = Bestsellers.rank(sales(day, 1, times = 8) + sales(day, 2, counter = true, times = 4), friday)
        assertEquals(listOf(2L, 1L), ranked)
    }

    @Test
    fun `the newer counts for more`() {
        val ranked = Bestsellers.rank(sales(friday.minusDays(50), 1, times = 5) + sales(friday.minusDays(1), 2, times = 4), friday)
        assertEquals(listOf(2L, 1L), ranked)
    }

    @Test
    fun `today, what is too old, and what sold too seldom are left out`() {
        val ranked =
            Bestsellers.rank(
                sales(friday, 1, times = 9) +
                    sales(friday.minusDays(Bestsellers.DAYS + 1), 2, times = 9) +
                    sales(friday.minusDays(7), 3, counter = true, times = Bestsellers.MIN_ORDERS - 1) +
                    sales(friday.minusDays(1), 4, times = Bestsellers.MIN_ORDERS),
                friday,
            )
        assertEquals(listOf(4L), ranked)
    }

    @Test
    fun `the day is Leipzig's`() {
        // 23:30 UTC on Thursday is Friday in Leipzig.
        val late = DishSales(Instant.parse("2026-11-19T23:30:00Z"), false, setOf(1))
        val thursday = sales(friday.minusDays(8), 2, times = 4)
        assertEquals(listOf(1L, 2L), Bestsellers.rank(List(3) { late } + thursday, friday))
    }

    @Test
    fun `as much sold is the same order every time`() {
        assertEquals(listOf(1L, 2L, 3L), Bestsellers.rank(sales(friday.minusDays(1), 3, 1, 2, times = 3), friday))
    }

    @Test
    fun `the bestsellers come first and the others keep their places`() {
        val menu = (1L..5L).map(::dish)
        // 99 is not on the menu any more.
        val arranged = Bestsellers.onTop(menu, listOf(4, 99, 2))
        assertEquals(listOf(4L, 2L, 1L, 3L, 5L), arranged.dishes.map { it.id })
        assertEquals(2, arranged.top)
        assertEquals(Bestsellers.Arranged(menu, 0), Bestsellers.onTop(menu, emptyList()))
    }

    @Test
    fun `no more than a screen of them`() {
        val menu = (1L..30L).map(::dish)
        val arranged = Bestsellers.onTop(menu, (30L downTo 1L).toList())
        assertEquals(Bestsellers.TOP, arranged.top)
        assertEquals((30L downTo 19L).toList() + (1L..18L).toList(), arranged.dishes.map { it.id })
    }

    // -----------------------------------------------------------------------

    private class Sales(var answer: () -> List<DishSales>) : DishSalesBackend {
        val asked = mutableListOf<Pair<Instant, Instant>>()

        override suspend fun dishSales(from: Instant, until: Instant): List<DishSales> {
            asked += from to until
            return answer()
        }
    }

    @Test
    fun `is read once a day`() = runBlocking<Unit> {
        var now = Instant.parse("2026-11-27T09:00:00Z")
        val backend = Sales { sales(friday.minusDays(1), 1, times = 3) }
        val ranking = BestsellerRanking(backend, { now })
        assertEquals(listOf(1L), ranking.today())
        now = Instant.parse("2026-11-27T21:00:00Z")
        assertEquals(listOf(1L), ranking.today())
        // Eight weeks, from midnight to midnight in Leipzig, the clocks changed in between.
        assertEquals(listOf(Instant.parse("2026-10-01T22:00:00Z") to Instant.parse("2026-11-26T23:00:00Z")), backend.asked)

        now = Instant.parse("2026-11-27T23:30:00Z")
        ranking.today()
        assertEquals(2, backend.asked.size)
    }

    @Test
    fun `without an answer it is the one before, and asked again`() = runBlocking<Unit> {
        var now = Instant.parse("2026-11-27T09:00:00Z")
        val backend = Sales { throw IOException("offline") }
        val ranking = BestsellerRanking(backend, { now })
        assertEquals(emptyList(), ranking.today())
        backend.answer = { sales(friday.minusDays(1), 1, times = 3) }
        assertEquals(listOf(1L), ranking.today())
        now = Instant.parse("2026-11-28T09:00:00Z")
        backend.answer = { throw IOException("offline") }
        assertEquals(listOf(1L), ranking.today())
        assertEquals(listOf(1L), ranking.known)
        ranking.today()
        assertEquals(4, backend.asked.size)
    }

    @Test
    fun `no orders at all is not kept for the day`() = runBlocking<Unit> {
        val backend = Sales { emptyList() }
        val ranking = BestsellerRanking(backend, { Instant.parse("2026-11-27T09:00:00Z") })
        assertEquals(emptyList(), ranking.today())
        // Somebody who may read them has signed in.
        backend.answer = { sales(friday.minusDays(1), 1, times = 3) }
        assertEquals(listOf(1L), ranking.today())
    }

    // -----------------------------------------------------------------------

    private val test = TestServer()
    private val store = MemorySessionStore(StoredSession("refresh-0", Account("user-1", null)))
    private val backend =
        SupabaseDishSalesBackend(test.config, test.client, SessionManager(SupabaseAuth(test.config, test.client) { 0L }, store) { 0L })

    @AfterTest fun close() = test.close()

    private val from = Instant.parse("2026-10-01T22:00:00Z")
    private val until = Instant.parse("2026-11-26T23:00:00Z")

    @Test
    fun `reads delivered orders, no tests, and nothing about the customer`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        test.reply(
            200,
            """[{"created_at":"2026-11-26T17:00:00+00:00","scheduled_for":null,"source":"counter",""" +
                """"items":[{"id":1,"name":"Döner","qty":2},{"id":"2","qty":1},{"id":1,"qty":1},{"name":"gone"},7]},""" +
                """{"created_at":"2026-11-25T10:00:00.123456+00:00","scheduled_for":"2026-11-26T18:30:00+00:00","source":"web","items":null}]""",
        )

        assertEquals(
            listOf(
                DishSales(Instant.parse("2026-11-26T17:00:00Z"), true, setOf(1, 2)),
                DishSales(Instant.parse("2026-11-26T18:30:00Z"), false, emptySet()),
            ),
            backend.dishSales(from, until),
        )

        test.server.takeRequest()
        val request = test.server.takeRequest()
        assertEquals("/rest/v1/orders", request.url.encodedPath)
        assertEquals("created_at,scheduled_for,source,items", request.url.queryParameter("select"))
        assertEquals("eq.delivered", request.url.queryParameter("status"))
        assertEquals("eq.false", request.url.queryParameter("is_test"))
        assertEquals(listOf<String?>("gte.$from", "lt.$until"), request.url.queryParameterValues("created_at"))
        assertEquals("0", request.url.queryParameter("offset"))
    }

    @Test
    fun `reads on where the server gives no more at once`() = runBlocking<Unit> {
        test.token("access-1", "refresh-1")
        val row = """{"created_at":"2026-11-26T17:00:00+00:00","source":"web","items":[{"id":1}]}"""
        test.reply(200, List(1000) { row }.joinToString(",", "[", "]"))
        test.reply(200, "[$row]")

        assertEquals(1001, backend.dishSales(from, until).size)

        test.server.takeRequest()
        assertEquals("0", test.server.takeRequest().url.queryParameter("offset"))
        assertEquals("1000", test.server.takeRequest().url.queryParameter("offset"))
    }
}
