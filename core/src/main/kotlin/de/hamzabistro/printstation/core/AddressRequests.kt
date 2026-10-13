package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * A link texted to a caller to type their delivery address into, and the
 * phone order waiting for it (hamza-bistro-web:
 * 20261013170000_address_requests). Read as staff see the row.
 */
@Serializable
data class AddressRequest(
    val id: String,
    val token: String,
    val phone: String,
    @SerialName("customer_name") val customerName: String = "",
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("sms_claimed_at") @Serializable(with = InstantSerializer::class) val smsClaimedAt: Instant? = null,
    @SerialName("sms_sent_at") @Serializable(with = InstantSerializer::class) val smsSentAt: Instant? = null,
    @SerialName("sms_error") val smsError: String? = null,
    val street: String? = null,
    @SerialName("postal_code") val postalCode: String? = null,
    val city: String? = null,
    @SerialName("address_note") val addressNote: String? = null,
    /** When the customer typed the address; null until they have. */
    @SerialName("address_at") @Serializable(with = InstantSerializer::class) val addressAt: Instant? = null,
    /** The basket, as [AddressRequests.draft] wrote it. */
    val draft: JsonObject? = null,
    @SerialName("saved_at") @Serializable(with = InstantSerializer::class) val savedAt: Instant? = null,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("order_number") val orderNumber: Long? = null,
    @SerialName("placed_at") @Serializable(with = InstantSerializer::class) val placedAt: Instant? = null,
    /** Why the order could not be placed with the address typed, in words or by its code. */
    val problem: String? = null,
    @SerialName("cancelled_at") @Serializable(with = InstantSerializer::class) val cancelledAt: Instant? = null,
) {
    /** The address the customer typed, once they have. */
    val address: RecentAddress?
        get() {
            if (addressAt == null || street.isNullOrBlank() || postalCode.isNullOrBlank()) return null
            return RecentAddress(street, postalCode, city.orEmpty(), addressNote.orEmpty())
        }

    val placed: Boolean
        get() = placedAt != null

    val cancelled: Boolean
        get() = cancelledAt != null

    /** The basket is saved and waits for the address, or for somebody to sort out [problem]. */
    val waiting: Boolean
        get() = savedAt != null && !placed && !cancelled

    val sms: SmsState
        get() =
            when {
                smsSentAt != null -> SmsState.SENT
                smsError != null -> SmsState.FAILED
                else -> SmsState.SENDING
            }

    /** The dishes saved with it, for the card; empty before the basket is saved. */
    val lines: List<OrderItem>
        get() =
            draft?.get("lines")?.let { runCatching { json.decodeFromJsonElement(ListSerializer(OrderItem.serializer()), it) }.getOrNull() }
                .orEmpty()

    /** The menu prices of [lines], as the tablet showed them. */
    val subtotal: Double
        get() = lines.sumOf { it.price * it.qty }
}

/** How the text with the link fares. */
enum class SmsState {
    /** Not sent yet: waiting for whatever sends the texts, or being sent. */
    SENDING,
    SENT,
    FAILED,
}

/** What the database answers for a new link: the request, its token, and whether an open one was taken again. */
@Serializable
data class RequestedLink(val id: String, val token: String, val reused: Boolean = false)

/** A text this device is to send. */
@Serializable
data class SmsJob(val phone: String, val token: String)

/** Why a request could not be placed, by the database's codes. */
enum class AddressRequestError(val code: String) {
    /** HB484: unknown, cancelled, or past its three hours. */
    GONE("HB484"),

    /** HB485: the customer has not typed an address yet. */
    NO_ADDRESS("HB485"),

    /** HB486: no basket saved. */
    NO_BASKET("HB486"),

    /** HB488: no number to text. */
    NO_NUMBER("HB488");

    companion object {
        fun of(code: String?): AddressRequestError? = entries.firstOrNull { it.code == code }
    }
}

class AddressRequestException(val reason: AddressRequestError, message: String) : Exception(message)

/** The link, the text and what of the basket is stored: the rules the tablet and the database share. */
object AddressRequests {
    /** The server update that brought the links. */
    const val MIGRATION = "20261013170000_address_requests"

    /** Where the customer's page is: SITE_ORIGIN in the site's src/app/site.ts. */
    const val SITE = "https://www.hamzabistro.de"

    /** How long a link takes an address, as address_request_submit has it. */
    val OPEN_FOR: Duration = 3.hours

    /** A text older than this is not sent any more, as claim_address_sms has it. */
    val SEND_WITHIN: Duration = 15.minutes

    /** A claim nobody answered for this long may be taken again, as claim_address_sms has it. */
    val CLAIM_FOR: Duration = 2.minutes

    /** How long a text may wait to be sent before the tablet says that nothing sends it. */
    val NO_SENDER_AFTER: Duration = 45.seconds

    /** How far back the tablet reads: as long as the database keeps a request. */
    val KEPT_FOR: Duration = 1.days

    fun link(token: String): String = "$SITE/a/$token"

    /**
     * The text. German, as the shop speaks to its callers; short enough for
     * one SMS of 160 characters with the link, and nothing in it outside
     * the SMS alphabet, so it is not sent as three.
     */
    fun smsText(token: String): String = "Hamza Bistro: Bitte gib hier deine Lieferadresse ein: ${link(token)}"

    /**
     * What is saved of [draft] to wait for the address: staff_place_order's
     * own arguments for everything but the address and the minutes, which
     * come when it is placed — and the lines as the order will carry them,
     * for the card and the cooking time.
     */
    fun draft(draft: PhoneOrderDraft): JsonObject {
        val body = PhoneOrders.body(draft.copy(fulfilment = Fulfilment.DELIVERY), etaMinutes = null, expectedTotal = null, dryRun = true)
        return buildJsonObject {
            for (key in listOf("p_items", "p_customer_name", "p_phone", "p_notes", "p_waive_fees")) put(key, body[key] ?: JsonNull)
            put("lines", buildJsonArray { draft.lines.forEach { add(json.encodeToJsonElement(OrderItem.serializer(), it.asItem)) } })
        }
    }

    /** The baskets waiting for an address or a person, the latest first. */
    fun waiting(requests: List<AddressRequest>): List<AddressRequest> = requests.filter { it.waiting }.sortedByDescending { it.createdAt }

    /**
     * What [me] should place now: its own requests with a basket and an
     * address, not placed, and no problem waiting for a person. The
     * database makes one order of each however often it is asked.
     */
    fun toPlace(requests: List<AddressRequest>, me: String?): List<AddressRequest> =
        requests.filter { it.waiting && it.address != null && it.problem == null && me != null && it.createdBy == me }

    /** The texts still to send at [now]: not sent, not failed, not being sent, not too old, still wanted. */
    fun toText(requests: List<AddressRequest>, now: Instant): List<AddressRequest> =
        requests.filter {
            it.smsSentAt == null && it.smsError == null && !it.placed && !it.cancelled &&
                it.createdAt.isAfter(now.minus(SEND_WITHIN.toJavaDuration())) &&
                (it.smsClaimedAt == null || !it.smsClaimedAt.isAfter(now.minus(CLAIM_FOR.toJavaDuration())))
        }

    /** Nobody has sent the text, nor said they could not, for [NO_SENDER_AFTER]: nothing is sending texts. */
    fun unsent(request: AddressRequest, now: Instant): Boolean =
        request.sms == SmsState.SENDING && request.smsClaimedAt == null && !request.createdAt.isAfter(now.minus(NO_SENDER_AFTER.toJavaDuration()))

    /** `select=* where created_at >= since order by created_at desc`. */
    internal fun sinceUrl(base: HttpUrl.Builder, since: Instant): HttpUrl =
        base
            .addQueryParameter("select", "*")
            .addQueryParameter("created_at", "gte.$since")
            .addQueryParameter("order", "created_at.desc")
            .build()
}

/** The links, over PostgREST as the signed-in staff account. */
interface AddressRequestBackend {
    /** A link for [phone]: the open one for that caller if there is one, its text sent once more. */
    suspend fun request(phone: String, name: String): RequestedLink

    /** "Nochmal senden". */
    suspend fun resend(id: String)

    /** The requests since [since], the latest first. Null on a database without them. */
    suspend fun since(since: Instant): List<AddressRequest>?

    /** One text for this device to send, or null when it is not this device's to send. */
    suspend fun claimSms(id: String): SmsJob?

    /** The text went out ([error] null), or why not. */
    suspend fun smsResult(id: String, error: String?)

    /** The basket, waiting for the address. */
    suspend fun save(id: String, draft: PhoneOrderDraft)

    /**
     * staff_place_order with the basket saved and the address typed. Throws
     * [PhoneOrderException] for its refusals and [AddressRequestException]
     * for the request's own.
     */
    suspend fun place(id: String, etaMinutes: Int?, expectedTotal: Double?, dryRun: Boolean): PhoneOrderQuote

    /** Why it could not be placed; null clears it. */
    suspend fun noteProblem(id: String, problem: String?)

    /** Saved the usual way, as order [orderId]: the link is done. */
    suspend fun close(id: String, orderId: String)

    suspend fun cancel(id: String)
}

class SupabaseAddressRequestBackend internal constructor(private val rest: SupabaseRest) : AddressRequestBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun request(phone: String, name: String): RequestedLink =
        json.decodeFromString(
            RequestedLink.serializer(),
            rpc("staff_request_address") {
                put("p_phone", phone.trim())
                put("p_name", name.trim())
            },
        )

    override suspend fun resend(id: String) {
        rpc("staff_resend_address_sms") { put("p_id", id) }
    }

    override suspend fun since(since: Instant): List<AddressRequest>? =
        rest.call({ it.url(AddressRequests.sinceUrl(rest.endpoint(TABLE), since)).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "address_requests")
                if (e is BackendException && e.missingTable) return@call null
                throw e
            }
            json.decodeFromString(ListSerializer(AddressRequest.serializer()), body)
        }

    override suspend fun claimSms(id: String): SmsJob? {
        val answer = rpc("claim_address_sms") { put("p_id", id) }.trim()
        return if (answer.isEmpty() || answer == "null") null else json.decodeFromString(SmsJob.serializer(), answer)
    }

    override suspend fun smsResult(id: String, error: String?) {
        rpc("address_sms_result") {
            put("p_id", id)
            put("p_error", error)
        }
    }

    override suspend fun save(id: String, draft: PhoneOrderDraft) {
        rpc("staff_save_address_request") {
            put("p_id", id)
            put("p_draft", AddressRequests.draft(draft))
        }
    }

    override suspend fun place(id: String, etaMinutes: Int?, expectedTotal: Double?, dryRun: Boolean): PhoneOrderQuote {
        val body =
            try {
                rpc("staff_place_address_request") {
                    put("p_id", id)
                    put("p_eta_minutes", etaMinutes)
                    put("p_expected_total", expectedTotal)
                    put("p_dry_run", dryRun)
                }
            } catch (e: Exception) {
                // staff_place_order's own refusals, read as for any phone order.
                throw PhoneOrders.error(e) ?: e
            }
        return PhoneOrders.quote(body)
    }

    override suspend fun noteProblem(id: String, problem: String?) {
        rpc("staff_note_address_problem") {
            put("p_id", id)
            put("p_problem", problem)
        }
    }

    override suspend fun close(id: String, orderId: String) {
        rpc("staff_close_address_request") {
            put("p_id", id)
            put("p_order", orderId)
        }
    }

    override suspend fun cancel(id: String) {
        rpc("staff_cancel_address_request") { put("p_id", id) }
    }

    /** A function of the update; a database without it is told to update. */
    private suspend fun rpc(name: String, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        try {
            rest.rpc(name, buildJsonObject(args))
        } catch (e: BackendException) {
            if (e.missingFunction) throw NeedsServerUpdateException(AddressRequests.MIGRATION)
            if (e.code == "42501") throw NotAllowedException()
            AddressRequestError.of(e.code)?.let { throw AddressRequestException(it, e.message ?: it.code) }
            throw e
        }

    private companion object {
        const val TABLE = "rest/v1/address_requests"
    }
}

/** What the tablet knows of the links. */
data class AddressRequestsState(
    /** Null until the database answered once; false on one without links. */
    val available: Boolean? = null,
    /** The last day's requests, the latest first. */
    val requests: List<AddressRequest> = emptyList(),
) {
    fun byId(id: String?): AddressRequest? = requests.firstOrNull { it.id == id }
}

/**
 * Keeps the last day's links in view, as [CallWatch] does the calls:
 * Realtime says within a second that an address arrived or a text was
 * sent, and a poll underneath catches a socket that died without saying so.
 */
class AddressRequestWatch(
    private val backend: AddressRequestBackend,
    private val logger: Logger,
    private val now: () -> Instant = Instant::now,
    private val poll: Duration = 15.seconds,
    private val missingPoll: Duration = 15.minutes,
    private val debounce: Duration = 300.milliseconds,
) {
    private val _state = MutableStateFlow(AddressRequestsState())
    val state: StateFlow<AddressRequestsState> = _state.asStateFlow()

    private val asks = Channel<Unit>(Channel.CONFLATED)

    /** Forgets what was read: another account is about to sign in. */
    fun reset() {
        _state.value = AddressRequestsState()
    }

    /** Reads again at once: after this device changed something. */
    fun refresh() {
        asks.trySend(Unit)
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
            val read = backend.since(now().minus(AddressRequests.KEPT_FOR.toJavaDuration()))
            _state.value = if (read == null) AddressRequestsState(available = false) else AddressRequestsState(available = true, requests = read)
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            // What was read last stays: a waiting basket still shown beats a blank.
            logger.warn("The address links could not be read", e)
        }
    }
}

/**
 * Puts a waiting basket into the kitchen once its caller has typed the
 * address: on the device that took the order, as its staff account — the
 * address checked with check-address, priced with a dry run, saved with
 * that price — exactly as an address typed on the tablet would be. What
 * the database refuses (outside the area, under the last ring's minimum, a
 * dish sold out since) is noted on the request for a person to sort out;
 * anything else is tried again on the next look.
 */
class AddressRequestPlacer(
    private val requests: AddressRequestBackend,
    private val orders: PhoneOrderBackend,
    private val logger: Logger,
    /** The minutes to promise for [items] to [postalCode] in [zone]: the incoming order's rule. */
    private val minutes: (items: List<OrderItem>, postalCode: String, zone: String?) -> Int,
    private val now: () -> Instant = Instant::now,
    private val retryAfter: Duration = 30.seconds,
) {
    /** When each request last failed for a reason worth retrying: not before [retryAfter]. */
    private val failedAt = mutableMapOf<String, Instant>()

    /** Placed here, and not read back as placed yet. */
    private val done = mutableSetOf<String>()

    /**
     * Places what [state] holds for [me], one at a time: each time it
     * changes, and every [retryAfter] for what failed for a reason worth
     * trying again.
     */
    suspend fun run(state: StateFlow<AddressRequestsState>, me: () -> String?): Nothing = coroutineScope {
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch { state.collect { wake.send(Unit) } }
        launch {
            while (true) {
                delay(retryAfter)
                wake.send(Unit)
            }
        }
        for (ignored in wake) placeAll(state.value, me())
        error("the wake channel never closes")
    }

    internal suspend fun placeAll(read: AddressRequestsState, me: String?) {
        done.retainAll(read.requests.filterNot { it.placed }.map { it.id }.toSet())
        for (request in AddressRequests.toPlace(read.requests, me)) {
            if (request.id in done) continue
            val last = failedAt[request.id]
            if (last != null && now().isBefore(last.plus(retryAfter.toJavaDuration()))) continue
            place(request)
        }
    }

    /** True once it is in the kitchen (by this device or another). */
    internal suspend fun place(request: AddressRequest): Boolean {
        val address = request.address ?: return false
        try {
            val check = orders.checkAddress(address.street, address.postalCode, address.city)
            if (check.outside) {
                requests.noteProblem(request.id, PhoneOrderError.OUTSIDE.code)
                return false
            }
            val eta = minutes(request.lines, address.postalCode, check.zone)
            val quote = requests.place(request.id, eta, null, dryRun = true)
            if (quote.already) {
                done += request.id
                return true
            }
            val placed =
                try {
                    requests.place(request.id, eta, quote.total, dryRun = false)
                } catch (e: PhoneOrderException) {
                    // The price moved between the two: once more at the new one.
                    if (e.reason != PhoneOrderError.TOTAL_CHANGED) throw e
                    val again = requests.place(request.id, eta, null, dryRun = true)
                    requests.place(request.id, eta, again.total, dryRun = false)
                }
            failedAt.remove(request.id)
            done += request.id
            logger.info("Address link ${request.id} placed as #${placed.orderNumber}")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignedOutException) {
            throw e
        } catch (e: PhoneOrderException) {
            // For a person: the database said no to this address or basket.
            noteQuietly(request.id, e.reason.code)
        } catch (e: AddressRequestException) {
            // Cancelled meanwhile, or not ready after all: the next look knows.
            logger.warn("Address link ${request.id} not placed: ${e.reason}")
        } catch (e: Exception) {
            failedAt[request.id] = now()
            logger.warn("Address link ${request.id} not placed, trying again", e)
        }
        return false
    }

    private suspend fun noteQuietly(id: String, problem: String) {
        try {
            requests.noteProblem(id, problem)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[id] = now()
            logger.warn("The problem with address link $id could not be noted", e)
        }
    }
}

/**
 * How a text with the address link goes out: a gateway, a phone with a SIM,
 * whatever is chosen — not decided yet. Answers null when the text went
 * out, or why not.
 */
fun interface SmsTransport {
    suspend fun send(phone: String, text: String): String?
}

/**
 * Wherever an [SmsTransport] runs: takes each text still to send (one
 * device claims it, so it goes once), hands it to the transport, and says
 * how it went — the database's side of it is claim_address_sms and
 * address_sms_result, whichever transport it is.
 */
class AddressSmsSender(
    private val backend: AddressRequestBackend,
    private val transport: SmsTransport,
    private val logger: Logger,
    private val now: () -> Instant = Instant::now,
) {
    /** Sends what [state] holds each time it changes, and every [tick] for a claim that ran out. */
    suspend fun run(state: StateFlow<AddressRequestsState>, tick: Duration = 30.seconds): Nothing = coroutineScope {
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch { state.collect { wake.send(Unit) } }
        launch {
            while (true) {
                delay(tick)
                wake.send(Unit)
            }
        }
        for (ignored in wake) sendAll(state.value)
        error("the wake channel never closes")
    }

    internal suspend fun sendAll(read: AddressRequestsState) {
        for (request in AddressRequests.toText(read.requests, now())) {
            try {
                val job = backend.claimSms(request.id) ?: continue
                val error =
                    try {
                        transport.send(job.phone, AddressRequests.smsText(job.token))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                backend.smsResult(request.id, error)
                if (error != null) logger.warn("The text for address link ${request.id} failed: $error")
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignedOutException) {
                throw e
            } catch (e: Exception) {
                // Claimed and not answered: taken again after the claim runs out.
                logger.warn("The text for address link ${request.id} could not be handled", e)
            }
        }
    }
}
