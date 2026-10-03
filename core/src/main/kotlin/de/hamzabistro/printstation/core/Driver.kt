package de.hamzabistro.printstation.core

import java.time.Instant
import java.util.Locale

/**
 * The four delivery rings, inside out, and how each is ridden: by bike to
 * the inner two, by car to the outer two (docs/delivery-area.md in
 * hamza-bistro-web).
 */
enum class Ring(val wire: String, val mode: TravelMode) {
    INNER("inner", TravelMode.BICYCLING),
    NEAR("near", TravelMode.BICYCLING),
    FAR("far", TravelMode.DRIVING),
    EDGE("edge", TravelMode.DRIVING);

    companion object {
        /** The ring an order was priced in; null for a pickup, or an order from before the rings. */
        fun of(order: StaffOrder): Ring? = order.deliveryZone?.trim()?.lowercase(Locale.ROOT)?.let { zone -> entries.firstOrNull { it.wire == zone } }
    }
}

/**
 * The driver's view of the queue (hamza-bistro-android#8): which bags to
 * take, in which order to ride them, and one link to the map for the lot.
 * Pure functions over the orders, as [StaffQueue] is.
 *
 * Without a coordinate per order (that is the server's half of #8), the
 * stops are put in order by ring, then postcode, then street, and the
 * driver puts right what that gets wrong.
 */
object Driver {
    /**
     * How many stops Google Maps takes between here and the destination in
     * a link opened on a phone. More are dropped without a word, so a trip
     * with more is split.
     */
    const val MAX_WAYPOINTS = 3

    /** The stops one link holds: the waypoints and the destination. */
    const val STOPS_PER_TRIP = MAX_WAYPOINTS + 1

    /** Whether the driver has anything to do with it: a delivery in the kitchen or on its way. */
    fun isForDriver(order: StaffOrder): Boolean =
        !order.pickup && (order.status == OrderStatus.CONFIRMED || order.status == OrderStatus.ON_THE_WAY)

    /** Whether it can be ticked for "Mitnehmen": a delivery still in the kitchen. */
    fun canTake(order: StaffOrder): Boolean = !order.pickup && order.status == OrderStatus.CONFIRMED

    /**
     * The order the pick-up list is read in: packed bags first, the one
     * waiting longest on top (packed_at, hamza-bistro-web#90 — absent until
     * then, which leaves every order unpacked); then by when it is due; then
     * by when it was placed.
     */
    val pickUpOrder: Comparator<StaffOrder> = Comparator { a, b ->
        val aPacked = a.packedAt
        val bPacked = b.packedAt
        if ((aPacked != null) != (bPacked != null)) return@Comparator if (aPacked != null) -1 else 1
        if (aPacked != null && bPacked != null) {
            val packed = aPacked.compareTo(bPacked)
            if (packed != 0) return@Comparator packed
        }
        val due = (StaffQueue.promisedAt(a) ?: Instant.MAX).compareTo(StaffQueue.promisedAt(b) ?: Instant.MAX)
        if (due != 0) return@Comparator due
        a.createdAt.compareTo(b.createdAt)
    }

    /** The deliveries still in the kitchen, in [pickUpOrder]. */
    fun toCollect(orders: List<StaffOrder>): List<StaffOrder> = orders.filter(::canTake).sortedWith(pickUpOrder)

    /**
     * What "Mitnehmen (n)" sends: of the [chosen] ids, the orders still in
     * the kitchen as this device last read them, each as it was read — so
     * every one of them is sent only from where this device saw it, and one
     * moved on elsewhere meanwhile is refused alone while the rest go
     * through.
     */
    fun batch(chosen: Set<String>, orders: List<StaffOrder>): List<StaffOrder> = toCollect(orders).filter { it.id in chosen }

    /** The ticks that still mean something once the queue has been read again. */
    fun stillChosen(chosen: Set<String>, orders: List<StaffOrder>): Set<String> {
        val open = orders.filter(::canTake).mapTo(HashSet()) { it.id }
        return chosen.filterTo(LinkedHashSet()) { it in open }
    }

    /**
     * The order to ride the stops in without a coordinate: by ring from the
     * shop outwards (an order without one last), then postcode, then
     * street, and the order number to settle the rest.
     */
    val stopOrder: Comparator<StaffOrder> =
        compareBy<StaffOrder> { Ring.of(it)?.ordinal ?: Ring.entries.size }
            .thenBy { it.postalCode?.trim().orEmpty().ifEmpty { "￿" } }
            .thenBy { (it.street?.trim().orEmpty().ifEmpty { it.address.trim() }).lowercase(Locale.GERMAN) }
            .thenBy { it.orderNumber }

    /**
     * The deliveries on their way, as the driver rides them: in the order
     * the driver put them in ([manual], order ids), and any the driver has
     * not placed after those, in [stopOrder].
     */
    fun stops(orders: List<StaffOrder>, manual: List<String> = emptyList()): List<StaffOrder> {
        val out = orders.filter { !it.pickup && it.status == OrderStatus.ON_THE_WAY }
        val byId = out.associateBy { it.id }
        val placed = manual.distinct().mapNotNull { byId[it] }
        val placedIds = placed.mapTo(HashSet()) { it.id }
        return placed + out.filter { it.id !in placedIds }.sortedWith(stopOrder)
    }

    /**
     * The stops' ids with [id] moved [by] places (-1 is up, towards the
     * shop): what ↑ and ↓ make the driver's own order. Unchanged at either
     * end, or for an id that is not a stop.
     */
    fun move(stops: List<StaffOrder>, id: String, by: Int): List<String> {
        val ids = stops.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return ids
        val to = (from + by).coerceIn(0, ids.lastIndex)
        if (to == from) return ids
        ids.add(to, ids.removeAt(from))
        return ids
    }

    /**
     * The stops cut into trips that each fit one link, in order. Four or
     * fewer are one trip; more are several, the next one opened when the
     * last is done.
     */
    fun trips(stops: List<StaffOrder>): List<List<StaffOrder>> = stops.chunked(STOPS_PER_TRIP)

    /**
     * How a trip is ridden: by car as soon as one stop is in an outer ring,
     * by bike when every known ring is an inner one, and as the device is
     * set ([fallback]) when no stop has a ring at all.
     */
    fun travelMode(trip: List<StaffOrder>, fallback: TravelMode): TravelMode {
        val rings = trip.mapNotNull(Ring::of)
        if (rings.isEmpty()) return fallback
        return if (rings.any { it.mode == TravelMode.DRIVING }) TravelMode.DRIVING else TravelMode.BICYCLING
    }

    /**
     * One Google Maps link for a trip, from wherever the phone is: the last
     * stop the destination, the ones before it waypoints, every address as
     * text ([StaffQueue.destinationQuery]) — never the door note. At most
     * [STOPS_PER_TRIP] stops; [trips] cuts them so.
     */
    fun googleRouteUrl(trip: List<StaffOrder>, fallback: TravelMode): String {
        require(trip.isNotEmpty()) { "a trip without stops" }
        require(trip.size <= STOPS_PER_TRIP) { "${trip.size} stops in one link; Google Maps takes $STOPS_PER_TRIP" }
        val queries = trip.map { StaffQueue.encodeUriComponent(StaffQueue.destinationQuery(it)) }
        return buildString {
            append("https://www.google.com/maps/dir/?api=1")
            append("&destination=").append(queries.last())
            if (queries.size > 1) append("&waypoints=").append(queries.dropLast(1).joinToString(WAYPOINT_SEPARATOR))
            append("&travelmode=").append(travelMode(trip, fallback).param)
        }
    }

    /**
     * The link "Route öffnen" opens for one trip: all its stops at once in
     * Google Maps; one stop at a time in the apps that take only one
     * destination (Waze, Apple Maps, whatever answers geo:), the first stop
     * of [trip] — "Nächster Stopp".
     */
    fun routeUrl(trip: List<StaffOrder>, app: NavApp, fallback: TravelMode): String =
        if (app == NavApp.GOOGLE) googleRouteUrl(trip, fallback)
        else StaffQueue.navigationUrl(trip.first(), app, travelMode(trip.take(1), fallback))

    /** Whether [app] takes several stops in one link, or the driver goes one stop at a time. */
    fun multiStop(app: NavApp): Boolean = app == NavApp.GOOGLE

    /** "|", as encodeURIComponent writes it: one link the same wherever it is opened from. */
    private const val WAYPOINT_SEPARATOR = "%7C"
}
