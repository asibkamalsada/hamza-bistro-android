package de.hamzabistro.printstation.core

/**
 * What to offer as the time to the door when an order for right away comes
 * in — src/app/eta.ts in hamza-bistro-web, figure for figure, so the button
 * filled in here is the one filled in on /orders.
 *
 * Not an answer, a starting point: the person accepting sees it already
 * picked and presses another when the kitchen says otherwise.
 */
object Eta {
    /** For a dish we have no time for — a new one, or the menu did not load. */
    private const val DEFAULT_PREP_MINUTES = 8

    /** Dishes cook alongside each other: the slowest sets the floor, the rest add a little. */
    private const val MINUTES_PER_EXTRA_DISH = 2

    private const val MAX_PREP_MINUTES = 30

    /** Stairs, the bell, the change: the fee bands do not count it, a promise has to. */
    private const val HANDOVER_MINUTES = 2

    /** Riding minutes to the ring an order was priced in, at the ring's outer edge (delivery-area.ts). */
    private val RING_MINUTES =
        mapOf(
            "inner" to 5 + HANDOVER_MINUTES,
            "near" to 10 + HANDOVER_MINUTES,
            "far" to 15 + HANDOVER_MINUTES,
            "edge" to 25 + HANDOVER_MINUTES,
        )

    /** The fallback by postcode, for an order with no ring to read. */
    private val NEARBY_POSTCODES = setOf("04177")
    private const val NEARBY_MINUTES = 7
    private val MIDDLE_POSTCODES = setOf("04105", "04107", "04109", "04179", "04209", "04229")
    private const val MIDDLE_MINUTES = 14
    private val FAR_POSTCODES = setOf("04103", "04155", "04275", "04315", "04317")
    private const val FAR_MINUTES = 25
    private const val DEFAULT_TRAVEL_MINUTES = 18

    /** The buttons offered besides the estimate, unless the device was set to others. */
    val LADDER: List<Int> = listOf(15, 30, 45, 60)

    /** What the ladder can be made of, as on /orders/settings. */
    val CHOICES: List<Int> = listOf(10, 15, 20, 25, 30, 40, 45, 50, 60, 75, 90)

    private val POSTCODE = Regex("\\b(\\d{5})\\b")

    fun travelMinutes(address: String, zone: String?): Int {
        RING_MINUTES[zone]?.let { return it }
        val postcode = POSTCODE.find(address)?.groupValues?.get(1) ?: return DEFAULT_TRAVEL_MINUTES
        return when (postcode) {
            in NEARBY_POSTCODES -> NEARBY_MINUTES
            in MIDDLE_POSTCODES -> MIDDLE_MINUTES
            in FAR_POSTCODES -> FAR_MINUTES
            else -> DEFAULT_TRAVEL_MINUTES
        }
    }

    /** How long the food takes. [prep] maps a menu item's id to its prep_minutes. */
    fun prepMinutes(items: List<OrderItem>, prep: Map<Long, Int>): Int {
        var slowest = 0
        var cooking = 0
        for (item in items) {
            val minutes = prep[item.id] ?: DEFAULT_PREP_MINUTES
            // Nothing to cook: a crate of cola is not kitchen time.
            if (minutes <= 0) continue
            slowest = maxOf(slowest, minutes)
            cooking += item.qty
        }
        if (cooking == 0) return 0
        return minOf(MAX_PREP_MINUTES, slowest + MINUTES_PER_EXTRA_DISH * (cooking - 1))
    }

    /**
     * Cooking plus riding, rounded up to five — or cooking alone for an
     * order the customer is collecting.
     */
    fun estimate(order: StaffOrder, prep: Map<Long, Int>): Int {
        val travel = if (order.pickup) 0 else travelMinutes(order.address, order.deliveryZone)
        return roundToFive(prepMinutes(order.items, prep) + travel)
    }

    /** The estimate among the round numbers, so the buttons always include it. */
    fun options(estimate: Int, ladder: List<Int> = LADDER): List<Int> = (ladder + estimate).distinct().sorted()

    private fun roundToFive(minutes: Int): Int = (minutes + 4) / 5 * 5
}
