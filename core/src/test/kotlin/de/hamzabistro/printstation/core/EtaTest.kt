package de.hamzabistro.printstation.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cases of src/app/eta.spec.ts in hamza-bistro-web: the same order gets the same button filled. */
class EtaTest {
    private val doner = 1L
    private val pizza = 2L
    private val cola = 3L
    private val prep = mapOf(doner to 5, pizza to 12, cola to 0)

    private fun line(id: Long, qty: Int = 1) = OrderItem(id = id, name = "item $id", price = 8.19, qty = qty)

    private fun delivery(address: String, vararg items: OrderItem, zone: String? = null, pickup: Boolean = false) =
        order("a").copy(address = address, items = items.toList(), deliveryZone = zone, pickup = pickup)

    @Test
    fun `takes the slowest dish, not the sum, and counts every one of a repeated dish`() {
        assertEquals(12 + 2, Eta.prepMinutes(listOf(line(doner), line(pizza)), prep))
        assertEquals(5 + 2 * 2, Eta.prepMinutes(listOf(line(doner, 3)), prep))
    }

    @Test
    fun `charges nothing for drinks, and caps a big order`() {
        assertEquals(0, Eta.prepMinutes(listOf(line(cola, 6)), prep))
        assertEquals(5, Eta.prepMinutes(listOf(line(doner), line(cola, 6)), prep))
        assertEquals(30, Eta.prepMinutes(listOf(line(pizza, 20)), prep))
    }

    @Test
    fun `guesses for a dish the menu no longer has, or when the menu did not load`() {
        assertEquals(8, Eta.prepMinutes(listOf(line(99)), prep))
        assertEquals(8 + 2, Eta.prepMinutes(listOf(line(doner), line(pizza)), emptyMap()))
    }

    @Test
    fun `reads the postcode out of the one-line address, or falls back`() {
        assertEquals(7, Eta.travelMinutes("Demmeringstr. 1, 04177 Leipzig", null))
        assertEquals(14, Eta.travelMinutes("Karl-Liebknecht-Str. 1, 04107 Leipzig", null))
        assertEquals(25, Eta.travelMinutes("Dresdner Str. 1, 04317 Leipzig", null))
        assertEquals(18, Eta.travelMinutes("Bautzner Str. 1, 04347 Leipzig", null))
        assertEquals(18, Eta.travelMinutes("bei der Tankstelle", null))
    }

    @Test
    fun `takes the ring the order was priced in over its postcode`() {
        assertEquals(17, Eta.travelMinutes("Karl-Liebknecht-Str. 99, 04275 Leipzig", "far"))
        assertEquals(27, Eta.travelMinutes("Karl-Liebknecht-Str. 99, 04275 Leipzig", "edge"))
        assertEquals(7, Eta.travelMinutes("Demmeringstr. 1, 04177 Leipzig", "inner"))
        assertEquals(7, Eta.travelMinutes("Demmeringstr. 1, 04177 Leipzig", "unknown"))
    }

    @Test
    fun `adds cooking to riding and rounds up to five`() {
        assertEquals(15, Eta.estimate(delivery("Lützner Str. 1, 04177 Leipzig", line(doner)), prep))
        assertEquals(10, Eta.estimate(delivery("Lützner Str. 1, 04177 Leipzig", line(cola, 2)), prep))
        assertEquals(30, Eta.estimate(delivery("Zum Dorfe 1, 04319 Leipzig", line(pizza)), prep))
        assertEquals(30, Eta.estimate(delivery("Zum Dorfe 1, 04107 Leipzig", line(pizza), zone = "far"), prep))
    }

    @Test
    fun `counts no riding at all for an order being collected`() {
        assertEquals(15, Eta.estimate(delivery("", line(pizza), pickup = true), prep))
        assertEquals(30, Eta.estimate(delivery("", line(pizza)), prep))
    }

    @Test
    fun `offers the estimate among the round numbers, once, in order`() {
        assertEquals(listOf(15, 20, 30, 45, 60), Eta.options(20))
        assertEquals(listOf(15, 30, 45, 60), Eta.options(30))
        assertEquals(listOf(20, 25, 40), Eta.options(25, listOf(20, 40)))
    }

    @Test
    fun `adds busy mode on top of the rounded estimate`() {
        assertEquals(45, Eta.estimate(delivery("Lützner Str. 1, 04177 Leipzig", line(doner)), prep, busyMinutes = 30))
        assertEquals(30, Eta.estimate(delivery("", line(pizza), pickup = true), prep, busyMinutes = 15))
        // Not rounded again: +15 on 13 minutes of cooking and riding is 30, not 28 rounded.
        assertEquals(15 + 15, Eta.estimate(delivery("Lützner Str. 1, 04177 Leipzig", line(doner)), prep, 15))
        assertEquals(15, Eta.estimate(delivery("Lützner Str. 1, 04177 Leipzig", line(doner)), prep, -5))
    }

    // The kitchen's backlog (hamza-bistro-web#74): the cases of eta.spec.ts's
    // "the kitchen backlog in the estimate", kitchen.spec.ts's "reads an
    // order's backlog off the slot it was given", and the table on #74.

    private val minute = 60_000L

    /** 18:00 Leipzig summer time, on a quarter hour — AT_18 of kitchen.spec.ts. */
    private val at18 = at("2026-07-07T16:00:00Z")

    private fun minutes(n: Long) = Duration.ofMinutes(n)

    /** One Döner to the near ring, for right away, placed at 18:02 with the kitchen slot [slot]. */
    private fun placed(slot: Instant?, items: List<OrderItem> = listOf(line(doner)), pickup: Boolean = false) =
        delivery("", *items.toTypedArray(), zone = if (pickup) null else "near", pickup = pickup)
            .copy(createdAt = at18.plus(minutes(2)), kitchenSlot = slot, status = OrderStatus.NEW, confirmedAt = null, etaMinutes = null)

    @Test
    fun `waits for the slot when it comes after the cooking, not on top of it`() {
        // A Döner (5) to the near ring (10 + 2): 17 → 20 with room in the kitchen.
        assertEquals(20, Eta.quote(5, 12))
        // Its slot starts in 3 minutes: the cooking is still the longer wait.
        assertEquals(20, Eta.quote(5, 12, kitchenWaitMillis = 3 * minute))
        // In 28: it leaves then, and rides 12 → 40.
        assertEquals(40, Eta.quote(5, 12, kitchenWaitMillis = 28 * minute))
        // Busy mode on top of that, as ever.
        assertEquals(55, Eta.quote(5, 12, 15, 28 * minute))
        // Collected: the slot is the wait.
        assertEquals(30, Eta.quote(5, 0, kitchenWaitMillis = 28 * minute))
    }

    @Test
    fun `is the same sum from its parts`() {
        assertEquals(20, Eta.quote(5, 12))
        assertEquals(55, Eta.quote(5, 12, 15, 28 * minute))
        assertEquals(0, Eta.quote(0, 0))
        assertEquals(5, Eta.quote(5, 0, 0, -4 * minute))
    }

    @Test
    fun `rounds a part-minute wait up as the site does, in milliseconds`() {
        // 27½ minutes until the slot plus 12 riding is 39½ → 40; 28 and a second → 45.
        assertEquals(40, Eta.quote(5, 12, kitchenWaitMillis = 27 * minute + 30_000))
        assertEquals(45, Eta.quote(5, 12, kitchenWaitMillis = 28 * minute + 1_000))
    }

    @Test
    fun `reads an order's backlog off the slot it was given`() {
        val now = at18.plus(minutes(5))
        // Its natural slot, or none: no wait beyond the cooking.
        assertEquals(0, Kitchen.orderWaitMillis(placed(at18), now))
        assertEquals(0, Kitchen.orderWaitMillis(placed(null), now))
        // Moved to 18:30 and accepted at 18:05: 25 minutes until it can leave.
        assertEquals(25 * minute, Kitchen.orderWaitMillis(placed(at18.plus(minutes(30))), now))
        // Accepted after the slot began: nothing left to wait for.
        assertEquals(0, Kitchen.orderWaitMillis(placed(at18.plus(minutes(30))), at18.plus(minutes(40))))
        // A pre-order has its own time: no backlog on top.
        val preorder = placed(at18.plus(minutes(30))).copy(scheduledFor = at18.plus(minutes(60)))
        assertEquals(0, Kitchen.orderWaitMillis(preorder, now))
    }

    @Test
    fun `pre-selects what the customer was quoted, as the table on #74 has it`() {
        // 2 Döner (prep 5) to near, room in the kitchen: 5 + 2 + 12 = 19 → 20.
        val two = placed(at18, listOf(line(doner, 2)))
        assertEquals(20, Eta.estimate(two, prep, now = at18.plus(minutes(2))))
        // Same order, busy mode +30: 50.
        assertEquals(50, Eta.estimate(two, prep, busyMinutes = 30, now = at18.plus(minutes(2))))

        // The next slots full: place_order gave it 18:30, accepted at 18:02 — 28 minutes off.
        val full = placed(at18.plus(minutes(30)))
        val now = at18.plus(minutes(2))
        assertEquals(40, Eta.estimate(full, prep, now = now))
        assertEquals(55, Eta.estimate(full, prep, busyMinutes = 15, now = now))
        assertEquals(30, Eta.estimate(placed(at18.plus(minutes(30)), pickup = true), prep, now = now))
    }

    @Test
    fun `leaves the estimate of before without a slot, and without a clock`() {
        val full = placed(at18.plus(minutes(30)))
        // Without now — timing a pre-order's cooking — no backlog.
        assertEquals(20, Eta.estimate(full, prep))
        // No kitchen_slot (a database without the cap, an order made by hand): the estimate of before.
        assertEquals(20, Eta.estimate(placed(null), prep, now = at18.plus(minutes(2))))
        assertEquals(20 + 15, Eta.estimate(placed(null), prep, 15, at18.plus(minutes(2))))
    }

    @Test
    fun `reads kitchen_slot with the order`() {
        val row = """{"id":"a","order_number":1,"created_at":"2026-07-07T16:02:00+00:00","status":"new",""" +
            """"kitchen_slot":"2026-07-07T16:30:00+00:00"}"""
        assertEquals(at("2026-07-07T16:30:00Z"), json.decodeFromString(StaffOrder.serializer(), row).kitchenSlot)
        val older = """{"id":"a","order_number":1,"created_at":"2026-07-07T16:02:00+00:00","status":"new"}"""
        assertEquals(null, json.decodeFromString(StaffOrder.serializer(), older).kitchenSlot)
        assertTrue("kitchen_slot" in StaffOrder.QUEUE_COLUMNS.split(","))
    }
}
