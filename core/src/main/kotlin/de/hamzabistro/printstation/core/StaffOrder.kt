package de.hamzabistro.printstation.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Where an order is — the status column, with the steps between them
 * enforced by the database (order_status_step in
 * 20260927140000_order_handling.sql in hamza-bistro-web).
 */
@Serializable
enum class OrderStatus {
    @SerialName("new") NEW,
    @SerialName("confirmed") CONFIRMED,
    @SerialName("on_the_way") ON_THE_WAY,
    @SerialName("delivered") DELIVERED,
    @SerialName("cancelled") CANCELLED;

    /** Still in play: somebody has something to do with it. */
    val open: Boolean
        get() = this == NEW || this == CONFIRMED || this == ON_THE_WAY

    /** As the column spells it, for a filter. */
    val column: String
        get() =
            when (this) {
                NEW -> "new"
                CONFIRMED -> "confirmed"
                ON_THE_WAY -> "on_the_way"
                DELIVERED -> "delivered"
                CANCELLED -> "cancelled"
            }
}

/**
 * Why an order was called off — the four buttons /orders offers, and the
 * database's own [TIMEOUT]: all that orders_cancel_reason_check allows. The
 * customer's email says it.
 */
@Serializable
enum class CancelReason {
    @SerialName("busy") BUSY,
    @SerialName("sold_out") SOLD_OUT,
    @SerialName("unreachable") UNREACHABLE,
    @SerialName("address") ADDRESS,

    /**
     * Nobody answered in time, and the database declined it
     * (20261002150000_auto_decline.sql). Read, never sent: the database
     * refuses it from anybody but its own job.
     */
    @SerialName("timeout") TIMEOUT;

    companion object {
        /** What a person may give as the reason: the buttons, never [TIMEOUT]. */
        val CHOSEN: List<CancelReason> = listOf(BUSY, SOLD_OUT, UNREACHABLE, ADDRESS)
    }
}

/** One line of an order as it was placed: what, how many, with what, at what price. */
@Serializable
data class OrderItem(
    val id: Long,
    val name: String,
    /** Chosen option names; absent on orders placed before options existed. */
    val options: List<String> = emptyList(),
    /** Unit price including option surcharges and any deposit. */
    val price: Double = 0.0,
    /** Deposit portion per unit. */
    val deposit: Double = 0.0,
    val qty: Int,
)

/**
 * An order as the staff queue reads it — the columns of COLUMNS in the
 * site's staff-orders.service.ts. Staff may read every order; what this
 * device keeps of it lives in memory only.
 */
@Serializable
data class StaffOrder(
    val id: String,
    @SerialName("order_number") val orderNumber: Long,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    /** When it was accepted, from the database clock. Null while new. */
    @SerialName("confirmed_at") @Serializable(with = InstantSerializer::class) val confirmedAt: Instant? = null,
    /** The time it was ordered ahead for; null means as soon as possible. */
    @SerialName("scheduled_for") @Serializable(with = InstantSerializer::class) val scheduledFor: Instant? = null,
    @SerialName("customer_name") val customerName: String = "",
    val phone: String = "",
    /** The composed one-line address, note included. What older orders have. */
    val address: String = "",
    val street: String? = null,
    @SerialName("postal_code") val postalCode: String? = null,
    val city: String? = null,
    /** "Hinterhaus, 3. OG" — for the rider at the door, not for the map. */
    @SerialName("address_note") val addressNote: String? = null,
    /** What the customer asked the kitchen for. */
    val notes: String = "",
    val items: List<OrderItem> = emptyList(),
    val total: Double = 0.0,
    val status: OrderStatus,
    @SerialName("eta_minutes") val etaMinutes: Int? = null,
    @SerialName("cancel_reason") val cancelReason: CancelReason? = null,
    /** Delivered to within the last month. False is the order worth a call. */
    @SerialName("returning_customer") val returningCustomer: Boolean = false,
    @SerialName("delivery_fee") val deliveryFee: Double = 0.0,
    @SerialName("small_order_fee") val smallOrderFee: Double = 0.0,
    val discount: Double = 0.0,
    @SerialName("discount_code") val discountCode: String? = null,
    val pickup: Boolean = false,
    @SerialName("pickup_discount") val pickupDiscount: Double = 0.0,
    @SerialName("stamp_discount") val stampDiscount: Double = 0.0,
    /** The ring it was priced in, which the ETA's riding time is read from. */
    @SerialName("delivery_zone") val deliveryZone: String? = null,
    /** "postcode" when the map did not know the address: worth a word on the call. */
    @SerialName("delivery_zone_source") val deliveryZoneSource: String? = null,
    /** When its ticket came out, wherever. Null: not printed anywhere that said so. */
    @SerialName("printed_at") @Serializable(with = InstantSerializer::class) val printedAt: Instant? = null,
    /**
     * When the database declines it unanswered — the computed column
     * auto_decline_at(orders). Null once it is not new, or with auto-decline
     * off.
     */
    @SerialName("auto_decline_at") @Serializable(with = InstantSerializer::class) val autoDeclineAt: Instant? = null,
) {
    // Never the customer, wherever an order ends up printed.
    override fun toString(): String = "StaffOrder(#$orderNumber, $status)"

    /** Fees inside the total, so the driver can explain the figure at the door. */
    val fees: Double
        get() = deliveryFee + smallOrderFee

    /** The refundable bottle deposit inside the total. */
    val depositTotal: Double
        get() = items.sumOf { it.deposit * it.qty }

    /** Ordered from an address the map could not place: the price came from the postcode. */
    val unverifiedAddress: Boolean
        get() = !pickup && deliveryZoneSource == "postcode"

    companion object {
        /** What the queue reads, and nothing more. */
        const val COLUMNS =
            "id,order_number,created_at,confirmed_at,scheduled_for,customer_name,phone,address,street," +
                "postal_code,city,address_note,notes,items,total,status,eta_minutes,cancel_reason," +
                "returning_customer,delivery_fee,small_order_fee,discount,discount_code,pickup," +
                "pickup_discount,stamp_discount,delivery_zone,delivery_zone_source,printed_at,auto_decline_at"
    }
}

/**
 * One step on an order: where it goes, and what goes with it. Exactly the
 * steps /orders offers; the database refuses any other (HB412).
 */
sealed interface OrderStep {
    val to: OrderStatus

    /** Accepted for right away, with the minutes promised. */
    data class Accept(val etaMinutes: Int) : OrderStep {
        override val to = OrderStatus.CONFIRMED
    }

    /** Accepted for the time the customer chose. */
    data object AcceptScheduled : OrderStep {
        override val to = OrderStatus.CONFIRMED
    }

    /** Out of the door, or onto the counter for collection. */
    data object Out : OrderStep {
        override val to = OrderStatus.ON_THE_WAY
    }

    /** At the door, or collected. */
    data object Done : OrderStep {
        override val to = OrderStatus.DELIVERED
    }

    /** Declined while new, or cancelled later; [reason] is what the customer is told. */
    data class Cancel(val reason: CancelReason?) : OrderStep {
        override val to = OrderStatus.CANCELLED
    }
}

/**
 * PostgREST's timestamptz — "2026-09-26T16:00:00.123456+00:00" — as an
 * [Instant]. Written back in ISO form, which only tests need.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e: DateTimeParseException) {
            Instant.parse(text)
        }
    }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}
