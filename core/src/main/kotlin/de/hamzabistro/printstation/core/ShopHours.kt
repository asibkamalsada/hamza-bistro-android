package de.hamzabistro.printstation.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
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
 * out (supabase/migrations/20261001150000_shop_hours.sql). The week itself is
 * in the answer too, and not read here: it is set on the website, at
 * /orders/hours, and the app only needs to know what follows from it.
 */
@Serializable
data class ShopHours(
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
 * Opening and closing the shop, as staff. The database checks that the
 * account is staff; a print account is refused like anybody else.
 */
interface ShopBackend {
    /** Where the shop stands; null on a database that does not know about closing yet. */
    suspend fun hours(): ShopHours?

    /** Closes from now until [until] — until somebody opens again, when null. */
    suspend fun close(until: Instant?): ShopHours

    /** Ends every closure running now. One planned for later stays planned. */
    suspend fun open(): ShopHours
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

    override suspend fun close(until: Instant?): ShopHours =
        parse(
            rest.rpc(
                "shop_close",
                buildJsonObject {
                    if (until == null) put("p_until", JsonNull) else put("p_until", until.toString())
                },
            )
        )

    override suspend fun open(): ShopHours = parse(rest.rpc("shop_open", buildJsonObject {}))

    private fun parse(body: String): ShopHours = json.decodeFromString(ShopHours.serializer(), body)
}
