package de.hamzabistro.printstation.core

import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * What a customer said went wrong (20261003201500_order_issues.sql in
 * hamza-bistro-web, #88): Etwas fehlt · Falsches Gericht · Kalt / zu spät ·
 * Anderes.
 */
enum class IssueKind(val wire: String) {
    MISSING("missing"),
    WRONG("wrong"),
    COLD_LATE("cold_late"),
    OTHER("other");

    companion object {
        /** A kind this app does not know yet reads as "Anderes", not as a failure. */
        fun of(wire: String?): IssueKind = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}

/** The order behind a report, as the embedded `orders(...)` reads it. */
@Serializable
data class IssueOrder(
    @SerialName("order_number") val orderNumber: Long = 0,
    @SerialName("customer_name") val customerName: String = "",
    val phone: String = "",
    val address: String = "",
    val pickup: Boolean = false,
    val items: List<OrderItem> = emptyList(),
    val total: Double = 0.0,
    @SerialName("delivered_at") @Serializable(with = InstantSerializer::class) val deliveredAt: Instant? = null,
)

/**
 * One row of public.order_issues, with its order when it was asked for. The
 * comment is cleared with the order's personal data after 30 days.
 */
@Serializable
data class OrderIssue(
    val id: String,
    @SerialName("order_id") val orderId: String = "",
    @SerialName("kind") val kindWire: String = "",
    /** For [IssueKind.MISSING]: positions in the order's items, 0-based. */
    val lines: List<Int> = emptyList(),
    val comment: String = "",
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
    @SerialName("resolved_at") @Serializable(with = InstantSerializer::class) val resolvedAt: Instant? = null,
    /** What staff told the customer when closing it without a voucher. */
    val resolution: String? = null,
    @SerialName("voucher_code") val voucherCode: String? = null,
    @SerialName("voucher_amount") val voucherAmount: Double? = null,
    val orders: IssueOrder? = null,
) {
    val kind: IssueKind
        get() = IssueKind.of(kindWire)
}

/** The open reports as the badge and the chime need them: which, and how many. */
data class OpenIssues(val ids: List<String>, val count: Int)

/** Why issue_voucher() or resolve_issue() refused. */
enum class IssueError {
    /** HB461: somebody else answered it meanwhile. */
    ALREADY_RESOLVED,

    /** P0002: the report is gone. */
    NOT_FOUND,

    /** HB462 from issue_voucher(): outside 0,01–100 €. */
    INVALID_AMOUNT,

    /** HB462 from resolve_issue(): a note over 300 characters. */
    NOTE_TOO_LONG,

    /** HB463: the order's email address is already gone, so no voucher can reach anyone. */
    NO_EMAIL,
}

/** A report could not be answered, for [reason]. */
class IssueActionException(val reason: IssueError, message: String) : Exception(message)

/**
 * The rules of the reports, mirrored from the database and from the site's
 * order-issues.ts, so the app offers only what it will be allowed to send.
 * Money is counted in cents here, so 3 × 2,30 € is 6,90 € and not 6,8999.
 */
object OrderIssues {
    /** The select of the issue's API comment: the report and what staff need of its order. */
    const val COLUMNS =
        "id,order_id,kind,lines,comment,created_at,resolved_at,resolution,voucher_code,voucher_amount," +
            "orders(order_number,customer_name,phone,address,pickup,items,total,delivered_at)"

    /** The fixed amounts offered beside the missing lines' price and another amount, in cents. */
    val VOUCHER_STEPS: List<Long> = listOf(300L, 500L)

    /** issue_voucher()'s range, in cents. */
    const val MIN_CENTS = 1L
    const val MAX_CENTS = 10_000L

    /** resolve_issue()'s limit on the note, after trimming. */
    const val NOTE_MAX = 300

    /** The ticked lines that the order really has, in the order ticked. Empty unless "Etwas fehlt". */
    fun missingLines(issue: OrderIssue): List<OrderItem> {
        if (issue.kind != IssueKind.MISSING) return emptyList()
        val items = issue.orders?.items.orEmpty()
        return issue.lines.mapNotNull { items.getOrNull(it) }
    }

    /** "2× Ayran, 1× Döner". */
    fun linesLabel(issue: OrderIssue): String = missingLines(issue).joinToString(", ") { "${it.qty}× ${it.name}" }

    /**
     * The missing lines' price in cents — the sum of price × qty, Pfand
     * included per unit; the voucher only ever comes off food, so this is a
     * fair upper bound. Null for any other kind, or when it comes to nothing.
     */
    fun missingCents(issue: OrderIssue): Long? =
        missingLines(issue).sumOf { Math.round(it.price * 100) * it.qty }.takeIf { it > 0 }

    /**
     * The amounts offered as buttons, in cents: 3 €, 5 €, and the missing
     * lines' price when there is one that issue_voucher() would take and it
     * is not one of the two already.
     */
    fun voucherChoices(issue: OrderIssue): List<Long> {
        val missing = missingCents(issue)?.takeIf { valid(it) && it !in VOUCHER_STEPS }
        return VOUCHER_STEPS + listOfNotNull(missing)
    }

    fun valid(cents: Long): Boolean = cents in MIN_CENTS..MAX_CENTS

    /**
     * "Anderer Betrag" as typed: "7", "7,50", "7.5", "7,50 €". Cents, or
     * null for anything else and for an amount outside 0,01–100 €. More than
     * two decimals is a typo, not a rounding job.
     */
    fun parseAmount(text: String): Long? {
        val clean = text.replace("€", "").replace(" ", "").replace(' '.toString(), "").replace(',', '.')
        if (!AMOUNT.matches(clean)) return null
        val cents = BigDecimal(clean).movePointRight(2).longValueExact()
        return cents.takeIf(::valid)
    }

    /** The note to send with "Erledigt": trimmed, null when empty. */
    fun note(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    /** Whether resolve_issue() takes [text] as the note. */
    fun noteFits(text: String?): Boolean = (note(text)?.length ?: 0) <= NOTE_MAX

    /** The body of issue_voucher(): the report and the euros, to the cent. */
    fun voucherBody(issueId: String, cents: Long): JsonObject = buildJsonObject {
        put("p_issue_id", issueId)
        put("p_amount", JsonPrimitive(BigDecimal.valueOf(cents, 2)))
    }

    /** The body of resolve_issue(): the report and the note, null for none. */
    fun resolveBody(issueId: String, note: String?): JsonObject = buildJsonObject {
        put("p_issue_id", issueId)
        val said = note(note)
        if (said == null) put("p_resolution", JsonNull) else put("p_resolution", said)
    }

    /** The database's refusal of an answer, in this app's terms; null for any other. */
    fun error(code: String?, voucher: Boolean, message: String): IssueActionException? {
        val reason =
            when (code) {
                "HB461" -> IssueError.ALREADY_RESOLVED
                "P0002" -> IssueError.NOT_FOUND
                "HB462" -> if (voucher) IssueError.INVALID_AMOUNT else IssueError.NOTE_TOO_LONG
                "HB463" -> IssueError.NO_EMAIL
                else -> return null
            }
        return IssueActionException(reason, message)
    }

    /**
     * The total of `Prefer: count=exact` from Content-Range: the 3 of
     * "0-2/3", the 0 of an empty answer's star-slash-0. [fallback] when there
     * is none to read.
     */
    fun count(contentRange: String?, fallback: Int): Int =
        contentRange?.substringAfterLast('/', "")?.toIntOrNull() ?: fallback

    /** The answer of a function that returns one row: an object, or an array of one. */
    internal fun row(body: String): OrderIssue {
        val element = json.parseToJsonElement(body)
        val obj = if (element is JsonArray) element.first() else element
        return json.decodeFromJsonElement(OrderIssue.serializer(), obj)
    }

    private val AMOUNT = Regex("""\d{1,3}(\.\d{1,2})?""")
}

/**
 * The reports, over PostgREST as the signed-in account — RLS lets staff read
 * every one. A database without them yet answers null or nothing, so the
 * app can leave "Reklamationen" out without a word.
 */
interface IssuesBackend {
    /** The open reports, oldest first, with their orders. Null on a database without them. */
    suspend fun open(): List<OrderIssue>?

    /** Which reports are open and how many (`Prefer: count=exact`). Null on a database without them. */
    suspend fun openCount(): OpenIssues?

    /** Which of [orderIds] had a report, open or not. Empty on a database without them. */
    suspend fun reported(orderIds: Collection<String>): Set<String>

    /** "Gutschein senden": the updated row, with the code that was emailed. */
    suspend fun sendVoucher(issueId: String, cents: Long): OrderIssue

    /** "Erledigt", with a note for the customer or none. */
    suspend fun resolve(issueId: String, note: String?): OrderIssue
}

class SupabaseIssuesBackend internal constructor(private val rest: SupabaseRest) : IssuesBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun open(): List<OrderIssue>? {
        val url =
            rest.endpoint("rest/v1/order_issues")
                .addQueryParameter("select", OrderIssues.COLUMNS)
                .addQueryParameter("resolved_at", "is.null")
                .addQueryParameter("order", "created_at.asc")
                .build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "order_issues")
                if (e is BackendException && e.missingTable) return@call null
                throw e
            }
            json.decodeFromString(ListSerializer(OrderIssue.serializer()), body)
        }
    }

    override suspend fun openCount(): OpenIssues? {
        val url =
            rest.endpoint("rest/v1/order_issues")
                .addQueryParameter("select", "id")
                .addQueryParameter("resolved_at", "is.null")
                .addQueryParameter("order", "created_at.asc")
                .build()
        return rest.call({ it.url(url).get().header("Prefer", "count=exact") }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "order_issues")
                if (e is BackendException && e.missingTable) return@call null
                throw e
            }
            val ids = json.decodeFromString(ListSerializer(IdRow.serializer()), body).map { it.id }
            OpenIssues(ids, OrderIssues.count(response.header("Content-Range"), ids.size))
        }
    }

    override suspend fun reported(orderIds: Collection<String>): Set<String> {
        if (orderIds.isEmpty()) return emptySet()
        val url =
            rest.endpoint("rest/v1/order_issues")
                .addQueryParameter("select", "order_id")
                .addQueryParameter("order_id", orderIds.joinToString(",", "in.(", ")"))
                .build()
        return rest.call({ it.url(url).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "order_issues")
                if (e is BackendException && e.missingTable) return@call emptySet()
                throw e
            }
            json.decodeFromString(ListSerializer(OrderIdRow.serializer()), body).map { it.orderId }.toSet()
        }
    }

    override suspend fun sendVoucher(issueId: String, cents: Long): OrderIssue =
        answer("issue_voucher", OrderIssues.voucherBody(issueId, cents), voucher = true)

    override suspend fun resolve(issueId: String, note: String?): OrderIssue =
        answer("resolve_issue", OrderIssues.resolveBody(issueId, note), voucher = false)

    private suspend fun answer(name: String, body: JsonObject, voucher: Boolean): OrderIssue {
        val text =
            try {
                rest.rpc(name, body)
            } catch (e: BackendException) {
                throw OrderIssues.error(e.code, voucher, e.message ?: name) ?: e
            }
        return OrderIssues.row(text)
    }

    @Serializable private class IdRow(val id: String)

    @Serializable private class OrderIdRow(@SerialName("order_id") val orderId: String)
}

/**
 * A table, view or function the database does not have yet: PostgREST's
 * 404 (PGRST205 for a table, PGRST202 for a function), or Postgres's own
 * "undefined table".
 */
internal val BackendException.missingTable: Boolean
    get() = missingFunction || code == "PGRST205" || code == "42P01"

/** What the badge and the chime know of the open reports. */
data class IssuesState(
    /** Null until the database answered once; false on one without reports. */
    val available: Boolean? = null,
    /** The open reports, oldest first. */
    val ids: List<String> = emptyList(),
    val count: Int = 0,
)

/**
 * Keeps the number of open reports in view, for the badge on "Mehr →
 * Reklamationen" and for the chime: Realtime on order_issues says within a
 * second that one came in or was answered elsewhere, and a slow poll
 * underneath catches a socket that died without saying so. A report is
 * never as urgent as a new order, hence the minute.
 *
 * A database without the reports is asked again only now and then, in case
 * it was updated meanwhile.
 */
class IssueWatch(
    private val backend: IssuesBackend,
    private val logger: Logger,
    private val poll: Duration = 60.seconds,
    private val missingPoll: Duration = 15 * 60.seconds,
    private val debounce: Duration = 400.milliseconds,
) {
    private val _state = MutableStateFlow(IssuesState())
    val state: StateFlow<IssuesState> = _state.asStateFlow()

    private val asks = Channel<Unit>(Channel.CONFLATED)

    /** Count again now: after an answer here, or the screen came back. */
    fun refresh() {
        asks.trySend(Unit)
    }

    /** Forgets what was read: another account is about to sign in. */
    fun reset() {
        _state.value = IssuesState()
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
            val open = backend.openCount()
            _state.value =
                if (open == null) IssuesState(available = false) else IssuesState(available = true, ids = open.ids, count = open.count)
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            // What was counted last stays: a badge one off beats none.
            logger.warn("Problem reports could not be counted", e)
        }
    }
}
