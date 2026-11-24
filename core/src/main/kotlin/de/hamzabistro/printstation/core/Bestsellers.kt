package de.hamzabistro.printstation.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/** One delivered order, as far as it says what sells: no customer in it. */
data class DishSales(
    /** When it was for: the slot of a pre-order, else when it came in, as the Auswertung counts its hours. */
    val at: Instant,
    /** Rung up or entered at the counter. */
    val counter: Boolean,
    /** The dishes in it, each once, however many of it. */
    val dishes: Set<Long>,
)

/** The orders the Kasse's bestsellers are worked out from. */
interface DishSalesBackend {
    /** The delivered orders placed from [from] until before [until], tests left out. */
    suspend fun dishSales(from: Instant, until: Instant): List<DishSales>
}

/**
 * Which dishes are tapped first in the Kasse: what sold most, on this day of
 * the week above all.
 *
 * Counted in orders, not in pieces: forty Ayran for one party are one tap
 * more, not forty. An order counts for more
 *   - on the same day of the week ([SAME_WEEKDAY]): a Friday is ranked by
 *     Fridays first, and by the other days where Fridays say too little;
 *   - at the counter ([COUNTER]): what is ordered for delivery is not quite
 *     what is asked for across the counter. Until there are counter sales,
 *     the deliveries decide;
 *   - the newer it is: half as much after [HALF_LIFE_DAYS], so a new dish
 *     gets there and an old favourite leaves.
 *
 * Today's orders are left out: the order of the tiles is the same on every
 * device and does not change until tomorrow.
 */
object Bestsellers {
    /** How far back: eight of every day of the week. */
    const val DAYS = 56L

    /** How many are put on top: whole rows of two, three or four tiles, a screen of the tablet. */
    const val TOP = 12

    /** In fewer orders than this a dish is no bestseller, only sold. */
    const val MIN_ORDERS = 3

    const val SAME_WEEKDAY = 4.0
    const val COUNTER = 3.0
    const val HALF_LIFE_DAYS = 28.0

    /** The dishes' ids, the best seller on [day] first. */
    fun rank(sales: List<DishSales>, day: LocalDate, zone: ZoneId = AlarmPolicy.LEIPZIG): List<Long> {
        val scores = mutableMapOf<Long, Double>()
        val orders = mutableMapOf<Long, Int>()
        for (sale in sales) {
            val on = sale.at.atZone(zone).toLocalDate()
            val age = ChronoUnit.DAYS.between(on, day)
            if (age !in 1..DAYS) continue
            val weight =
                (if (on.dayOfWeek == day.dayOfWeek) SAME_WEEKDAY else 1.0) *
                    (if (sale.counter) COUNTER else 1.0) *
                    0.5.pow(age / HALF_LIFE_DAYS)
            for (dish in sale.dishes) {
                scores[dish] = (scores[dish] ?: 0.0) + weight
                orders[dish] = (orders[dish] ?: 0) + 1
            }
        }
        return scores.entries
            .filter { orders.getValue(it.key) >= MIN_ORDERS }
            .sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    /**
     * The [dishes] with the first [TOP] of the [ranking] that are among them
     * in front, and how many those are. The others keep their places; a sold
     * out one keeps its, so nothing moves when it is.
     */
    fun onTop(dishes: List<MenuDish>, ranking: List<Long>): Arranged {
        val byId = dishes.associateBy { it.id }
        val top = ranking.mapNotNull { byId[it] }.take(TOP)
        val ids = top.map { it.id }.toSet()
        return Arranged(top + dishes.filterNot { it.id in ids }, top.size)
    }

    data class Arranged(val dishes: List<MenuDish>, val top: Int)
}

/**
 * The ranking of the day, read once a day and kept for it: one for the
 * process. Without an answer it is the one known before, or none; it is never
 * a reason for the Kasse not to open.
 */
class BestsellerRanking(
    private val backend: DishSalesBackend,
    private val now: () -> Instant,
    private val zone: ZoneId = AlarmPolicy.LEIPZIG,
    private val logger: Logger = Logger.NONE,
) {
    /** What was last worked out; empty until then. */
    @Volatile
    var known: List<Long> = emptyList()
        private set

    /** The day [known] is for. */
    @Volatile private var day: LocalDate? = null

    private val reading = Mutex()

    suspend fun today(): List<Long> =
        reading.withLock {
            val today = date()
            if (day != today) {
                try {
                    val from = today.minusDays(Bestsellers.DAYS).atStartOfDay(zone).toInstant()
                    val sales = backend.dishSales(from, today.atStartOfDay(zone).toInstant())
                    // Nothing at all is also what an account that may not read orders is told: asked again, not kept.
                    if (sales.isNotEmpty()) {
                        known = Bestsellers.rank(sales, today, zone)
                        day = today
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Reading what sells failed: ${e.javaClass.simpleName}")
                }
            }
            known
        }

    private fun date(): LocalDate = now().atZone(zone).toLocalDate()
}

/** [DishSalesBackend] over PostgREST, as the signed-in account — staff read every order; only these columns of it. */
class SupabaseDishSalesBackend internal constructor(private val rest: SupabaseRest) : DishSalesBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun dishSales(from: Instant, until: Instant): List<DishSales> {
        val sales = mutableListOf<DishSales>()
        for (page in 0 until PAGES) {
            val rows = page(from, until, page * PAGE)
            rows.mapTo(sales) { it.sales() }
            if (rows.size < PAGE) break
        }
        return sales
    }

    private suspend fun page(from: Instant, until: Instant, offset: Int): List<Row> {
        val url =
            rest.endpoint("rest/v1/orders")
                .addQueryParameter("select", COLUMNS)
                .addQueryParameter("status", "eq.delivered")
                .addQueryParameter("is_test", "eq.false")
                .addQueryParameter("created_at", "gte.$from")
                .addQueryParameter("created_at", "lt.$until")
                // The newest first: they count most, should there be more than is read.
                .addQueryParameter("order", "created_at.desc,id.desc")
                .addQueryParameter("limit", PAGE.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw rest.rejected(response.code, body, "orders")
            json.decodeFromString(ListSerializer(Row.serializer()), body)
        }
    }

    @Serializable
    private class Row(
        @SerialName("created_at") @Serializable(InstantSerializer::class) val createdAt: Instant,
        @SerialName("scheduled_for") @Serializable(InstantSerializer::class) val scheduledFor: Instant? = null,
        val source: String = "",
        /** Read by hand: one old line that is not as it should be does not cost the whole ranking. */
        val items: JsonElement = JsonNull,
    ) {
        fun sales() =
            DishSales(
                scheduledFor ?: createdAt,
                source == "counter",
                (items as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull }.toSet(),
            )
    }

    private companion object {
        const val COLUMNS = "created_at,scheduled_for,source,items"

        /** The most rows the server gives at once. */
        const val PAGE = 1000
        const val PAGES = 5
    }
}
