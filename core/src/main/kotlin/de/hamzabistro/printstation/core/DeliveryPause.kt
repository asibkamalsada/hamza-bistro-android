package de.hamzabistro.printstation.core

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * What "Pausieren …" stops (hamza-bistro-web#72): everything, delivery, or
 * delivery to the outer rings — the shop_close() scope and rings each
 * choice sends (20261002180000_scoped_pause.sql). Collection stays open for
 * every choice but [ALL].
 */
enum class PauseWhat(val scope: String, val rings: List<String>?) {
    /** "Alles": the shop closed, as before scopes. */
    ALL(ClosureScope.ALL, null),

    /** "Nur Lieferung": collection only. */
    DELIVERY(ClosureScope.DELIVERY, null),

    /** "Weite Ringe": no delivery to far and edge. */
    FAR(ClosureScope.RINGS, listOf("far", "edge")),

    /** "Nur Rand": no delivery to the edge. */
    EDGE(ClosureScope.RINGS, listOf("edge")),
    ;

    companion object {
        /**
         * Which choice a closure's [scope] and [rings] read as, the way the
         * site names them: the far rings whenever far is among them, the edge
         * alone — or null for another set of rings, to be named one by one.
         */
        fun of(scope: String, rings: List<String>?): PauseWhat? =
            when (scope) {
                ClosureScope.DELIVERY -> DELIVERY
                ClosureScope.RINGS ->
                    when {
                        rings.orEmpty().contains("far") -> FAR
                        rings == listOf("edge") -> EDGE
                        else -> null
                    }
                else -> ALL
            }
    }
}

/** shop_closures.scope, as the database spells it. */
object ClosureScope {
    const val ALL = "all"
    const val DELIVERY = "delivery"
    const val RINGS = "rings"
}

/**
 * A pause of delivery running at a moment, while collection goes on:
 * delivery_pause_at() in 20261002180000_scoped_pause.sql and
 * deliveryPauseAt() on the site. A pause of all delivery outranks pauses of
 * rings; rings paused together add up.
 */
data class DeliveryPause(
    /** [ClosureScope.DELIVERY] or [ClosureScope.RINGS]. */
    val scope: String,
    /** The rings not delivered to, sorted; empty for a pause of all delivery. */
    val rings: List<String>,
    /** The end of the longest; null while somebody has to switch it back on. */
    val until: Instant?,
    /** Who paused — told to staff only. */
    val by: String?,
) {
    /** How the line names it — see [PauseWhat.of]; null for a set of rings named by [rings]. */
    val what: PauseWhat?
        get() = PauseWhat.of(scope, rings)
}

/**
 * A weekly time without delivery while collection stays open — Friday
 * prayer, say (hamza-bistro-web#112): delivery_breaks, read through
 * shop_hours(). Minutes since midnight, Leipzig time, on the quarter hour,
 * within one day.
 */
@Serializable
data class DeliveryBreak(
    /** Null for one not saved yet. */
    val id: Long? = null,
    /** 0 = Sunday, as the database counts. */
    val day: Int,
    val starts: Int,
    /** 1440 is midnight at the end of the day. */
    val ends: Int,
    /** Why — shown to customers; may be empty. */
    val label: String = "",
    /** Switched off, it is kept but ignored. Customers only ever get active ones. */
    val active: Boolean = true,
) {
    /** What the table's checks allow: delivery_break_save() refuses anything else with HB432. */
    val valid: Boolean
        get() =
            day in 0..6 &&
                starts % DeliveryDay.QUARTER == 0 &&
                ends % DeliveryDay.QUARTER == 0 &&
                starts >= 0 &&
                ends <= DeliveryDay.MIDNIGHT &&
                starts < ends &&
                label.trim().length <= MAX_LABEL

    companion object {
        /** What delivery_breaks.label holds at most. */
        const val MAX_LABEL = 60

        /** How long before a break delivery for right now stops, unless the shop says otherwise. */
        const val DEFAULT_LEAD = 30

        /** The lead choices of /orders/hours; set_delivery_break_lead() takes 0–120 on the five. */
        private val LEADS = listOf(0, 15, 30, 45, 60)

        /** The choices to offer, with [saved] among them even when it is none of the usual. */
        fun leadChoices(saved: Int): List<Int> = (LEADS + saved).distinct().sorted()

        /** A new break starts as Friday prayer, the case it was built for, as on the site. */
        val NEW = DeliveryBreak(day = 5, starts = 12 * 60 + 45, ends = 14 * 60)

        /** Monday first, then by the time of day, as the week is read. */
        fun weekOrdered(breaks: List<DeliveryBreak>): List<DeliveryBreak> =
            breaks.sortedWith(compareBy({ DeliveryDay.WEEK_ORDER.indexOf(it.day) }, { it.starts }, { it.id ?: Long.MAX_VALUE }))
    }
}

/** A delivery break for the line above the queue: running, or starting within the lead. */
data class BreakNotice(val brk: DeliveryBreak, val running: Boolean)
