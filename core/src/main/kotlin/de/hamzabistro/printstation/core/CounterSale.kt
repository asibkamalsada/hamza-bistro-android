package de.hamzabistro.printstation.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

// The "Kasse": a sale at the counter, rung up here and recorded and signed by
// the database (hamza-bistro-web: 20261123100000_counter_sales). The database
// is the till. This app is an input device that works online only and keeps
// nothing about a sale: a device that could record one offline would be a
// till of its own.

/** The device a sale is rung up on, as the till records it. */
data class CounterDevice(
    /** This device's own id: random, made once and kept. */
    val id: String,
    val label: String,
    val brand: String,
    val model: String,
    /** This build's versionName. */
    val version: String,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("label", label)
        put("brand", brand)
        put("model", model)
        put("version", version)
    }
}

/** Whether the till takes real sales, and how many are open anywhere. */
@Serializable
data class CounterStatus(
    /** Signing is on: real sales are taken. Training always is. */
    val enabled: Boolean = false,
    val open: Int = 0,
    /** Open from an earlier day: they stop the next real sale. */
    val stale: Int = 0,
)

/** What a basket comes to at the counter, the deal of the day taken off. */
@Serializable
data class CounterPrice(
    val total: Double,
    @SerialName("deal_discount") val dealDiscount: Double = 0.0,
)

/** One line of a sale, as the database priced it. */
@Serializable
data class CounterLine(
    val id: Long = 0,
    val name: String = "",
    val options: List<String> = emptyList(),
    val price: Double = 0.0,
    val deposit: Double = 0.0,
    val qty: Int = 1,
)

/** What the receipt knows of the TSE: receipt_tse() in hamza-bistro-web. */
@Serializable
data class CounterReceipt(
    /** The receipt link's token. */
    val token: String = "",
    /** signed, pending, failed or unsigned. */
    val state: String = "",
    /** The TSE did not sign at the payment: late, failed or still out. */
    val outage: Boolean = false,
    val training: Boolean = false,
    val env: String? = null,
) {
    val pending: Boolean
        get() = state == "pending"

    val signed: Boolean
        get() = state == "signed"

    /** What the customer scans: their receipt on their own phone. */
    val link: String
        get() = "${AddressRequests.SITE}/beleg/$token"
}

/** Where a sale is. */
enum class SaleState {
    /** Rung up: lines and price fixed, waiting for the money. */
    OPEN,
    PAID,
    ABORTED,

    /** A state this version does not know. */
    UNKNOWN,
}

/** How a counter sale is paid. */
enum class CounterPayment(val wire: String) {
    CASH("cash"),
    CARD("card"),
}

/** A counter sale as the database has it. */
@Serializable
data class CounterSale(
    val id: String,
    val number: Long,
    @SerialName("state") val stateName: String = "",
    @SerialName("is_test") val training: Boolean = false,
    @SerialName("dine_in") val dineIn: Boolean = false,
    @SerialName("items") val lines: List<CounterLine> = emptyList(),
    val total: Double = 0.0,
    /** "card" while the card terminal is being asked. */
    @SerialName("pending_method") val pendingMethod: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    /** The S-POS phone a card-paid sale went through; null when none was named. */
    @SerialName("card_terminal_id") val cardTerminalId: Int? = null,
    /** The tip paid on the card on top of [total] (20261128110000). */
    val tip: Double = 0.0,
    @SerialName("opened_at") @Serializable(with = InstantSerializer::class) val openedAt: Instant? = null,
    @SerialName("opened_by") val openedBy: String = "",
    /** The name of the device it was rung up on. */
    @SerialName("opened_on") val openedOn: String? = null,
    /** The order it became, once paid: what the receipt is printed from. */
    @SerialName("order_id") val orderId: String? = null,
    /** Open from an earlier day. */
    val stale: Boolean = false,
    /** The TSE knows of it. */
    val signing: Boolean = false,
    @SerialName("tse") val receipt: CounterReceipt? = null,
    /** A paper Notbeleg entered after a Kassenausfall (20261128140000); null for a sale rung up here. */
    val late: LateMark? = null,
) {
    val state: SaleState
        get() =
            when (stateName) {
                "open" -> SaleState.OPEN
                "paid" -> SaleState.PAID
                "aborted" -> SaleState.ABORTED
                else -> SaleState.UNKNOWN
            }

    val cardPending: Boolean
        get() = pendingMethod == CounterPayment.CARD.wire

    val payment: CounterPayment?
        get() = CounterPayment.entries.firstOrNull { it.wire == paymentMethod }
}

/** Why the till refused, by its error code. */
/** The Notbeleg a counter sale was entered from, after a Kassenausfall. */
@Serializable
data class LateMark(
    val slip: Int,
    /** The outage note's id. */
    val outage: String? = null,
    @SerialName("entered_at") @Serializable(with = InstantSerializer::class) val enteredAt: Instant? = null,
)

enum class CounterError(val code: String) {
    /** 42501: the account is not staff (any more). */
    NOT_STAFF("42501"),

    /** HB409: the total is not the one shown: prices or stock changed meanwhile. */
    TOTAL_CHANGED("HB409"),

    /** HB467, HB497, HB498: something this app should never send. */
    BAD_VALUE("HB467"),

    /** HB471: the basket — a dish or option sold out, a quantity. */
    BASKET("HB471"),

    /** HB490: signing is off, so no real sale is taken. */
    OFF("HB490"),

    /** HB491: no such sale. */
    GONE("HB491"),

    /** HB492: the sale is already paid, or aborted. */
    ALREADY("HB492"),

    /** HB493: this sale's id belongs to another basket. */
    KEY_REUSED("HB493"),

    /** HB494: a sale is still open from an earlier day. */
    STALE("HB494"),

    /** HB495: the basket has no VAT split. */
    NO_SPLIT("HB495"),

    /** HB496: somebody gave up on its TSE transaction: abort it, ring it up again. */
    DISMISSED("HB496"),

    /** HB506: an S-POS phone with cash, or one that is not in the list (any more). */
    CARD_TERMINAL("HB506"),

    /** HB507: a tip with cash, or one that is not 0,01 to 50,00 €. */
    TIP("HB507");

    companion object {
        fun of(code: String?): CounterError? =
            when (code) {
                "HB497", "HB498" -> BAD_VALUE
                else -> entries.firstOrNull { it.code == code }
            }
    }
}

/** The till refused, for [reason]. */
class CounterException(val reason: CounterError, message: String) : Exception(message)

/** What the Kasse sends, and the sums it does itself. */
object CounterSales {
    /** The server update that brought the till. */
    const val MIGRATION = "20261123100000_counter_sales"

    /**
     * The menu at the board's prices: the counter's price is the site's less
     * the Selbstabholerrabatt, which never eats into the Pfand.
     */
    fun boardMenu(menu: OrderMenu): OrderMenu =
        menu.copy(
            dishes =
                menu.dishes.map { dish ->
                    val off = minOf(cents(dish.pickupDiscount), maxOf(cents(dish.price) - cents(dish.deposit), 0))
                    dish.copy(price = euros(cents(dish.price) - maxOf(off, 0)))
                }
        )

    /** What the lines come to by the menu, in cents: shown until the database has answered. */
    fun subtotal(lines: List<BasketLine>): Long = lines.sumOf { cents(it.unitPrice) * it.qty }

    fun items(lines: List<BasketLine>): JsonArray = buildJsonArray {
        for (line in lines) {
            add(buildJsonObject {
                put("id", line.dish.id)
                put("qty", line.qty)
                put("options", buildJsonArray { line.options.forEach { add(JsonPrimitive(it.id)) } })
            })
        }
    }

    fun openBody(id: String, lines: List<BasketLine>, dineIn: Boolean, training: Boolean, expectedTotal: Double?, device: CounterDevice): JsonObject =
        buildJsonObject {
            put("p_id", id)
            put("p_fulfilment", if (dineIn) Fulfilment.DINE_IN.wire else Fulfilment.PICKUP.wire)
            put("p_items", items(lines))
            put("p_device", device.toJson())
            put("p_expected_total", expectedTotal?.let { JsonPrimitive(it) } ?: JsonNull)
            put("p_test", training)
        }

    /**
     * "Bar" or "Karte bezahlt". The S-POS phone goes only with a card, and
     * only when one was picked: a database before 20261128100000 knows no
     * p_card_terminal, and without it pays as before, phone unknown. The tip
     * the same way: only with a card and only when there is one, since a
     * database before 20261128110000 knows no p_tip.
     */
    fun payBody(id: String, method: CounterPayment, device: CounterDevice, cardTerminal: Int?, tip: Double? = null): JsonObject =
        buildJsonObject {
            put("p_id", id)
            put("p_method", method.wire)
            put("p_device", device.toJson())
            if (method == CounterPayment.CARD) cardTerminal?.let { put("p_card_terminal", it) }
            Tips.sent(tip, method == CounterPayment.CARD)?.let { put("p_tip", it) }
        }

    /** The till's refusal as a [CounterException]; null for anything else. */
    fun error(e: Exception): CounterException? {
        if (e is NotAllowedException) return CounterException(CounterError.NOT_STAFF, e.message ?: "staff only")
        if (e !is BackendException) return null
        val reason = CounterError.of(e.code) ?: return null
        return CounterException(reason, e.message ?: reason.code)
    }

    fun cents(euros: Double): Long = Math.round(euros * 100)

    fun euros(cents: Long): Double = cents / 100.0

    /** "20", "20,5" or "20.50" in cents; null for anything that is no amount. */
    fun parse(text: String): Long? {
        val match = AMOUNT.matchEntire(text.trim().removeSuffix("€").trim()) ?: return null
        val (whole, part) = match.destructured
        return whole.toLong() * 100 + part.padEnd(2, '0').toLong()
    }

    private val AMOUNT = Regex("(\\d{1,6})(?:[.,](\\d{0,2}))?")

    /** What to give back on [given]; null when it is not enough. */
    fun change(totalCents: Long, given: Long): Long? = (given - totalCents).takeIf { it >= 0 }

    /** What somebody is likely to hand over for [totalCents]: the next fives, tens and notes above it. */
    fun notes(totalCents: Long): List<Long> =
        listOf(500L, 1000L, 2000L, 5000L, 10000L)
            .map { step -> (totalCents / step + 1) * step }
            .filter { it > totalCents }
            .distinct()
            .sorted()
            .take(4)
}

/** The till: [SupabaseCounterBackend] over the database's functions. */
interface CounterBackend {
    /** Whether there is a till; null on a database without one. */
    suspend fun status(): CounterStatus?

    suspend fun price(lines: List<BasketLine>): CounterPrice

    /** "Kassieren". The same [id] again is the same sale. */
    suspend fun open(id: String, lines: List<BasketLine>, dineIn: Boolean, training: Boolean, expectedTotal: Double?): CounterSale

    /** Whether the card terminal is being asked. Books nothing. */
    suspend fun cardPending(id: String, pending: Boolean): CounterSale

    /**
     * The money is there; by card, on [cardTerminal] when one was picked
     * (20261128100000), with [tip] on top when there is one (20261128110000).
     */
    suspend fun pay(id: String, method: CounterPayment, cardTerminal: Int? = null, tip: Double? = null): CounterSale

    suspend fun abort(id: String): CounterSale

    suspend fun get(id: String): CounterSale

    /** Every open sale, on every device, the oldest first. */
    suspend fun openSales(): List<CounterSale>
}

/**
 * [CounterBackend] over PostgREST, as the signed-in account. Throws
 * [CounterException] for the till's refusals and
 * [NeedsServerUpdateException] on a database without it.
 */
class SupabaseCounterBackend internal constructor(private val rest: SupabaseRest, private val device: () -> CounterDevice) : CounterBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager, device: () -> CounterDevice) :
        this(SupabaseRest(config, http, sessions), device)

    override suspend fun status(): CounterStatus? =
        try {
            json.decodeFromString(CounterStatus.serializer(), rest.rpc("counter_status", buildJsonObject {}))
        } catch (e: BackendException) {
            if (e.missingFunction) null else throw CounterSales.error(e) ?: e
        }

    override suspend fun price(lines: List<BasketLine>): CounterPrice =
        json.decodeFromString(CounterPrice.serializer(), call("counter_sale_price", buildJsonObject { put("p_items", CounterSales.items(lines)) }))

    override suspend fun open(id: String, lines: List<BasketLine>, dineIn: Boolean, training: Boolean, expectedTotal: Double?): CounterSale =
        sale("counter_sale_open", CounterSales.openBody(id, lines, dineIn, training, expectedTotal, device()))

    override suspend fun cardPending(id: String, pending: Boolean): CounterSale =
        sale("counter_sale_method", buildJsonObject {
            put("p_id", id)
            put("p_method", if (pending) JsonPrimitive(CounterPayment.CARD.wire) else JsonNull)
        })

    override suspend fun pay(id: String, method: CounterPayment, cardTerminal: Int?, tip: Double?): CounterSale =
        sale("counter_sale_pay", CounterSales.payBody(id, method, device(), cardTerminal, tip))

    override suspend fun abort(id: String): CounterSale =
        sale("counter_sale_abort", buildJsonObject {
            put("p_id", id)
            put("p_device", device().toJson())
        })

    override suspend fun get(id: String): CounterSale = sale("counter_sale_get", buildJsonObject { put("p_id", id) })

    override suspend fun openSales(): List<CounterSale> =
        json.decodeFromString(ListSerializer(CounterSale.serializer()), call("counter_sales_open", buildJsonObject {}))

    private suspend fun sale(function: String, args: JsonObject): CounterSale =
        json.decodeFromString(CounterSale.serializer(), call(function, args))

    private suspend fun call(function: String, args: JsonObject): String =
        try {
            rest.rpc(function, args)
        } catch (e: Exception) {
            if (e is BackendException && e.missingFunction) throw NeedsServerUpdateException(CounterSales.MIGRATION)
            throw CounterSales.error(e) ?: e
        }
}
