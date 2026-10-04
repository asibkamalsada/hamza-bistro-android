package de.hamzabistro.printstation.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class OrderQueueTest {
    private class FakeBackend : StaffBackend {
        var orders = listOf<StaffOrder>()
        var reads = 0
        var failing: Exception? = null
        var staff = true
        val seen = mutableListOf<DeviceReport>()

        override suspend fun isStaff() = staff

        override suspend fun openOrders(): List<StaffOrder> {
            reads++
            failing?.let { throw it }
            return orders
        }

        override suspend fun move(order: StaffOrder, step: OrderStep) = Unit

        override suspend fun delayOpen(minutes: Int) = 0

        override suspend fun resetNoShows(order: StaffOrder) = 0

        override suspend fun prepMinutes() = mapOf(1L to 5)

        override suspend fun seen(device: DeviceReport) {
            if (!staff) throw NotAllowedException()
            seen += device
        }

        override suspend fun off(device: String) = Unit
    }

    private val backend = FakeBackend()
    private var onShift = true

    private fun TestScope.queue() =
        OrderQueue(
            backend = backend,
            logger = Logger.NONE,
            now = { testScheduler.currentTime },
            wallClock = { 1_000_000 + testScheduler.currentTime },
            report = { if (onShift) DeviceReport("device-1234", "Küche", "loop") else null },
        )

    @Test
    fun `reads the queue at once, on every change, and every 25 seconds besides`() = runTest {
        backend.orders = listOf(order("a"))
        val changes = MutableSharedFlow<Unit>()
        val queue = queue()
        backgroundScope.launch { queue.run(changes) }
        runCurrent()
        assertEquals(1, backend.reads)
        assertEquals(listOf("a"), queue.state.value.orders.map { it.id })
        assertTrue(queue.state.value.loaded)
        assertEquals(mapOf(1L to 5), queue.state.value.prep)

        backend.orders = listOf(order("a"), order("b"))
        changes.emit(Unit)
        changes.emit(Unit)
        advanceTimeBy(1.seconds)
        assertEquals(2, backend.reads)
        assertEquals(2, queue.state.value.orders.size)

        // Not at 25 seconds, which Realtime's look just covered, but at 50.
        advanceTimeBy(24.seconds)
        assertEquals(2, backend.reads)
        advanceTimeBy(26.seconds)
        assertEquals(3, backend.reads)
    }

    @Test
    fun `keeps the last queue while reading fails, and says since when`() = runTest {
        backend.orders = listOf(order("a"))
        val queue = queue()
        backgroundScope.launch { queue.run(emptyFlow()) }
        runCurrent()

        backend.failing = IOException("offline")
        advanceTimeBy(26.seconds)
        assertEquals(listOf("a"), queue.state.value.orders.map { it.id })
        assertEquals(1_025_000L, queue.state.value.failingSince)
        assertEquals("offline", queue.state.value.problem)

        backend.failing = null
        queue.refresh()
        runCurrent()
        assertNull(queue.state.value.failingSince)
    }

    @Test
    fun `says it is on shift once a minute, and learns when it is staff no more`() = runTest {
        val queue = queue()
        backgroundScope.launch { queue.run(emptyFlow()) }
        runCurrent()
        assertEquals(1, backend.seen.size)
        // Looks at 25 and 50 seconds are too soon to say it again; 75 is not.
        advanceTimeBy(51.seconds)
        assertEquals(1, backend.seen.size)
        advanceTimeBy(25.seconds)
        assertEquals(2, backend.seen.size)

        backend.staff = false
        advanceTimeBy(75.seconds)
        assertTrue(queue.state.value.notAllowed)
    }

    @Test
    fun `says nothing about a device that is not on shift`() = runTest {
        onShift = false
        val queue = queue()
        backgroundScope.launch { queue.run(emptyFlow()) }
        runCurrent()
        assertTrue(backend.seen.isEmpty())
        assertFalse(queue.state.value.notAllowed)
    }
}
