package de.hamzabistro.printstation.core

import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlarmPolicyTest {
    private val policy = AlarmPolicy()

    /** 18:00 in Leipzig, in September: open, and outside the night window. */
    private val evening = at("2026-09-26T16:00:00Z")

    private val loud = AlarmSettings()

    private fun waiting(id: String, createdAt: String = "2026-09-26T15:59:00Z", scheduledFor: String? = null) =
        order(id, status = OrderStatus.NEW, createdAt = createdAt, confirmedAt = null, scheduledFor = scheduledFor)

    private fun decide(
        orders: List<StaffOrder>?,
        now: Instant = evening,
        settings: AlarmSettings = loud,
        failing: Boolean = false,
        printer: Problem? = null,
        lead: Int = 25,
    ) = policy.decide(orders, failing, printer, settings, now) { lead }

    @Test
    fun `rings for every new order until it leaves the queue, wherever it was answered`() {
        val a = waiting("a")
        assertEquals(listOf("a"), decide(listOf(a)).ringing.map { it.id })
        assertEquals(listOf("a"), decide(listOf(a), now = evening.plusSeconds(600)).ringing.map { it.id })
        // Accepted on another phone, in Telegram, on the website: gone from
        // the new orders, and quiet here.
        assertTrue(decide(listOf(a.copy(status = OrderStatus.CONFIRMED))).ringing.isEmpty())
    }

    @Test
    fun `rings the longest-waiting first`() {
        val older = waiting("old", createdAt = "2026-09-26T15:50:00Z")
        val newer = waiting("new", createdAt = "2026-09-26T15:58:00Z")
        assertEquals(listOf("old", "new"), decide(listOf(newer, older)).ringing.map { it.id })
    }

    @Test
    fun `stops after an hour, as the server's reminders do`() {
        val stale = waiting("a", createdAt = "2026-09-26T14:59:00Z")
        assertTrue(decide(listOf(stale)).ringing.isEmpty())
    }

    @Test
    fun `rings a pre-order still waiting in the hour before its time, and not between`() {
        val pre = waiting("a", createdAt = "2026-09-26T08:00:00Z", scheduledFor = "2026-09-26T16:30:00Z")
        assertTrue(decide(listOf(pre), now = at("2026-09-26T15:00:00Z")).ringing.isEmpty())
        assertEquals(1, decide(listOf(pre), now = at("2026-09-26T15:31:00Z")).ringing.size)
        assertTrue(decide(listOf(pre), now = at("2026-09-26T16:31:00Z")).ringing.isEmpty())
    }

    @Test
    fun `silence holds for what was ringing, and an order that arrives meanwhile rings at once`() {
        val a = waiting("a")
        decide(listOf(a))
        policy.silence(evening, 60)
        val silenced = decide(listOf(a), now = evening.plusSeconds(10))
        assertTrue(silenced.ringing.isEmpty())
        assertEquals(evening.plusSeconds(60), silenced.silencedUntil)

        val b = waiting("b", createdAt = "2026-09-26T16:00:05Z")
        assertEquals(listOf("b"), decide(listOf(a, b), now = evening.plusSeconds(20)).ringing.map { it.id })
        // Silencing that one does not wake the first.
        policy.silence(evening.plusSeconds(20), 60)
        assertTrue(decide(listOf(a, b), now = evening.plusSeconds(30)).ringing.isEmpty())
        // Still waiting a minute on: both ring again.
        assertEquals(listOf("a", "b"), decide(listOf(a, b), now = evening.plusSeconds(81)).ringing.map { it.id })
    }

    @Test
    fun `once a message, it chimes once per order and never rings`() {
        val once = loud.copy(newOrders = NewOrderAlarm.ONCE)
        val first = decide(listOf(waiting("a")), settings = once)
        assertTrue(first.ringing.isEmpty())
        assertEquals(listOf<Chime>(Chime.NewOrder(waiting("a"))), first.chimes)
        assertTrue(decide(listOf(waiting("a")), settings = once).chimes.isEmpty())
        assertEquals(1, decide(listOf(waiting("a"), waiting("b")), settings = once).chimes.size)
    }

    @Test
    fun `in the night window a new order is a silent notification, not a ring`() {
        val night = at("2026-09-26T21:30:00Z") // 23:30 in Leipzig
        val late = waiting("a", createdAt = "2026-09-26T21:29:00Z")
        val decision = decide(listOf(late), now = night)
        assertTrue(decision.quiet)
        assertTrue(decision.ringing.isEmpty())
        assertIs<Chime.NewOrder>(decision.chimes.single())
    }

    @Test
    fun `knows the night window across midnight, and none at all`() {
        assertTrue(policy.isQuiet(loud, at("2026-09-26T05:00:00Z"))) // 07:00
        assertTrue(!policy.isQuiet(loud, at("2026-09-26T07:00:00Z"))) // 09:00
        val daytime = loud.copy(quietFrom = LocalTime.of(14, 0), quietTo = LocalTime.of(15, 0))
        assertTrue(policy.isQuiet(daytime, at("2026-09-26T12:30:00Z")))
        assertTrue(!policy.isQuiet(loud.copy(quietFrom = null), at("2026-09-26T21:30:00Z")))
    }

    @Test
    fun `chimes once when an accepted pre-order has to go on, not for one already late at the start`() {
        val pre = order("pre", createdAt = "2026-09-26T10:00:00Z", scheduledFor = "2026-09-26T16:30:00Z")
        val alreadyLate = order("late", createdAt = "2026-09-26T10:00:00Z", scheduledFor = "2026-09-26T16:10:00Z")
        // First look at 18:00: "late" should have gone on at 17:45.
        assertTrue(decide(listOf(pre, alreadyLate)).chimes.isEmpty())
        assertTrue(decide(listOf(pre, alreadyLate), now = at("2026-09-26T16:04:00Z")).chimes.isEmpty())
        assertEquals(listOf<Chime>(Chime.CookNow(pre)), decide(listOf(pre, alreadyLate), now = at("2026-09-26T16:05:10Z")).chimes)
        assertTrue(decide(listOf(pre, alreadyLate), now = at("2026-09-26T16:06:00Z")).chimes.isEmpty())
    }

    @Test
    fun `chimes about the printer after half a minute and every three minutes while it lasts`() {
        val trouble = Problem.NotPrinted(57, "the printer is off")
        assertTrue(decide(emptyList(), printer = trouble).chimes.isEmpty())
        assertEquals(
            listOf<Chime>(Chime.PrinterFailed(57, "the printer is off")),
            decide(emptyList(), printer = trouble, now = evening.plusSeconds(30)).chimes,
        )
        assertTrue(decide(emptyList(), printer = trouble, now = evening.plusSeconds(120)).chimes.isEmpty())
        assertEquals(1, decide(emptyList(), printer = trouble, now = evening.plusSeconds(210)).chimes.size)
        // Back on: a new failure starts the count again.
        decide(emptyList(), now = evening.plusSeconds(220))
        assertTrue(decide(emptyList(), printer = trouble, now = evening.plusSeconds(230)).chimes.isEmpty())
    }

    @Test
    fun `chimes once when the queue has not been readable for two minutes`() {
        assertTrue(decide(null, failing = true).chimes.isEmpty())
        assertEquals(listOf<Chime>(Chime.Offline(evening)), decide(null, failing = true, now = evening.plusSeconds(120)).chimes)
        assertTrue(decide(null, failing = true, now = evening.plusSeconds(600)).chimes.isEmpty())
    }

    @Test
    fun `switched off, a new order neither rings nor chimes`() {
        val off = loud.copy(newOrders = NewOrderAlarm.OFF)
        val decision = decide(listOf(waiting("a")), settings = off)
        assertTrue(decision.ringing.isEmpty())
        assertTrue(decision.chimes.isEmpty())
        assertEquals(1, decision.waiting.size)
    }
}
