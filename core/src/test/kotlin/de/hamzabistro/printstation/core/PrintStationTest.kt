package de.hamzabistro.printstation.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PrintStationTest {
    /** The database, as far as printing goes: a queue, and what each call did to it. */
    private class FakeBackend : PrintBackend {
        val queue = mutableListOf<QueuedOrder>()
        val claims = mutableMapOf<String, Claim>()
        val calls = mutableListOf<String>()
        val gone = mutableSetOf<String>()
        var failQueue: Exception? = null
        var failFinish = false
        var seen = 0

        override suspend fun canPrint() = true

        override suspend fun openOrders(): List<QueuedOrder> {
            failQueue?.let { throw it }
            return queue.toList()
        }

        override suspend fun claim(order: String): Claim {
            calls += "claim $order"
            return claims[order] ?: Claim.CLAIMED
        }

        override suspend fun finish(order: String, printed: Boolean) {
            calls += "finish $order $printed"
            if (failFinish) throw IOException("offline")
            if (printed) queue.removeAll { it.id == order }
        }

        override suspend fun seen(station: String, label: String) {
            assertEquals("station-id-1", station)
            assertEquals("Android-App", label)
            seen++
        }

        override suspend fun off(station: String) = Unit

        override suspend fun ticket(order: String, lang: String): ByteArray? {
            assertEquals("de", lang)
            calls += "ticket $order"
            return if (order in gone) null else "ticket $order".toByteArray()
        }

        override suspend fun testTicket(lang: String) = ByteArray(0)
    }

    private class FakePrinter : TicketPrinter {
        val printed = mutableListOf<String>()
        var failing: Exception? = null
        var hanging = false

        override suspend fun print(ticket: ByteArray) {
            if (hanging) awaitCancellation()
            failing?.let { throw it }
            printed += String(ticket)
        }
    }

    private val backend = FakeBackend()
    private val printer = FakePrinter()
    private val log = MemoryPrintLog()

    private fun TestScope.station() =
        PrintStation(
            backend = backend,
            printer = printer,
            log = log,
            station = "station-id-1",
            label = "Android-App",
            lang = "de",
            logger = Logger.NONE,
            now = { testScheduler.currentTime },
            wallClock = { 1_000_000 + testScheduler.currentTime },
        )

    private fun order(n: Long) = QueuedOrder("order-$n", n)

    @Test
    fun `prints each accepted order once, taking it first and recording it after`() = runTest {
        backend.queue += listOf(order(1), order(2))
        val station = station()
        backgroundScope.launch { station.run(emptyFlow()) }
        runCurrent()

        assertEquals(listOf("ticket order-1", "ticket order-2"), printer.printed)
        assertEquals(
            listOf(
                "claim order-1", "ticket order-1", "finish order-1 true",
                "claim order-2", "ticket order-2", "finish order-2 true",
            ),
            backend.calls,
        )
        assertEquals(2L, station.status.value.lastPrinted)
        assertNull(station.status.value.problem)

        advanceTimeBy(26.seconds)
        assertEquals(2, printer.printed.size)
    }

    @Test
    fun `leaves an order another station is printing, and takes it if that one fails`() = runTest {
        backend.queue += order(1)
        backend.claims["order-1"] = Claim.BUSY
        backgroundScope.launch { station().run(emptyFlow()) }
        runCurrent()
        assertTrue(printer.printed.isEmpty())

        backend.claims.remove("order-1")
        advanceTimeBy(26.seconds)
        assertEquals(listOf("ticket order-1"), printer.printed)
    }

    @Test
    fun `never prints what was printed elsewhere or moved on`() = runTest {
        backend.queue += listOf(order(1), order(2))
        backend.claims["order-1"] = Claim.PRINTED
        backend.claims["order-2"] = Claim.MOVED
        backgroundScope.launch { station().run(emptyFlow()) }
        runCurrent()
        advanceTimeBy(26.seconds)

        assertTrue(printer.printed.isEmpty())
        // Asked once each: after that they are none of this station's business.
        assertEquals(listOf("claim order-1", "claim order-2"), backend.calls)
    }

    @Test
    fun `a printer that is off stops the round, gives the order back, and catches up when on`() = runTest {
        backend.queue += listOf(order(7), order(8))
        printer.failing = PrinterException("Bluetooth is off")
        val station = station()
        backgroundScope.launch { station.run(emptyFlow()) }
        runCurrent()

        assertEquals(listOf("claim order-7", "ticket order-7", "finish order-7 false"), backend.calls)
        val problem = assertIs<Problem.NotPrinted>(station.status.value.problem)
        assertEquals(7L, problem.order)

        printer.failing = null
        advanceTimeBy(26.seconds)
        assertEquals(listOf("ticket order-7", "ticket order-8"), printer.printed)
        assertNull(station.status.value.problem)
    }

    @Test
    fun `gives up on a ticket after twenty seconds`() = runTest {
        backend.queue += order(3)
        printer.hanging = true
        val station = station()
        backgroundScope.launch { station.run(emptyFlow()) }
        advanceTimeBy(19.seconds)
        assertNull(station.status.value.problem)
        advanceTimeBy(2.seconds)

        assertIs<Problem.NotPrinted>(station.status.value.problem)
        assertEquals("finish order-3 false", backend.calls.last())
    }

    @Test
    fun `a ticket printed but not recorded is recorded later, not printed again`() = runTest {
        backend.queue += order(4)
        backend.failFinish = true
        backgroundScope.launch { station().run(emptyFlow()) }
        runCurrent()
        assertEquals(setOf("order-4"), log.unreported())

        backend.failFinish = false
        advanceTimeBy(26.seconds)
        assertEquals(listOf("ticket order-4"), printer.printed)
        assertTrue(log.unreported().isEmpty())
        assertTrue(backend.queue.isEmpty())
    }

    @Test
    fun `an order that moved on between the claim and the ticket is not printed`() = runTest {
        backend.queue += order(5)
        backend.gone += "order-5"
        backgroundScope.launch { station().run(emptyFlow()) }
        runCurrent()

        assertTrue(printer.printed.isEmpty())
        assertTrue(log.isDone("order-5"))
        assertEquals("finish order-5 false", backend.calls.last())
    }

    @Test
    fun `says it is there at once and then once a minute`() = runTest {
        backgroundScope.launch { station().run(emptyFlow()) }
        runCurrent()
        assertEquals(1, backend.seen)
        advanceTimeBy(51.seconds) // two polls
        assertEquals(1, backend.seen)
        advanceTimeBy(25.seconds)
        assertEquals(2, backend.seen)
    }

    @Test
    fun `Realtime has it look within a couple of seconds`() = runTest {
        val changes = MutableSharedFlow<Unit>()
        backgroundScope.launch { station().run(changes) }
        runCurrent()
        advanceTimeBy(5.seconds)

        backend.queue += order(9)
        changes.emit(Unit)
        advanceTimeBy(1.seconds)
        assertTrue(printer.printed.isEmpty())
        advanceTimeBy(1.seconds)
        assertEquals(listOf("ticket order-9"), printer.printed)
    }

    @Test
    fun `offline is a problem to show, and the next look tries again`() = runTest {
        backend.failQueue = IOException("no network")
        val station = station()
        backgroundScope.launch { station.run(emptyFlow()) }
        runCurrent()
        assertIs<Problem.Offline>(station.status.value.problem)

        backend.failQueue = null
        backend.queue += order(6)
        advanceTimeBy(26.seconds)
        assertEquals(listOf("ticket order-6"), printer.printed)
    }

    @Test
    fun `an account that may not print says so`() = runTest {
        backend.failQueue = NotAllowedException()
        val station = station()
        backgroundScope.launch { station.run(emptyFlow()) }
        runCurrent()
        assertEquals(Problem.NotAllowed, station.status.value.problem)
    }

    @Test
    fun `ends when the session has ended`() = runTest {
        backend.failQueue = SignedOutException()
        assertFailsWith<SignedOutException> { station().run(emptyFlow()) }
    }

    @Test
    fun `switching on leaves what was accepted before to others`() = runTest {
        backend.queue += listOf(order(1), order(2))
        val station = station()
        station.skipWaiting()
        backend.queue += order(3)
        backgroundScope.launch { station.run(emptyFlow()) }
        runCurrent()
        assertEquals(listOf("ticket order-3"), printer.printed)
    }
}
