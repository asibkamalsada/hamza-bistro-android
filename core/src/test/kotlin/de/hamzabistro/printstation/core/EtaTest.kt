package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
