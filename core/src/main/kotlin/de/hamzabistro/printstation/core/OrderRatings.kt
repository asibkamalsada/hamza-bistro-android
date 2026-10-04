package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * One row of public.order_ratings (20261003213000_order_ratings.sql in
 * hamza-bistro-web, #89): "Wie war's?", one to five stars and an optional
 * comment, once per delivered or collected order. The comment is cleared
 * with the order's personal data after 30 days; the stars stay.
 */
@Serializable
data class OrderRating(
    val id: String,
    @SerialName("order_id") val orderId: String = "",
    val stars: Int = 0,
    val comment: String = "",
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
)

/**
 * The rules of the ratings as staff see them, mirrored from the site's
 * order-ratings.ts and rating-alarm.ts.
 */
object OrderRatings {
    /** The columns the site reads too: nothing about who. */
    const val COLUMNS = "id,order_id,stars,comment,created_at"

    /** At or below this a rating is bad news: the "Reklamation" colour, and a chime when it says why. */
    const val LOW_STARS = 2

    /** How far back the chime looks: a rating is news for a day at most. */
    val LOOKBACK: Duration = 1.days

    fun isLow(rating: OrderRating): Boolean = rating.stars <= LOW_STARS

    /** Whether staff should hear about it: a bad rating that says why. */
    fun isAlarming(rating: OrderRating): Boolean = isLow(rating) && rating.comment.isNotBlank()

    /** "★★★★☆" — for sighted readers; the label beside it says it in words. */
    fun starsText(stars: Int): String {
        val n = stars.coerceIn(0, 5)
        return "★".repeat(n) + "☆".repeat(5 - n)
    }

    /** The comment to show: trimmed, null when there is none (or it was cleared). */
    fun comment(rating: OrderRating): String? = rating.comment.trim().takeIf { it.isNotEmpty() }

    /** The ratings by their order, for the cards. */
    fun byOrder(ratings: Collection<OrderRating>): Map<String, OrderRating> = ratings.associateBy { it.orderId }

    /** Where the chime's look starts: [LOOKBACK] before [now]. */
    fun since(now: Instant): Instant = now.minus(LOOKBACK.toJavaDuration())

    /** `select … where order_id in (…)`: the ratings on the orders listed. */
    internal fun forOrdersUrl(base: HttpUrl.Builder, orderIds: Collection<String>): HttpUrl =
        base
            .addQueryParameter("select", COLUMNS)
            .addQueryParameter("order_id", orderIds.joinToString(",", "in.(", ")"))
            .build()

    /** `select … where created_at >= since order by created_at desc`: the last day's, for the chime. */
    internal fun sinceUrl(base: HttpUrl.Builder, since: Instant): HttpUrl =
        base
            .addQueryParameter("select", COLUMNS)
            .addQueryParameter("created_at", "gte.$since")
            .addQueryParameter("order", "created_at.desc")
            .build()
}

/**
 * The ratings, over PostgREST as the signed-in account — RLS lets staff read
 * every one. A database without them yet answers empty or null, so the app
 * leaves them out without a word.
 */
interface RatingsBackend {
    /** The ratings on [orderIds], by order id. Empty on a database without them. */
    suspend fun forOrders(orderIds: Collection<String>): Map<String, OrderRating>

    /** The ratings given since [since], newest first. Null on a database without them. */
    suspend fun since(since: Instant): List<OrderRating>?
}

class SupabaseRatingsBackend internal constructor(private val rest: SupabaseRest) : RatingsBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun forOrders(orderIds: Collection<String>): Map<String, OrderRating> {
        if (orderIds.isEmpty()) return emptyMap()
        return read(OrderRatings.forOrdersUrl(rest.endpoint(TABLE), orderIds))?.let(OrderRatings::byOrder).orEmpty()
    }

    override suspend fun since(since: Instant): List<OrderRating>? = read(OrderRatings.sinceUrl(rest.endpoint(TABLE), since))

    private suspend fun read(url: HttpUrl): List<OrderRating>? =
        rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "order_ratings")
                if (e is BackendException && e.missingTable) return@call null
                throw e
            }
            json.decodeFromString(ListSerializer(OrderRating.serializer()), body)
        }

    private companion object {
        const val TABLE = "rest/v1/order_ratings"
    }
}

/** What the chime knows of the last day's ratings. */
data class RatingsState(
    /** Null until the database answered once; false on one without ratings. */
    val available: Boolean? = null,
    /** The last day's ratings, newest first. */
    val recent: List<OrderRating> = emptyList(),
)

/**
 * Keeps the last day's ratings in view for "Schlechte Bewertung": Realtime
 * on order_ratings says within a second that one came in, and a slow poll
 * underneath catches a socket that died without saying so — as [IssueWatch]
 * does for the reports.
 */
class RatingWatch(
    private val backend: RatingsBackend,
    private val logger: Logger,
    private val now: () -> Instant = Instant::now,
    private val poll: Duration = 2.minutes,
    private val missingPoll: Duration = 15.minutes,
    private val debounce: Duration = 400.milliseconds,
) {
    private val _state = MutableStateFlow(RatingsState())
    val state: StateFlow<RatingsState> = _state.asStateFlow()

    private val asks = Channel<Unit>(Channel.CONFLATED)

    /** Forgets what was read: another account is about to sign in. */
    fun reset() {
        _state.value = RatingsState()
    }

    @OptIn(FlowPreview::class)
    suspend fun run(changes: Flow<Unit>): Nothing = coroutineScope {
        asks.trySend(Unit)
        launch { changes.debounce(debounce).collect { asks.send(Unit) } }
        launch {
            while (true) {
                delay(if (_state.value.available == false) missingPoll else poll)
                asks.send(Unit)
            }
        }
        for (ask in asks) look()
        error("the look channel never closes")
    }

    private suspend fun look() {
        try {
            val recent = backend.since(OrderRatings.since(now()))
            _state.value = if (recent == null) RatingsState(available = false) else RatingsState(available = true, recent = recent)
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            // What was read last stays: the chime only cares about what is new.
            logger.warn("Ratings could not be read", e)
        }
    }
}
