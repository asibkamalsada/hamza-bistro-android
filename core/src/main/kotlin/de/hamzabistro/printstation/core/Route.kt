package de.hamzabistro.printstation.core

/**
 * How far the door is, as staff read it under the address: road km and
 * riding minutes from the shop — Ride and rideOf() in
 * supabase/functions/_shared/route.ts of hamza-bistro-web, which the site and
 * the Telegram group read the same columns with.
 *
 * Information for whoever accepts the order, not a promise: the minutes
 * they press are the kitchen plus this. Read from an order as [StaffOrder.ride].
 */
data class Ride(
    /** Road km, one decimal. Null on an estimate with no point to measure from. */
    val km: Double?,
    val minutes: Int,
    /** Not routed: the straight line and the ring, or the ring alone. */
    val estimated: Boolean,
) {
    companion object {
        /** route_source when the shop's own router worked it out. */
        const val ROUTED = "routed"
    }
}
