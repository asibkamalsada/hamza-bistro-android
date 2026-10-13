package de.hamzabistro.printstation.core

import java.time.Instant
import kotlin.time.Duration
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** A call the shop's phone reported: the number as the phone gave it, and when it rang. */
@Serializable
data class IncomingCall(
    val id: String,
    val phone: String,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
)

/** Where a number had a delivery before, with the door note of the latest one. */
@Serializable
data class RecentAddress(
    val street: String,
    @SerialName("postal_code") val postalCode: String,
    val city: String,
    @SerialName("address_note") val addressNote: String = "",
) {
    /** "Karl-Heine-Straße 9, 04229 Leipzig". */
    val line: String
        get() = "$street, $postalCode $city"
}

/**
 * The caller's number, from the shop's phone to the tablet that takes the
 * orders (hamza-bistro-web: 20261013120000_incoming_calls). The phone files
 * the number while it rings; the tablet shows it for a few minutes with
 * "Neue Bestellung" beside it, so nobody copies a number off one screen
 * into another with a caller waiting.
 */
object IncomingCalls {
    /**
     * How long a call stays on the tablet: long enough to say hello and
     * reach for it, not so long that it is the caller before last.
     */
    val SHOWN_FOR: Duration = 3.minutes

    /**
     * The calls to show at [now]: those of the last [SHOWN_FOR], the latest
     * first, each number once — somebody who rings twice is one caller —
     * and none whose latest call is among [dismissed]. Ringing again after
     * that shows again.
     */
    fun toShow(calls: List<IncomingCall>, dismissed: Collection<String>, now: Instant): List<IncomingCall> {
        val from = now.minus(SHOWN_FOR.toJavaDuration())
        return calls
            .filter { it.createdAt.isAfter(from) }
            .sortedByDescending { it.createdAt }
            .distinctBy { PhoneNumbers.normalise(it.phone) }
            .filter { it.id !in dismissed }
    }

    /** `select … where created_at >= since order by created_at desc`. */
    internal fun sinceUrl(base: HttpUrl.Builder, since: Instant): HttpUrl =
        base
            .addQueryParameter("select", "id,phone,created_at")
            .addQueryParameter("created_at", "gte.$since")
            .addQueryParameter("order", "created_at.desc")
            .build()
}

/**
 * The calls, over PostgREST as the signed-in account: staff file and read
 * them. A database without them yet answers null or empty, so the app
 * leaves them out without a word.
 */
interface IncomingCallsBackend {
    /** Files a call from [phone]: on the shop's phone, while it rings. */
    suspend fun report(phone: String)

    /** The calls since [since], the latest first. Null on a database without them. */
    suspend fun since(since: Instant): List<IncomingCall>?

    /**
     * Where [phone] had deliveries in the last 30 days, the latest first,
     * three at most. Empty on a database without the lookup.
     */
    suspend fun recentAddresses(phone: String): List<RecentAddress>
}

class SupabaseIncomingCallsBackend internal constructor(private val rest: SupabaseRest) : IncomingCallsBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun report(phone: String) {
        val body = buildJsonObject { put("phone", phone) }
        rest.call({ it.url(rest.endpoint(TABLE).build()).post(body.toRequestBody()) }) { response ->
            if (!response.isSuccessful) throw rest.rejected(response.code, response.body.string(), "incoming_calls")
        }
    }

    override suspend fun since(since: Instant): List<IncomingCall>? =
        rest.call({ it.url(IncomingCalls.sinceUrl(rest.endpoint(TABLE), since)).get() }) { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val e = rest.rejected(response.code, body, "incoming_calls")
                if (e is BackendException && e.missingTable) return@call null
                throw e
            }
            json.decodeFromString(ListSerializer(IncomingCall.serializer()), body)
        }

    override suspend fun recentAddresses(phone: String): List<RecentAddress> {
        val answer =
            try {
                rest.rpc("staff_recent_addresses", buildJsonObject { put("p_phone", phone) })
            } catch (e: BackendException) {
                if (e.missingFunction) return emptyList()
                throw e
            }
        return if (answer.isBlank()) emptyList() else json.decodeFromString(ListSerializer(RecentAddress.serializer()), answer)
    }

    private companion object {
        const val TABLE = "rest/v1/incoming_calls"
    }
}

/** What the tablet knows of the last minutes' calls. */
data class CallsState(
    /** Null until the database answered once; false on one without calls. */
    val available: Boolean? = null,
    /** The last minutes' calls, the latest first. */
    val calls: List<IncomingCall> = emptyList(),
)

/**
 * Keeps the last minutes' calls in view: Realtime on incoming_calls says
 * within a second that the phone rang, and a poll underneath catches a
 * socket that died without saying so — as [RatingWatch] does for the
 * ratings, only quicker: a call found two minutes late is found too late.
 */
class CallWatch(
    private val backend: IncomingCallsBackend,
    private val logger: Logger,
    private val now: () -> Instant = Instant::now,
    private val poll: Duration = 20.seconds,
    private val missingPoll: Duration = 15.minutes,
    private val debounce: Duration = 400.milliseconds,
) {
    private val _state = MutableStateFlow(CallsState())
    val state: StateFlow<CallsState> = _state.asStateFlow()

    private val asks = Channel<Unit>(Channel.CONFLATED)

    /** Forgets what was read: another account is about to sign in. */
    fun reset() {
        _state.value = CallsState()
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
            // A little further back than is shown: this device's clock and the database's need not agree to the second.
            val recent = backend.since(now().minus((IncomingCalls.SHOWN_FOR + SLACK).toJavaDuration()))
            _state.value = if (recent == null) CallsState(available = false) else CallsState(available = true, calls = recent)
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            // What was read last stays: a call still shown beats a blank.
            logger.warn("The calls could not be read", e)
        }
    }

    private companion object {
        val SLACK = 1.minutes
    }
}
