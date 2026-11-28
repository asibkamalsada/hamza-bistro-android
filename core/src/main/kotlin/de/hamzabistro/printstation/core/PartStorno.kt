package de.hamzabistro.printstation.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

// Teilstorno (hamza-bistro-web 20261128200000_teilstorno): part of a delivered
// order — a web order or a paid counter sale — taken back: some of its lines
// or some of a line's quantity, a fee whole, or an amount as goodwill. Each is
// a second Beleg of its own that the database books and the TSE signs. The
// database works out what goes back (order_part_storno_quote); this app shows
// it and sends what staff chose.

/** A line asked back: the order's item [line] (1-based, as in orders.items), [qty] of it. */
@Serializable
data class PartStornoLine(val line: Int, val qty: Int)

/** The fees a Teilstorno can take back, each whole. */
@Serializable
enum class PartStornoFee(val wire: String) {
    @SerialName("delivery") DELIVERY("delivery"),
    @SerialName("small_order") SMALL_ORDER("small_order"),
}

/** A Teilstorno as the database answers it (part_storno_json() in hamza-bistro-web). */
@Serializable
data class PartStorno(
    val id: String,
    /** Its own number, from the orders' sequence: on its receipt and Bon. */
    val number: Long,
    @SerialName("order_number") val orderNumber: Long,
    @SerialName("is_test") val training: Boolean = false,
    val amount: Double = 0.0,
    @SerialName("refund_method") val refundMethod: RefundMethod = RefundMethod.CASH,
    val reason: String = "",
    val lines: List<PartStornoLine> = emptyList(),
    val fees: List<PartStornoFee> = emptyList(),
    val goodwill: Double = 0.0,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_on") val createdOn: String? = null,
    /** This till's TSE signs it. A sale the TSE never had is taken back unsigned. */
    val signing: Boolean = false,
    /** Its receipt's link and state; null when the TSE does not sign it. */
    @SerialName("tse") val receipt: CounterReceipt? = null,
)

/** One row of order_part_stornos, as staff read it beside the history. */
@Serializable
data class PartStornoMark(
    val id: String,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("storno_number") val number: Long,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
    @SerialName("refund_method") val refundMethod: RefundMethod = RefundMethod.CASH,
    val amount: Double = 0.0,
)

/** order_part_storno_quote(): what a choice gives back, and what is left of the order. */
@Serializable
data class PartStornoQuote(
    @SerialName("order_number") val orderNumber: Long,
    val total: Double = 0.0,
    @SerialName("payment_method") val paymentMethod: PaymentMethod? = null,
    /** Taken back whole already. */
    val stornoed: Boolean = false,
    /** Given back by the Teilstornos before. */
    val before: Double = 0.0,
    /** Left to give back after this choice. */
    val left: Double = 0.0,
    /** What this choice gives back. */
    val amount: Double = 0.0,
    /** The most a goodwill may be beside the lines chosen. */
    @SerialName("goodwill_max") val goodwillMax: Double = 0.0,
    val lines: List<Line> = emptyList(),
    val fees: List<Fee> = emptyList(),
) {
    @Serializable
    data class Line(
        val line: Int,
        val name: String,
        val options: List<String> = emptyList(),
        val qty: Int,
        /** Taken back by Teilstornos before. */
        val taken: Int = 0,
        val price: Double = 0.0,
        val deposit: Double = 0.0,
        val drink: Boolean = false,
    ) {
        /** What is left of it to take back. */
        val left: Int
            get() = (qty - taken).coerceAtLeast(0)

        val label: String
            get() = if (options.isEmpty()) name else "$name (${options.joinToString(", ")})"
    }

    @Serializable
    data class Fee(val fee: PartStornoFee, val amount: Double = 0.0, val taken: Boolean = false)

    /** Every line and fee taken back and nothing left as goodwill. */
    val nothingLeft: Boolean
        get() = left <= 0.0
}

/** The rules the database keeps, mirrored so the app offers only what it will take. */
object PartStornos {
    /** The server update that brought the Teilstorno. */
    const val MIGRATION = "20261128200000_teilstorno"

    /** A delivered order, not taken back whole. */
    fun can(order: StaffOrder, mark: StornoMark?): Boolean = order.status == OrderStatus.DELIVERED && mark == null

    /** The choice as the database wants it: only lines with something asked, by line. */
    fun lines(asked: Map<Int, Int>): List<PartStornoLine> =
        asked.filterValues { it > 0 }.toSortedMap().map { (line, qty) -> PartStornoLine(line, qty) }

    /** A goodwill amount as typed ("3,50"): 0.0 for none, null for no amount. */
    fun goodwill(text: String): Double? {
        val typed = text.trim().replace(',', '.')
        if (typed.isEmpty()) return 0.0
        if (!Regex("""\d{1,4}(\.\d{1,2})?""").matches(typed)) return null
        return Math.round(typed.toDouble() * 100) / 100.0
    }

    fun quoteBody(orderNumber: Long, lines: List<PartStornoLine>, fees: Collection<PartStornoFee>, goodwill: Double): JsonObject =
        buildJsonObject {
            put("p_order_number", orderNumber)
            put("p_lines", linesJson(lines))
            put("p_fees", buildJsonArray { fees.sortedBy { it.wire }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.wire)) } })
            put("p_goodwill", goodwill)
        }

    fun body(
        id: String,
        orderNumber: Long,
        lines: List<PartStornoLine>,
        fees: Collection<PartStornoFee>,
        goodwill: Double,
        method: RefundMethod,
        reason: String,
        device: CounterDevice,
    ): JsonObject =
        buildJsonObject {
            put("p_id", id)
            put("p_order_number", orderNumber)
            put("p_lines", linesJson(lines))
            put("p_fees", buildJsonArray { fees.sortedBy { it.wire }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.wire)) } })
            put("p_goodwill", goodwill)
            put("p_refund_method", method.wire)
            put("p_reason", reason.trim().take(Stornos.REASON_MAX))
            put("p_device", device.toJson())
        }

    private fun linesJson(lines: List<PartStornoLine>) =
        buildJsonArray {
            for (l in lines) {
                add(
                    buildJsonObject {
                        put("line", l.line)
                        put("qty", l.qty)
                    }
                )
            }
        }
}

/** Taking part of sales back: [SupabasePartStornoBackend] over the database's functions. */
interface PartStornoBackend {
    /** What this choice would give back, and what is left of the order. */
    suspend fun quote(orderNumber: Long, lines: List<PartStornoLine>, fees: Collection<PartStornoFee>, goodwill: Double): PartStornoQuote

    /** "Zurückgeben". The same [id] again is the same Teilstorno. */
    suspend fun partStorno(
        id: String,
        orderNumber: Long,
        lines: List<PartStornoLine>,
        fees: Collection<PartStornoFee>,
        goodwill: Double,
        method: RefundMethod,
        reason: String,
    ): PartStorno

    /** The Teilstornos of these orders, by order id, oldest first. Empty on a database without them. */
    suspend fun marks(orderIds: Collection<String>): Map<String, List<PartStornoMark>>
}

/**
 * [PartStornoBackend] over PostgREST, as the signed-in account. Throws
 * [StornoException] for the till's refusals and [NeedsServerUpdateException]
 * on a database without it.
 */
class SupabasePartStornoBackend internal constructor(private val rest: SupabaseRest, private val device: () -> CounterDevice) :
    PartStornoBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager, device: () -> CounterDevice) :
        this(SupabaseRest(config, http, sessions), device)

    override suspend fun quote(orderNumber: Long, lines: List<PartStornoLine>, fees: Collection<PartStornoFee>, goodwill: Double): PartStornoQuote =
        json.decodeFromString(PartStornoQuote.serializer(), call("order_part_storno_quote", PartStornos.quoteBody(orderNumber, lines, fees, goodwill)))

    override suspend fun partStorno(
        id: String,
        orderNumber: Long,
        lines: List<PartStornoLine>,
        fees: Collection<PartStornoFee>,
        goodwill: Double,
        method: RefundMethod,
        reason: String,
    ): PartStorno =
        json.decodeFromString(
            PartStorno.serializer(),
            call("order_part_storno", PartStornos.body(id, orderNumber, lines, fees, goodwill, method, reason, device())),
        )

    override suspend fun marks(orderIds: Collection<String>): Map<String, List<PartStornoMark>> {
        if (orderIds.isEmpty()) return emptyMap()
        val url =
            rest.endpoint("rest/v1/order_part_stornos")
                .addQueryParameter("select", "id,order_id,storno_number,created_at,refund_method,amount")
                .addQueryParameter("order_id", orderIds.joinToString(",", "in.(", ")"))
                .addQueryParameter("order", "created_at")
                .build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "order_part_stornos")
                if (e is BackendException && e.missingTable) return@call emptyMap()
                throw e
            }
            json.decodeFromString(ListSerializer(PartStornoMark.serializer()), body)
                .filter { it.orderId != null }
                .groupBy { it.orderId!! }
        }
    }

    private suspend fun call(function: String, args: JsonObject): String =
        try {
            rest.rpc(function, args)
        } catch (e: Exception) {
            if (e is BackendException && e.missingFunction) throw NeedsServerUpdateException(PartStornos.MIGRATION)
            throw Stornos.error(e) ?: e
        }
}
