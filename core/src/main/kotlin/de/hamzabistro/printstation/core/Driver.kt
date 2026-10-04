package de.hamzabistro.printstation.core

import java.time.Instant
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A place on the map, in degrees. */
data class GeoPoint(val lat: Double, val lon: Double) {
    /** As the crow flies, in metres (haversine): near enough for which stop is nearer. */
    fun metresTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(h)))
    }

    /** "51.33553,12.32958", as a map link takes it, whatever the device's language. */
    val query: String
        get() = "${String.format(Locale.ROOT, "%.5f", lat)},${String.format(Locale.ROOT, "%.5f", lon)}"

    private companion object {
        const val EARTH_RADIUS_METRES = 6_371_000.0
    }
}

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
 * The stops are ridden nearest first from the shop, then nearest next, by
 * the coordinate the server stores per order (hamza-bistro-web#141); the
 * ones without a coordinate after those, by ring, then postcode, then
 * street. The driver puts right what that gets wrong.
 */
object Driver {
    /**
     * Where every trip starts: SHOP_LOCATION in hamza-bistro-web's
     * delivery-area.ts, the centre the rings are measured from. Not in the
     * database.
     */
    val SHOP = GeoPoint(51.33553, 12.32958)

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
     * waiting longest on top ([StaffQueue.packedFirst], hamza-bistro-web#90
     * — the same rule as the top of "Unterwegs & abholbereit"); then by when
     * it is due; then by when it was placed.
     */
    val pickUpOrder: Comparator<StaffOrder> = Comparator { a, b ->
        val packed = StaffQueue.packedFirst.compare(a, b)
        if (packed != 0) return@Comparator packed
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
     * street, and the order number to settle the rest. Also what settles a
     * tie in distance.
     */
    val stopOrder: Comparator<StaffOrder> =
        compareBy<StaffOrder> { Ring.of(it)?.ordinal ?: Ring.entries.size }
            .thenBy { it.postalCode?.trim().orEmpty().ifEmpty { "￿" } }
            .thenBy { (it.street?.trim().orEmpty().ifEmpty { it.address.trim() }).lowercase(Locale.GERMAN) }
            .thenBy { it.orderNumber }

    /**
     * [orders] in the order to ride them from [from]: the nearest stop
     * with a coordinate first, then the one nearest to that, and so on (a
     * greedy nearest neighbour — a handful of stops, not a salesman);
     * a tie goes by [stopOrder]. The ones without a coordinate after them,
     * in [stopOrder].
     */
    fun route(orders: List<StaffOrder>, from: GeoPoint = SHOP): List<StaffOrder> {
        val (located, rest) = orders.partition { it.point != null }
        val left = located.sortedWith(stopOrder).toMutableList()
        val out = ArrayList<StaffOrder>(orders.size)
        var here = from
        while (left.isNotEmpty()) {
            // The first of the nearest: left is in stopOrder, so a tie keeps it.
            val next = left.minBy { here.metresTo(it.point!!) }
            left.remove(next)
            out += next
            here = next.point!!
        }
        return out + rest.sortedWith(stopOrder)
    }

    /**
     * The deliveries on their way, as the driver rides them: in the order
     * the driver put them in ([manual], order ids), and any the driver has
     * not placed after those, in [route] from the last placed stop with a
     * coordinate (the shop when there is none).
     */
    fun stops(orders: List<StaffOrder>, manual: List<String> = emptyList()): List<StaffOrder> {
        val out = orders.filter { !it.pickup && it.status == OrderStatus.ON_THE_WAY }
        val byId = out.associateBy { it.id }
        val placed = manual.distinct().mapNotNull { byId[it] }
        val placedIds = placed.mapTo(HashSet()) { it.id }
        val from = placed.lastOrNull { it.point != null }?.point ?: SHOP
        return placed + route(out.filter { it.id !in placedIds }, from)
    }

    /**
     * How far each stop is from the one before it, the first from the
     * shop, in metres as the crow flies; null where either end has no
     * coordinate.
     */
    fun legs(stops: List<StaffOrder>): List<Double?> {
        var here: GeoPoint? = SHOP
        return stops.map { stop ->
            val point = stop.point
            val leg = point?.let { here?.metresTo(it) }
            here = point
            leg
        }
    }

    /** "1,2 km", in German whatever the device's language, as the money is. */
    fun km(metres: Double): String = String.format(Locale.GERMANY, "%.1f km", metres / 1000)

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
     * stop the destination, the ones before it waypoints, each as its
     * coordinate where it has one ([GeoPoint.query]) and as its address
     * ([StaffQueue.destinationQuery]) where not — never the door note. At most
     * [STOPS_PER_TRIP] stops; [trips] cuts them so.
     */
    fun googleRouteUrl(trip: List<StaffOrder>, fallback: TravelMode): String {
        require(trip.isNotEmpty()) { "a trip without stops" }
        require(trip.size <= STOPS_PER_TRIP) { "${trip.size} stops in one link; Google Maps takes $STOPS_PER_TRIP" }
        val queries = trip.map { StaffQueue.encodeUriComponent(it.point?.query ?: StaffQueue.destinationQuery(it)) }
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
