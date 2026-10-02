package de.hamzabistro.printstation.core

import java.time.Duration
import java.time.Instant

/**
 * How the staff app reads its queue: which order needs a decision, which is
 * due when, when a pre-order has to go on the grill, and how to get to the
 * door. The same rules as supabase/functions/_shared/staff-queue.ts in
 * hamza-bistro-web — the site's /orders and this app have to agree on what
 * "fällig 18:32" means — written as pure functions over an order and a
 * clock.
 */
object StaffQueue {
    /**
     * When the customer was promised the food: the time they chose for a
     * pre-order, or the acceptance plus the minutes given for one wanted
     * now — and any delay staff added since, on top of either. Null while
     * nothing has been promised. The same rule as promised_at() in SQL.
     *
     * An accepted order from before confirmed_at existed falls back to when
     * it was placed, which errs early: late is called sooner, never not.
     */
    fun promisedAt(order: StaffOrder): Instant? {
        val delay = Duration.ofMinutes(order.delayMinutes.toLong())
        order.scheduledFor?.let { return it.plus(delay) }
        if (order.status == OrderStatus.NEW) return null
        val minutes = order.etaMinutes ?: return null
        return (order.confirmedAt ?: order.createdAt).plus(Duration.ofMinutes(minutes.toLong())).plus(delay)
    }

    /**
     * When a pre-order has to be started to be at the door on time: its
     * time, less [leadMinutes] — the same estimate an order for right away is
     * accepted with, so both kinds are timed by one rule. From the promise,
     * so a delayed pre-order goes on the grill later too.
     */
    fun cookFrom(order: StaffOrder, leadMinutes: Int): Instant? {
        if (order.scheduledFor == null) return null
        return promisedAt(order)?.minus(Duration.ofMinutes(leadMinutes.toLong()))
    }

    // -----------------------------------------------------------------------
    // Running late: "+10 Min." (20261002170000_order_delay.sql)
    // -----------------------------------------------------------------------

    /** The delays a card offers. */
    val DELAY_STEPS: List<Int> = listOf(10, 20)

    /** "Alle +15 Min.", for every accepted order for right away at once. */
    const val DELAY_ALL_MINUTES = 15

    /** What all delays on one order may add up to; past that, call the customer. */
    const val MAX_DELAY_MINUTES = 180

    /** How far back "Alle" reaches: an order accepted yesterday and left open is not tonight's. */
    private val DELAY_ALL_WINDOW: Duration = Duration.ofDays(1)

    /** Whether staff have moved its promise later since accepting it. */
    fun isDelayed(order: StaffOrder): Boolean = order.delayMinutes > 0

    /**
     * Whether a card offers "+[minutes] Min.": accepted, in the kitchen or
     * out of it, with room left under [MAX_DELAY_MINUTES]. What
     * orders_delay_step lets through; anything else comes back as HB435.
     */
    fun canDelay(order: StaffOrder, minutes: Int): Boolean =
        (order.status == OrderStatus.CONFIRMED || order.status == OrderStatus.ON_THE_WAY) &&
            order.delayMinutes + minutes <= MAX_DELAY_MINUTES

    /**
     * What "Alle +[minutes] Min." moves: every accepted order for right
     * away, accepted within the last day, with room left — except a
     * collection already on the counter, which waits on the customer, not
     * the kitchen. Exactly the orders delay_open_orders() updates, so the
     * question asked first counts what the answer will.
     */
    fun delayableAll(orders: List<StaffOrder>, minutes: Int, now: Instant): List<StaffOrder> =
        orders.filter { order ->
            canDelay(order, minutes) &&
                order.scheduledFor == null &&
                !(order.status == OrderStatus.ON_THE_WAY && order.pickup) &&
                (order.confirmedAt ?: order.createdAt).isAfter(now.minus(DELAY_ALL_WINDOW))
        }

    /**
     * Whole minutes from [now] until [at]; negative once it has passed —
     * rounded so a promise 30 seconds out reads 0 and 30 seconds gone reads
     * -1: late is late.
     */
    fun minutesUntil(at: Instant, now: Instant): Long {
        val ms = Duration.between(now, at).toMillis()
        return if (ms >= 0) ms / 60_000 else -((-ms + 59_999) / 60_000)
    }

    /**
     * The order the queue is read in: every new order first, the
     * longest-waiting on top; then everything accepted by when it is due,
     * which puts a pre-order due in ten minutes above an order for right
     * away due in thirty.
     */
    val order: Comparator<StaffOrder> = Comparator { a, b ->
        val aNew = a.status == OrderStatus.NEW
        val bNew = b.status == OrderStatus.NEW
        if (aNew != bNew) return@Comparator if (aNew) -1 else 1
        if (!aNew) {
            val aDue = promisedAt(a) ?: Instant.MAX
            val bDue = promisedAt(b) ?: Instant.MAX
            val due = aDue.compareTo(bDue)
            if (due != 0) return@Comparator due
        }
        a.createdAt.compareTo(b.createdAt)
    }

    /** Which heading an order sits under: what the person holding the phone does next. */
    fun groupOf(order: StaffOrder): QueueGroup =
        when (order.status) {
            OrderStatus.NEW -> QueueGroup.DECIDE
            OrderStatus.CONFIRMED -> QueueGroup.COOK
            else -> QueueGroup.OUT
        }

    /** The queue sorted and cut under its headings, empty headings left out. */
    fun group(orders: List<StaffOrder>): List<Pair<QueueGroup, List<StaffOrder>>> {
        val sorted = orders.sortedWith(order)
        return QueueGroup.entries.mapNotNull { group ->
            sorted.filter { groupOf(it) == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }
    }

    // -----------------------------------------------------------------------
    // Getting there
    // -----------------------------------------------------------------------

    /**
     * What to hand a map to find the door: street, postcode and town — never
     * the one-line address, which carries the customer's note ("Hinterhaus,
     * 3. OG") that a geocoder either ignores or goes looking for. An order
     * from before the address was split has only the one line.
     */
    fun destinationQuery(order: StaffOrder): String {
        val street = order.street?.trim().orEmpty()
        val place = listOfNotNull(order.postalCode?.trim(), order.city?.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        return if (street.isNotEmpty() && place.isNotEmpty()) "$street, $place" else order.address
    }

    /** The street line a rider reads, without the note, which has a box of its own. */
    fun addressLine(order: StaffOrder): String {
        val street = order.street?.trim().orEmpty()
        val postcode = order.postalCode?.trim().orEmpty()
        if (street.isEmpty() || postcode.isEmpty()) return order.address
        return "$street, $postcode ${order.city?.trim().orEmpty()}".trim()
    }

    /** The note, only when the street line does not already carry it. */
    fun doorNote(order: StaffOrder): String? {
        val note = order.addressNote?.trim()
        return if (!note.isNullOrEmpty() && !order.street.isNullOrBlank()) note else null
    }

    /**
     * A link that opens the chosen app with the route already asked for,
     * rather than a search the rider then has to turn into one.
     */
    fun navigationUrl(order: StaffOrder, app: NavApp, mode: TravelMode): String {
        val q = encodeUriComponent(destinationQuery(order))
        return when (app) {
            NavApp.GOOGLE -> "https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=${mode.param}"
            // Apple's form knows driving, not cycling.
            NavApp.APPLE -> "https://maps.apple.com/?daddr=$q" + if (mode == TravelMode.DRIVING) "&dirflg=d" else ""
            NavApp.WAZE -> "https://waze.com/ul?q=$q&navigate=yes"
            NavApp.GEO -> "geo:0,0?q=$q"
        }
    }

    /** JavaScript's encodeURIComponent, so a link here is the link the site makes. */
    fun encodeUriComponent(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}

/** The three headings, named for what happens next. */
enum class QueueGroup {
    /** Accept or decline. */
    DECIDE,

    /** Accepted: in the kitchen. */
    COOK,

    /** On a bike, or on the counter waiting to be collected. */
    OUT,
}

/** The navigation apps a phone can be set to open. */
enum class NavApp {
    GOOGLE,
    APPLE,
    WAZE,
    /** The intent every map app answers: the phone asks which. */
    GEO,
}

/** How the rider travels. Only the apps that plan by mode are told. */
enum class TravelMode(val param: String) {
    BICYCLING("bicycling"),
    DRIVING("driving"),
}
