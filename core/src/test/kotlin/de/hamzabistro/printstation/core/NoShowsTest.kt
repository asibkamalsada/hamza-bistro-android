package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer

class NoShowsTest {
    private fun cancelled(reason: String) =
        """{"id":"a","order_number":57,"created_at":"2026-10-04T16:00:00+00:00","status":"cancelled","cancel_reason":$reason}"""

    private fun read(vararg rows: String): List<StaffOrder> =
        json.decodeFromString(ListSerializer(StaffOrder.serializer()), rows.joinToString(",", "[", "]"))

    @Test
    fun `every reason reads by its name, no_show among them`() {
        val reasons = listOf("\"busy\"", "\"sold_out\"", "\"unreachable\"", "\"address\"", "\"timeout\"", "\"no_show\"", "null")
        assertEquals(
            listOf(
                CancelReason.BUSY,
                CancelReason.SOLD_OUT,
                CancelReason.UNREACHABLE,
                CancelReason.ADDRESS,
                CancelReason.TIMEOUT,
                CancelReason.NO_SHOW,
                null,
            ),
            read(*reasons.map(::cancelled).toTypedArray()).map { it.cancelReason },
        )
    }

    @Test
    fun `a reason this app does not know reads as unknown, and the rest of the list still loads`() {
        val orders = read(cancelled("\"lost_in_space\""), cancelled("\"busy\"").replace("\"a\"", "\"b\""))
        assertEquals(listOf(CancelReason.UNKNOWN, CancelReason.BUSY), orders.map { it.cancelReason })
    }

    @Test
    fun `sends a reason as the column spells it, and never an unknown one`() {
        assertEquals("\"no_show\"", json.encodeToString(CancelReasonSerializer, CancelReason.NO_SHOW))
        assertEquals("\"sold_out\"", json.encodeToString(CancelReasonSerializer, CancelReason.SOLD_OUT))
        assertFailsWith<IllegalArgumentException> { json.encodeToString(CancelReasonSerializer, CancelReason.UNKNOWN) }
    }

    @Test
    fun `no_show, timeout and unknown are never reasons to choose`() {
        assertEquals(listOf(CancelReason.BUSY, CancelReason.SOLD_OUT, CancelReason.UNREACHABLE, CancelReason.ADDRESS), CancelReason.CHOSEN)
    }

    @Test
    fun `not met at the door is offered on a delivery on its way, on a database that has it`() {
        val out = order("a", status = OrderStatus.ON_THE_WAY).copy(noShowsBefore = 0)
        assertTrue(NoShows.canMark(out))
        assertFalse(NoShows.canMark(out.copy(pickup = true)))
        assertFalse(NoShows.canMark(out.copy(status = OrderStatus.CONFIRMED)))
        assertFalse(NoShows.canMark(out.copy(status = OrderStatus.DELIVERED)))
        // A database without no-shows sends no count: no button.
        assertFalse(NoShows.canMark(out.copy(noShowsBefore = null)))
    }

    @Test
    fun `the flag shows the count on an order still in play`() {
        val new = order("a", status = OrderStatus.NEW)
        assertNull(NoShows.flag(new))
        assertNull(NoShows.flag(new.copy(noShowsBefore = 0)))
        assertEquals(1, NoShows.flag(new.copy(noShowsBefore = 1)))
        assertEquals(1, NoShows.flag(new.copy(status = OrderStatus.ON_THE_WAY, noShowsBefore = 1)))
        assertNull(NoShows.flag(new.copy(status = OrderStatus.DELIVERED, noShowsBefore = 1)))
    }

    @Test
    fun `the count can be reset on a flagged order, or on the no-show itself`() {
        val new = order("a", status = OrderStatus.NEW)
        assertFalse(NoShows.canReset(new))
        assertFalse(NoShows.canReset(new.copy(noShowsBefore = 0)))
        assertTrue(NoShows.canReset(new.copy(noShowsBefore = 1)))
        val noShow = new.copy(status = OrderStatus.CANCELLED, cancelReason = CancelReason.NO_SHOW)
        assertTrue(NoShows.canReset(noShow))
        assertFalse(NoShows.canReset(noShow.copy(cancelReason = CancelReason.BUSY)))
    }
}
