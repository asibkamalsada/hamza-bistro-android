package de.hamzabistro.printstation.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One quarter hour of kitchen_slots(): how many dishes are due to leave the kitchen in it. */
@Serializable
data class KitchenSlot(
    /** Where the quarter hour starts. */
    @Serializable(with = InstantSerializer::class) val slot: Instant,
    /** Dishes per quarter hour; 0 while the cap is off. */
    val capacity: Int,
    /** Dishes due to leave in it; above [capacity] when staff accepted more. */
    val dishes: Int = 0,
    /** [capacity] − [dishes], never below 0; null while the cap is off. */
    val free: Int? = null,
)

/**
 * One quarter hour of the queue's load line: "18:00 ■■■■□". [bar] is one
 * square per dish of the cap, filled for each taken, " +2" past it; a cap
 * above ten is "7/12" instead: nobody counts twelve squares.
 */
data class LoadSlot(val slot: Instant, val dishes: Int, val capacity: Int) {
    /** Nothing more fits: drawn in the warn colour. */
    val full: Boolean
        get() = dishes >= capacity

    val bar: String
        get() {
            if (capacity > Kitchen.SQUARES_UP_TO) return "$dishes/$capacity"
            val filled = minOf(dishes, capacity)
            val over = dishes - filled
            return "■".repeat(filled) + "□".repeat(capacity - filled) + if (over > 0) " +$over" else ""
        }
}

/**
 * The kitchen's capacity (hamza-bistro-web#73): at most so many dishes leave
 * the kitchen per quarter hour (20261003160000_kitchen_capacity.sql). The
 * database counts and decides; customers cannot pre-order into a full
 * quarter hour, while staff can still accept anything. The app sets the cap
 * under Lieferzeiten and shows the next hour's load above the queue, as
 * kitchen.ts and /orders of the site do.
 */
object Kitchen {
    /** The choices under Lieferzeiten, 0 being off — DISHES_PER_SLOT_CHOICES of the site. */
    val CHOICES: List<Int> = listOf(0, 3, 4, 5, 6, 8, 10, 12, 15)

    /** What set_dishes_per_slot() takes; anything else is HB432. */
    val ALLOWED: IntRange = 0..50

    val SLOT: Duration = Duration.ofMinutes(15)

    /** How far ahead the load line looks: the next hour, four quarter hours. */
    val LINE: Duration = Duration.ofHours(1)

    /** From a cap above this, "7/12" rather than squares. */
    const val SQUARES_UP_TO = 10

    /** The choices to offer, with a saved cap that is not among them (set on the site, say) kept in. */
    fun choices(saved: Int): List<Int> = if (saved in CHOICES) CHOICES else (CHOICES + saved).sorted()

    /**
     * The start of the quarter hour [at] falls in, on a Leipzig clock — the
     * database's kitchen_slot_of(). Its offsets are whole hours, so this is
     * the same instant on any clock; counted in Leipzig all the same.
     */
    fun slotOf(at: Instant, zone: ZoneId = AlarmPolicy.LEIPZIG): Instant {
        val local = at.atZone(zone)
        return local.truncatedTo(ChronoUnit.HOURS).plusMinutes(local.minute / 15 * 15L).toInstant()
    }

    /** What the load line asks kitchen_slots() for: from the quarter hour [now] is in, one hour on. */
    fun window(now: Instant): Pair<Instant, Instant> {
        val from = slotOf(now)
        return from to from.plus(LINE)
    }

    /** kitchen_slots()'s answer. */
    fun parse(body: String): List<KitchenSlot> = json.decodeFromString(ListSerializer(KitchenSlot.serializer()), body)

    /**
     * The load line for [now]: the four quarter hours from the one [now] is
     * in, each with its dishes and the cap. Empty while the cap is off, and
     * when nothing was read. A quarter hour missing from [slots] is empty.
     */
    fun line(slots: List<KitchenSlot>?, now: Instant): List<LoadSlot> {
        if (slots.isNullOrEmpty()) return emptyList()
        val capacity = slots.first().capacity
        if (capacity <= 0) return emptyList()
        val dishes = slots.associate { it.slot to it.dishes }
        val first = slotOf(now)
        return (0 until LINE.dividedBy(SLOT).toInt()).map { k ->
            val at = first.plus(SLOT.multipliedBy(k.toLong()))
            LoadSlot(at, dishes[at] ?: 0, capacity)
        }
    }
}
