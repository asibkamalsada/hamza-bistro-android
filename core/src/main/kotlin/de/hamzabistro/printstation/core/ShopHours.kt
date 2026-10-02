package de.hamzabistro.printstation.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** A stretch of time in which the shop takes no orders. */
@Serializable
data class ShopClosure(
    val id: Long,
    @SerialName("starts_at") @Serializable(with = InstantSerializer::class) val startsAt: Instant,
    /** Null: until somebody opens again. */
    @SerialName("ends_at") @Serializable(with = InstantSerializer::class) val endsAt: Instant? = null,
    /** Who closed — told to staff only. */
    val by: String? = null,
)

/**
 * One weekday's delivery hours, as delivery_hours keeps them: minutes since
 * midnight, Leipzig time, on the quarter hour. A day without delivery keeps
 * its times, so a Ruhetag switched back on comes back with its hours.
 */
@Serializable
data class DeliveryDay(
    /** 0 = Sunday, as the database counts. */
    val day: Int,
    val delivers: Boolean,
    val opens: Int,
    /** 1440 is midnight at the end of the day. */
    val closes: Int,
) {
    /** What the table's own checks allow — on a day off too, since it keeps its times. */
    val valid: Boolean
        get() = opens % QUARTER == 0 && closes % QUARTER == 0 && opens >= 0 && closes <= MIDNIGHT && opens < closes

    companion object {
        const val QUARTER = 15
        const val MIDNIGHT = 24 * 60

        /** Monday first, as the shop's week is read. */
        val WEEK_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
    }
}

/** Where the shop stands, for the line above the queue. */
enum class ShopState {
    /** Orders come in. */
    OPEN,

    /** Somebody closed it: a pause, the evening, until further notice. */
    CLOSED,

    /** Outside the week's delivery hours; nobody closed anything. */
    OUTSIDE,
}

/**
 * Whether the shop takes orders, as shop_hours() in hamza-bistro-web works it
 * out (supabase/migrations/20261001150000_shop_hours.sql), with the week and
 * the closures it follows from.
 */
@Serializable
data class ShopHours(
    /** The week, by day; empty from a database that sent none. */
    val delivery: List<DeliveryDay> = emptyList(),
    @SerialName("open_now") val openNow: Boolean,
    /** While open: when that stops, by the hours or by a closure. */
    @SerialName("open_until") @Serializable(with = InstantSerializer::class) val openUntil: Instant? = null,
    /** While not: when orders come in again; null when nobody can say. */
    @SerialName("next_open") @Serializable(with = InstantSerializer::class) val nextOpen: Instant? = null,
    /** Running now or still to come. */
    val closures: List<ShopClosure> = emptyList(),
    /** Only in the answer to closing: open orders already booked for a time inside it. */
    @SerialName("preorders_inside") val preordersInside: Int = 0,
) {
    /** The closure running at [now], the one lasting longest where several overlap. */
    fun closureAt(now: Instant): ShopClosure? =
        closures
            .filter { !it.startsAt.isAfter(now) && (it.endsAt == null || it.endsAt.isAfter(now)) }
            .maxWithOrNull(compareBy(nullsLast()) { it.endsAt })

    /**
     * Where the shop stands at [now], which may be up to a poll later than
     * this was read: a closure that has started since is closed, and a
     * pause that has run out since is open again.
     */
    fun state(now: Instant): ShopState =
        when {
            closureAt(now) != null -> ShopState.CLOSED
            openNow && (openUntil == null || openUntil.isAfter(now)) -> ShopState.OPEN
            !openNow && nextOpen != null && !nextOpen.isAfter(now) -> ShopState.OPEN
            else -> ShopState.OUTSIDE
        }

    /** While closed: when orders come in again, as far as is known at [now]. */
    fun reopensAt(now: Instant): Instant? = nextOpen?.takeIf { it.isAfter(now) } ?: closureAt(now)?.endsAt
}

/**
 * Opening and closing the shop, and its delivery hours, as staff. The
 * database checks that the account is staff; a print account is refused
 * like anybody else. Every call answers with the hours as they now stand.
 */
interface ShopBackend {
    /** Where the shop stands; null on a database that does not know about closing yet. */
    suspend fun hours(): ShopHours?

    /**
     * Closes from [from] (now, when null) until [until] (until somebody opens
     * again, when null). Throws [InvalidHoursException] for one that ends
     * before it starts.
     */
    suspend fun close(until: Instant?, from: Instant? = null): ShopHours

    /** Ends every closure running now. One planned for later stays planned. */
    suspend fun open(): ShopHours

    /** Takes a closure off the list altogether: the holiday is not happening after all. */
    suspend fun removeClosure(id: Long): ShopHours

    /**
     * The whole week at once, so it is never saved half-changed. Throws
     * [InvalidHoursException] for a day that closes before it opens.
     */
    suspend fun saveWeek(week: List<DeliveryDay>): ShopHours
}

/** [ShopBackend] over PostgREST, as the signed-in account. */
class SupabaseShopBackend internal constructor(private val rest: SupabaseRest) : ShopBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun hours(): ShopHours? =
        try {
            parse(rest.rpc("shop_hours", buildJsonObject {}))
        } catch (e: BackendException) {
            if (e.missingFunction) null else throw e
        }

    override suspend fun close(until: Instant?, from: Instant?): ShopHours =
        call(
            "shop_close",
            buildJsonObject {
                if (until == null) put("p_until", JsonNull) else put("p_until", until.toString())
                if (from != null) put("p_from", from.toString())
            },
        )

    override suspend fun open(): ShopHours = call("shop_open", buildJsonObject {})

    override suspend fun removeClosure(id: Long): ShopHours = call("shop_closure_delete", buildJsonObject { put("p_id", id) })

    override suspend fun saveWeek(week: List<DeliveryDay>): ShopHours =
        call(
            "set_delivery_hours",
            buildJsonObject { put("p_hours", json.encodeToJsonElement(ListSerializer(DeliveryDay.serializer()), week)) },
        )

    private suspend fun call(name: String, args: JsonObject): ShopHours =
        try {
            parse(rest.rpc(name, args))
        } catch (e: BackendException) {
            // The database refusing what was sent as hours or as a closure.
            if (e.code == INVALID_HOURS) throw InvalidHoursException(e.message ?: name)
            throw e
        }

    private fun parse(body: String): ShopHours = json.decodeFromString(ShopHours.serializer(), body)

    private companion object {
        /** See 20261001150000_shop_hours.sql. */
        const val INVALID_HOURS = "HB432"
    }
}
