package de.hamzabistro.printstation.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What a new order does on this device. */
enum class NewOrderAlarm {
    /** Rings in a loop, over the lock screen, until somebody answers the order — anywhere. */
    LOOP,

    /** One sound and a notification per order, like a message. */
    ONCE,

    /** Nothing: the queue is there to look at. */
    OFF,
}

/** How this device wants to be alarmed. Kept on the device. */
data class AlarmSettings(
    val newOrders: NewOrderAlarm = NewOrderAlarm.LOOP,
    /** A chime when an accepted pre-order has to go on. */
    val cookNow: Boolean = true,
    /** A chime when this device's own printer did not print a ticket. */
    val printer: Boolean = true,
    /**
     * A chime when an accepted order has no ticket two minutes on, while a
     * device is meant to print every one — the "unprinted" push the site
     * sends the phones.
     */
    val unprinted: Boolean = true,
    /** A chime when the queue could not be read for a while. */
    val connection: Boolean = true,
    /**
     * "Bestellung fertig": a chime when a bag is packed and waits for the
     * driver (hamza-bistro-web#90). Null until somebody chooses on this
     * device: then it follows what the device is for — on for a driver's
     * phone, off for the kitchen tablet ([forDevice]).
     */
    val packed: Boolean? = null,
    /**
     * The night window, Leipzig time: inside it nothing rings, and a new
     * order is a silent notification — for the pre-order somebody places at
     * one in the morning. Both null: never quiet.
     */
    val quietFrom: LocalTime? = LocalTime.of(22, 0),
    val quietTo: LocalTime? = LocalTime.of(9, 0),
    /** How long "Stumm" silences the orders ringing at that moment. */
    val silenceSeconds: Int = 60,
) {
    /**
     * These settings on a device that is a driver's phone or not: a choice
     * not made yet for "Bestellung fertig" made as [driver] says. What
     * [AlarmPolicy.decide] is given.
     */
    fun forDevice(driver: Boolean): AlarmSettings = if (packed != null) this else copy(packed = driver)
}

/** A one-off alert: a sound once, and a notification. */
sealed interface Chime {
    data class NewOrder(val order: StaffOrder) : Chime

    data class CookNow(val order: StaffOrder) : Chime

    data class PrinterFailed(val order: Long, val reason: String) : Chime

    /** Accepted two minutes ago, and no ticket came out anywhere. */
    data class Unprinted(val order: StaffOrder) : Chime

    /** The queue has not been read for a while: this device would not hear of an order. */
    data class Offline(val since: Instant) : Chime

    /**
     * Two minutes before the database declines it unanswered: the alarm
     * escalates, once, with a sound and a vibration of its own.
     */
    data class DecliningSoon(val order: StaffOrder) : Chime

    /**
     * "#57 ist fertig": the bag is packed and waits for the driver, with
     * [waiting] packed bags in all, this one included.
     */
    data class Packed(val order: StaffOrder, val waiting: Int) : Chime
}

/** What the alarm should be doing now. */
data class AlarmDecision(
    /** The waiting orders rung for in a loop, oldest first. Empty: silent. */
    val ringing: List<StaffOrder> = emptyList(),
    /** Every new order still worth a decision, rung for or not. */
    val waiting: List<StaffOrder> = emptyList(),
    /** One-off alerts to give now. */
    val chimes: List<Chime> = emptyList(),
    /** Inside the night window: chimes come without a sound. */
    val quiet: Boolean = false,
    /** Until when "Stumm" holds, while it does. */
    val silencedUntil: Instant? = null,
)

/**
 * Decides when this device rings — the native half of what the site's
 * reminders do with pushes (20260927130000_staff_push_reminders.sql and
 * 20260927150000_staff_push_settings.sql in hamza-bistro-web), with the one
 * thing a push cannot do: keep ringing until somebody answers.
 *
 *   * A new order rings for the first hour after it arrived, and a
 *     pre-order still waiting in the hour before its time, as the server
 *     rings the phones. An order accepted or declined anywhere — this
 *     device, another phone, the website, Telegram — stops it everywhere,
 *     because it leaves the queue every device reads.
 *   * "Stumm" silences what is ringing for [AlarmSettings.silenceSeconds];
 *     an order still waiting after that rings again, and one that arrives
 *     meanwhile rings at once.
 *   * An accepted pre-order chimes once when it has to go on; this device's
 *     printer failing chimes after half a minute and every three minutes
 *     while it lasts; a queue that cannot be read chimes once after two.
 *   * A new order two minutes before the database declines it unanswered
 *     (auto_decline_at, 20261002150000_auto_decline.sql) escalates once:
 *     through "Stumm", since it is the last chance; not on a device set to
 *     stay silent about new orders.
 *   * An accepted order with no ticket two minutes on chimes once, while a
 *     print station registered before it was accepted is meant to print it
 *     — the rule of remind_staff_of_waiting_orders()'s "unprinted" stamp in
 *     20260930150000_order_printing.sql.
 *   * A bag packed for the driver ("Fertig", hamza-bistro-web#90) chimes
 *     once, on a device that wants it: not for what was already packed
 *     when this device started listening, and not again for one unpacked
 *     and packed again within [REPACK_WINDOW] — a slip of the finger
 *     corrected.
 *
 * Holds what it has already said, so it is one per device; [decide] is
 * pure otherwise, with the clock passed in.
 */
class AlarmPolicy(private val zone: ZoneId = LEIPZIG) {
    private val chimedNew = mutableSetOf<String>()
    private val escalated = mutableSetOf<String>()
    private val cookDone = mutableSetOf<String>()
    private var cookPrimed = false
    private val unprintedDone = mutableSetOf<String>()
    private var unprintedPrimed = false
    private var silenced: Set<String> = emptySet()
    private var silencedUntil: Instant? = null
    private var printerSince: Instant? = null
    private var printerChimed: Instant? = null
    private var offlineSince: Instant? = null
    private var offlineChimed = false
    private var lastRinging: List<StaffOrder> = emptyList()
    private val packedSeen = mutableMapOf<String, Instant>()
    private var packedLook: Instant? = null

    /** "Stumm": what is ringing now stays quiet for [seconds]. */
    @Synchronized
    fun silence(now: Instant, seconds: Int) {
        // Added to what is already silenced: silencing the order that came
        // in during the last silence does not wake the one before it.
        silenced = silenced + lastRinging.map { it.id }
        silencedUntil = now.plusSeconds(seconds.toLong())
    }

    /**
     * What to do, given the queue ([orders], null until it was read once),
     * whether reading it fails just now, this device's printer's trouble if
     * any, since when some other device has been meant to print every
     * accepted order ([printingSince], null for none or not known), how long
     * each pre-order needs ([lead]), and the time.
     */
    @Synchronized
    fun decide(
        orders: List<StaffOrder>?,
        failing: Boolean,
        printer: Problem?,
        settings: AlarmSettings,
        now: Instant,
        printingSince: Instant? = null,
        lead: (StaffOrder) -> Int,
    ): AlarmDecision {
        val chimes = mutableListOf<Chime>()
        val quiet = isQuiet(settings, now)

        if (silencedUntil?.let { !now.isBefore(it) } == true) {
            silenced = emptySet()
            silencedUntil = null
        }

        val waiting = orders.orEmpty().filter { worthRinging(it, now) }.sortedBy { it.createdAt }
        val ringing =
            if (settings.newOrders == NewOrderAlarm.LOOP && !quiet) waiting.filterNot { it.id in silenced } else emptyList()

        // Once per order, where it does not ring: as a message, or in the
        // night window, silently.
        if (settings.newOrders == NewOrderAlarm.ONCE || (settings.newOrders == NewOrderAlarm.LOOP && quiet)) {
            for (order in waiting) if (chimedNew.add(order.id)) chimes += Chime.NewOrder(order)
        } else {
            chimedNew += waiting.map { it.id }
        }

        for (order in waiting) {
            if (!AutoDecline.escalationDue(order, now) || !escalated.add(order.id)) continue
            if (settings.newOrders != NewOrderAlarm.OFF) chimes += Chime.DecliningSoon(order)
        }

        if (orders != null) {
            for (order in orders) {
                if (order.status != OrderStatus.CONFIRMED || order.scheduledFor == null || order.id in cookDone) continue
                val from = StaffQueue.cookFrom(order, lead(order)) ?: continue
                if (from.isAfter(now)) continue
                cookDone += order.id
                // Already late to start when this device first read the
                // queue: the card says so; it does not also chime.
                if (cookPrimed && settings.cookNow && from.isAfter(now.minus(LATE_START))) chimes += Chime.CookNow(order)
            }
            cookPrimed = true
            if (printingSince != null) {
                for (order in orders) {
                    if (order.status != OrderStatus.CONFIRMED || order.printedAt != null || order.id in unprintedDone) continue
                    val accepted = order.confirmedAt ?: continue
                    if (accepted.isAfter(now.minus(UNPRINTED_GRACE))) continue
                    unprintedDone += order.id
                    // Already overdue when this device first knew it, accepted
                    // before anything was meant to print it, or over an hour
                    // ago: the card says so; it does not also chime.
                    val due = unprintedPrimed && !printingSince.isAfter(accepted) && accepted.isAfter(now.minus(WINDOW))
                    if (due && settings.unprinted) chimes += Chime.Unprinted(order)
                }
                unprintedPrimed = true
            }
            chimes += packedChimes(orders, settings, now)
            val ids = orders.map { it.id }.toSet()
            chimedNew.retainAll(ids)
            escalated.retainAll(ids)
            cookDone.retainAll(ids)
            unprintedDone.retainAll(ids)
        }

        if (printer is Problem.NotPrinted) {
            val since = printerSince ?: now.also { printerSince = it }
            val due = printerChimed?.let { !now.isBefore(it.plus(PRINTER_AGAIN)) } ?: !now.isBefore(since.plus(PRINTER_GRACE))
            if (due) {
                printerChimed = now
                if (settings.printer) chimes += Chime.PrinterFailed(printer.order, printer.reason)
            }
        } else {
            printerSince = null
            printerChimed = null
        }

        if (failing) {
            val since = offlineSince ?: now.also { offlineSince = it }
            if (!offlineChimed && !now.isBefore(since.plus(OFFLINE_GRACE))) {
                offlineChimed = true
                if (settings.connection) chimes += Chime.Offline(since)
            }
        } else {
            offlineSince = null
            offlineChimed = false
        }

        lastRinging = ringing
        return AlarmDecision(
            ringing = ringing,
            waiting = waiting,
            chimes = chimes,
            quiet = quiet,
            silencedUntil = silencedUntil.takeIf { silenced.isNotEmpty() },
        )
    }

    /**
     * "Bestellung fertig", decided apart from the rest: which of [orders]
     * are packed bags this device has not chimed for.
     *
     * Each packed bag is remembered with when it was last seen packed, and
     * forgotten [REPACK_WINDOW] after that — not when it leaves the list, so
     * one hidden for a moment while a step is on its way here does not chime
     * again when it comes back. A look after a longer gap (the first one, or
     * the first since the shift was off) takes in what is packed without a
     * word, as the other chimes treat a starting picture.
     */
    private fun packedChimes(orders: List<StaffOrder>, settings: AlarmSettings, now: Instant): List<Chime> {
        val primed = packedLook?.let { !now.isAfter(it.plus(REPACK_WINDOW)) } == true
        packedLook = now
        val packed = orders.filter(StaffQueue::isPacked)
        val chimes = mutableListOf<Chime>()
        for (order in packed) {
            val seen = packedSeen.put(order.id, now)
            val known = seen != null && !now.isAfter(seen.plus(REPACK_WINDOW))
            if (primed && !known && settings.packed == true) chimes += Chime.Packed(order, packed.size)
        }
        packedSeen.values.removeAll { now.isAfter(it.plus(REPACK_WINDOW)) }
        return chimes
    }

    /**
     * A new order worth ringing about: in the first hour after it arrived,
     * or — a pre-order — in the hour before its time. The same windows as
     * remind_staff_of_waiting_orders(): one left over from closing time does
     * not ring through the night.
     */
    fun worthRinging(order: StaffOrder, now: Instant): Boolean {
        if (order.status != OrderStatus.NEW) return false
        if (order.createdAt.isAfter(now.minus(WINDOW))) return true
        val at = order.scheduledFor ?: return false
        return !now.isBefore(at.minus(WINDOW)) && now.isBefore(at)
    }

    /** Inside the night window on a Leipzig clock — the SQL of staff_push_recipients(). */
    fun isQuiet(settings: AlarmSettings, now: Instant): Boolean {
        val from = settings.quietFrom ?: return false
        val to = settings.quietTo ?: return false
        val time = now.atZone(zone).toLocalTime()
        return if (!from.isAfter(to)) !time.isBefore(from) && time.isBefore(to) else !time.isBefore(from) || time.isBefore(to)
    }

    companion object {
        val LEIPZIG: ZoneId = ZoneId.of("Europe/Berlin")
        private val WINDOW = Duration.ofHours(1)
        private val LATE_START = Duration.ofMinutes(2)
        private val PRINTER_GRACE = Duration.ofSeconds(30)
        private val PRINTER_AGAIN = Duration.ofMinutes(3)
        private val OFFLINE_GRACE = Duration.ofMinutes(2)

        /**
         * How long a packed bag is remembered once it is not seen packed:
         * "Doch nicht fertig" and "Fertig" again within it is one bag, not
         * two.
         */
        val REPACK_WINDOW: Duration = Duration.ofMinutes(2)

        /** Several of a station's looks, each trying a failed ticket again: as the server waits. */
        private val UNPRINTED_GRACE = Duration.ofMinutes(2)
    }
}
