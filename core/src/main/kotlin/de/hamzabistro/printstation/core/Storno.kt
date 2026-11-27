package de.hamzabistro.printstation.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

// Storno: a delivered order — a web order or a paid counter sale — taken back,
// all of it, by a second Beleg the database books and the TSE signs
// (hamza-bistro-web: 20261127100000_storno). The order itself is never
// changed. As with the Kasse, the database is the till and decides
// everything; this app sends what staff chose and shows what came back.

/** How the money goes back: in cash, or the way it was paid. */
@Serializable
enum class RefundMethod(val wire: String) {
    @SerialName("cash") CASH("cash"),
    @SerialName("card") CARD("card"),
    @SerialName("online") ONLINE("online"),
}

/** A Storno as the database answers it (storno_json() in hamza-bistro-web). */
@Serializable
data class Storno(
    val id: String,
    /** Its own number, from the orders' sequence: on its receipt and Bon. */
    val number: Long,
    @SerialName("order_number") val orderNumber: Long,
    @SerialName("is_test") val training: Boolean = false,
    val amount: Double = 0.0,
    @SerialName("refund_method") val refundMethod: RefundMethod = RefundMethod.CASH,
    val reason: String = "",
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_on") val createdOn: String? = null,
    /** This till's TSE signs it. A sale the TSE never had is taken back unsigned. */
    val signing: Boolean = false,
    /** Its receipt's link and state; null when the TSE does not sign it. */
    @SerialName("tse") val receipt: CounterReceipt? = null,
)

/** That an order was taken back: one row of order_stornos, as staff read it. */
@Serializable
data class StornoMark(
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("storno_number") val number: Long,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
    @SerialName("refund_method") val refundMethod: RefundMethod = RefundMethod.CASH,
)

/** Why the till refused a Storno, by its error code. */
enum class StornoError(val code: String) {
    /** 42501: the account is not staff (any more). */
    NOT_STAFF("42501"),

    /** HB499, HB497: something this app should never send (no id, a device without one). */
    BAD_VALUE("HB499"),

    /** HB500: this id is another Storno's. */
    KEY_REUSED("HB500"),

    /** HB501: no reason given. */
    NO_REASON("HB501"),

    /** HB502: not a delivered order. */
    NOT_DELIVERED("HB502"),

    /** HB503: the order has been taken back already. */
    ALREADY("HB503"),

    /** HB504: not a way to give this order's money back. */
    METHOD("HB504"),

    /** HB505: the TSE has not signed the sale yet. */
    UNSIGNED("HB505");

    companion object {
        fun of(code: String?): StornoError? =
            when (code) {
                "HB497" -> BAD_VALUE
                else -> entries.firstOrNull { it.code == code }
            }
    }
}

/** The till refused the Storno, for [reason]. */
class StornoException(val reason: StornoError, message: String) : Exception(message)

/** The rules the database keeps, mirrored so the app offers only what it will take. */
object Stornos {
    /** The server update that brought the Storno. */
    const val MIGRATION = "20261127100000_storno"

    /** Mirrors the column: the most a reason may say. */
    const val REASON_MAX = 300

    /** Only a handed-over order is taken back, and once; before, it is cancelled. */
    fun can(order: StaffOrder, mark: StornoMark?): Boolean = order.status == OrderStatus.DELIVERED && mark == null

    /** The ways the money can go back, the usual first: the way it was paid, and cash. */
    fun refundMethods(paid: PaymentMethod?): List<RefundMethod> =
        when (paid) {
            PaymentMethod.CARD -> listOf(RefundMethod.CARD, RefundMethod.CASH)
            PaymentMethod.ONLINE -> listOf(RefundMethod.ONLINE, RefundMethod.CASH)
            PaymentMethod.CASH, null -> listOf(RefundMethod.CASH)
        }

    fun body(id: String, orderNumber: Long, method: RefundMethod, reason: String, device: CounterDevice): JsonObject =
        buildJsonObject {
            put("p_id", id)
            put("p_order_number", orderNumber)
            put("p_refund_method", method.wire)
            put("p_reason", reason.trim().take(REASON_MAX))
            put("p_device", device.toJson())
        }

    /** The till's refusal as a [StornoException]; null for anything else. */
    fun error(e: Exception): StornoException? {
        if (e is NotAllowedException) return StornoException(StornoError.NOT_STAFF, e.message ?: "staff only")
        if (e !is BackendException) return null
        val reason = StornoError.of(e.code) ?: return null
        return StornoException(reason, e.message ?: reason.code)
    }
}

/** Taking sales back: [SupabaseStornoBackend] over the database's functions. */
interface StornoBackend {
    /** "Stornieren". The same [id] again is the same Storno. */
    suspend fun storno(id: String, orderNumber: Long, method: RefundMethod, reason: String): Storno

    /** One Storno by its id, or null: to resume after a lost answer. */
    suspend fun get(id: String): Storno?

    /** Which of these orders were taken back, by order id. Empty on a database without the Storno. */
    suspend fun marks(orderIds: Collection<String>): Map<String, StornoMark>
}

/**
 * [StornoBackend] over PostgREST, as the signed-in account. Throws
 * [StornoException] for the till's refusals and [NeedsServerUpdateException]
 * on a database without it.
 */
class SupabaseStornoBackend internal constructor(private val rest: SupabaseRest, private val device: () -> CounterDevice) : StornoBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager, device: () -> CounterDevice) :
        this(SupabaseRest(config, http, sessions), device)

    override suspend fun storno(id: String, orderNumber: Long, method: RefundMethod, reason: String): Storno =
        json.decodeFromString(Storno.serializer(), call("order_storno", Stornos.body(id, orderNumber, method, reason, device())))

    override suspend fun get(id: String): Storno? {
        val text = call("order_storno_get", buildJsonObject { put("p_id", id) })
        return if (text.trim() == "null") null else json.decodeFromString(Storno.serializer(), text)
    }

    override suspend fun marks(orderIds: Collection<String>): Map<String, StornoMark> {
        if (orderIds.isEmpty()) return emptyMap()
        val url =
            rest.endpoint("rest/v1/order_stornos")
                .addQueryParameter("select", "order_id,storno_number,created_at,refund_method")
                .addQueryParameter("order_id", orderIds.joinToString(",", "in.(", ")"))
                .build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "order_stornos")
                if (e is BackendException && e.missingTable) return@call emptyMap()
                throw e
            }
            json.decodeFromString(ListSerializer(StornoMark.serializer()), body)
                .mapNotNull { mark -> mark.orderId?.let { it to mark } }
                .toMap()
        }
    }

    private suspend fun call(function: String, args: JsonObject): String =
        try {
            rest.rpc(function, args)
        } catch (e: Exception) {
            if (e is BackendException && e.missingFunction) throw NeedsServerUpdateException(Stornos.MIGRATION)
            throw Stornos.error(e) ?: e
        }
}
