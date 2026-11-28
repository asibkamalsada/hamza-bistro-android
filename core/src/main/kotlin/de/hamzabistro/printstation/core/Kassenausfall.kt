package de.hamzabistro.printstation.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

// The Kassenausfall (hamza-bistro-web: 20261128140000_kassenausfall): when
// the till — the database — cannot be reached. This app does not sell
// without a connection, by design: on its own a device would be a till of
// its own (BMF FAQ). So staff first try a phone's hotspot; if the till still
// cannot be reached they write numbered paper Notbelege and an outage note
// (Verfahrensdokumentation 2.8). When it is back — the same day, before the
// count (the owner, 28 November 2026; any staff account) — the note is
// reported here and each Notbeleg entered as a counter sale at the time on
// the paper, marked and signed late. The database decides everything; this
// mirrors its rules so the screen offers only what it will take.

/** A Notbeleg entered, as the outage note lists it. */
@Serializable
data class LateSlip(
    val slip: Int,
    /** The sale's Bon number. */
    val number: Long,
    /** The time on the paper. */
    @Serializable(with = InstantSerializer::class) val at: Instant? = null,
    @SerialName("entered_at") @Serializable(with = InstantSerializer::class) val enteredAt: Instant? = null,
    val total: Double = 0.0,
    @SerialName("payment_method") val paymentMethod: String? = null,
    val by: String = "",
)

/** One outage note, as till_outages() and cash_up().outages give it. */
@Serializable
data class TillOutage(
    /** The id the app made when the form opened. */
    val id: String,
    val number: Long = 0,
    val day: String = "",
    @SerialName("started_at") @Serializable(with = InstantSerializer::class) val startedAt: Instant? = null,
    @SerialName("ended_at") @Serializable(with = InstantSerializer::class) val endedAt: Instant? = null,
    val cause: String = "",
    val note: String? = null,
    @SerialName("slips_first") val slipsFirst: Int? = null,
    @SerialName("slips_last") val slipsLast: Int? = null,
    val void: List<Int> = emptyList(),
    val entered: List<LateSlip> = emptyList(),
    /** Neither entered nor void: the count waits for these (HB518). */
    val missing: List<Int> = emptyList(),
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_on") val createdOn: String? = null,
    val corrections: Int = 0,
) {
    /** "1–14", or "" for a note without Notbelege. */
    val range: String
        get() =
            when {
                slipsFirst == null || slipsLast == null -> ""
                slipsFirst == slipsLast -> "$slipsFirst"
                else -> "$slipsFirst–$slipsLast"
            }
}

/** What the outage form holds, as typed. */
data class OutageDraft(
    /** "HH:MM", Leipzig, on the day. */
    val from: String = "",
    val to: String = "",
    val cause: String = "",
    val slipsFirst: String = "",
    val slipsLast: String = "",
    /** "3, 7". */
    val void: String = "",
    val note: String = "",
)

/** What till_outage_report() is sent. */
data class OutageRequest(
    val startedAt: Instant,
    val endedAt: Instant,
    val cause: String,
    val slipsFirst: Int?,
    val slipsLast: Int?,
    val void: List<Int>,
    val note: String?,
)

/** Why the till refused, by its error code. */
enum class OutageError(val code: String) {
    /** 42501: the account is not staff (any more). */
    NOT_STAFF("42501"),

    /** HB513: not an outage note (times, cause, Notbelege, note). */
    BAD_NOTE("HB513"),

    /** HB514: a correction of another day, or one that drops an entered Notbeleg. */
    CORRECTION("HB514"),

    /** HB515: another note has this time or these numbers. */
    OVERLAP("HB515"),

    /** HB516: not a Notbeleg of this note (number, time). */
    NOT_A_SLIP("HB516"),

    /** HB517: this Notbeleg is entered already. */
    TWICE("HB517"),

    /** HB409: the till prices these dishes otherwise than the paper says. */
    AMOUNT("HB409"),

    /** HB493: this id is another sale. */
    KEY_REUSED("HB493"),

    /** HB490: signing is off, the till takes no sales. */
    SIGNING_OFF("HB490");

    companion object {
        fun of(code: String?): OutageError? = entries.firstOrNull { it.code == code }
    }
}

/** The till refused, for [reason]. */
class OutageException(val reason: OutageError, message: String) : Exception(message)

/** The rules the database keeps, mirrored. */
object Kassenausfall {
    /** The server update that brought the Kassenausfall. */
    const val MIGRATION = "20261128140000_kassenausfall"

    /** Mirrors till_outage_report(): the cause goes into TSE_TA_FEHLER. */
    const val CAUSE_MAX = 80

    const val NOTE_MAX = 500

    /** One note covers fewer than this many Notbelege. */
    const val SLIPS_MAX = 1000

    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    private val TIME = Regex("""^(\d{1,2})[:.](\d{2})$""")
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** "12:05" on [day] in Leipzig; null for anything that is not a time. */
    fun instant(day: LocalDate, time: String): Instant? {
        val m = TIME.matchEntire(time.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        return day.atTime(LocalTime.of(h, min)).atZone(ZONE).toInstant()
    }

    /** "12:05" of an instant on a Leipzig clock. */
    fun clock(at: Instant): String = CLOCK.format(at.atZone(ZONE))

    /** Today on a Leipzig calendar. */
    fun today(now: Instant = Instant.now()): LocalDate = now.atZone(ZONE).toLocalDate()

    private fun count(text: String): Int? = text.trim().takeIf { Regex("""^\d{1,6}$""").matches(it) }?.toInt()

    /** "7, 3 3;9" → [3, 7, 9]; [] for nothing; null for anything else. */
    fun parseSlips(text: String): List<Int>? {
        val s = text.trim()
        if (s.isEmpty()) return emptyList()
        val parts = s.split(Regex("""[\s,;]+""")).filter { it.isNotEmpty() }
        if (!parts.all { Regex("""^\d{1,6}$""").matches(it) }) return null
        return parts.map { it.toInt() }.distinct().sorted()
    }

    /** What the form sends, or null when the till would refuse it (HB513 mirrored). */
    fun request(draft: OutageDraft, day: LocalDate, now: Instant = Instant.now()): OutageRequest? {
        val started = instant(day, draft.from) ?: return null
        val ended = instant(day, draft.to) ?: return null
        if (!started.isBefore(ended)) return null
        if (ended.isAfter(now.plusSeconds(60))) return null
        val cause = draft.cause.trim()
        if (cause.isEmpty() || cause.length > CAUSE_MAX) return null
        val note = draft.note.trim()
        if (note.length > NOTE_MAX) return null
        val voided = parseSlips(draft.void) ?: return null
        val noRange = draft.slipsFirst.isBlank() && draft.slipsLast.isBlank()
        var first: Int? = null
        var last: Int? = null
        if (noRange) {
            if (voided.isNotEmpty()) return null
        } else {
            first = count(draft.slipsFirst) ?: return null
            last = count(draft.slipsLast) ?: return null
            if (first < 1 || last < first || last - first >= SLIPS_MAX) return null
            if (voided.any { it < first || it > last }) return null
        }
        return OutageRequest(started, ended, cause, first, last, voided, note.ifEmpty { null })
    }

    /** A note's form filled with what it says, for a correction. */
    fun draftOf(o: TillOutage): OutageDraft =
        OutageDraft(
            from = o.startedAt?.let(::clock) ?: "",
            to = o.endedAt?.let(::clock) ?: "",
            cause = o.cause,
            slipsFirst = o.slipsFirst?.toString() ?: "",
            slipsLast = o.slipsLast?.toString() ?: "",
            void = o.void.joinToString(", "),
            note = o.note ?: "",
        )

    /**
     * A Notbeleg's time "HH:MM" on [day], if it lies inside the outage and not
     * after now (HB516 mirrored); the note's start when the paper's minute is
     * the minute the outage began.
     */
    fun slipTime(o: TillOutage, day: LocalDate, time: String, now: Instant = Instant.now()): Instant? {
        val at = instant(day, time) ?: return null
        val started = o.startedAt ?: return null
        val ended = o.endedAt ?: return null
        if (at.isAfter(now)) return null
        val minute = Instant.ofEpochSecond(started.epochSecond / 60 * 60)
        if (at.isBefore(minute) || at.isAfter(ended)) return null
        return if (at.isBefore(started)) started else at
    }

    /** Every Notbeleg still to be entered across the day's notes. */
    fun missing(outages: List<TillOutage>?): List<Int> = outages.orEmpty().flatMap { it.missing }.sorted()

    fun reportBody(id: String, req: OutageRequest, device: CounterDevice): JsonObject =
        buildJsonObject {
            put("p_id", id)
            put("p_started_at", req.startedAt.toString())
            put("p_ended_at", req.endedAt.toString())
            put("p_cause", req.cause)
            if (req.slipsFirst == null) put("p_slips_first", JsonNull) else put("p_slips_first", req.slipsFirst)
            if (req.slipsLast == null) put("p_slips_last", JsonNull) else put("p_slips_last", req.slipsLast)
            put("p_void", JsonArray(req.void.map { JsonPrimitive(it) }))
            if (req.note == null) put("p_note", JsonNull) else put("p_note", req.note)
            put("p_device", device.toJson())
        }

    fun lateBody(
        id: String,
        outage: String,
        slip: Int,
        at: Instant,
        dineIn: Boolean,
        lines: List<BasketLine>,
        method: CounterPayment,
        paper: Double,
        device: CounterDevice,
        cardTerminal: Int?,
    ): JsonObject =
        buildJsonObject {
            put("p_id", id)
            put("p_outage", outage)
            put("p_slip", slip)
            put("p_at", at.toString())
            put("p_fulfilment", if (dineIn) "dine_in" else "pickup")
            put("p_items", CounterSales.items(lines))
            put("p_method", method.wire)
            put("p_device", device.toJson())
            put("p_expected_total", paper)
            val t = cardTerminal?.takeIf { method == CounterPayment.CARD }
            if (t == null) put("p_card_terminal", JsonNull) else put("p_card_terminal", t)
        }

    /** The till's refusal as an [OutageException]; null for anything else. */
    fun error(e: Exception): OutageException? {
        if (e is NotAllowedException) return OutageException(OutageError.NOT_STAFF, e.message ?: "staff only")
        if (e !is BackendException) return null
        val reason = OutageError.of(e.code) ?: return null
        return OutageException(reason, e.message ?: reason.code)
    }
}

/** The Kassenausfall: [SupabaseKassenausfallBackend] over the database's functions. */
interface KassenausfallBackend {
    /** The outage notes of [day] (today when null); null on a database without them. */
    suspend fun outages(day: LocalDate?): List<TillOutage>?

    /** Reports the note; the same [id] with other values corrects it (today only). */
    suspend fun report(id: String, req: OutageRequest): TillOutage

    /** Enters one Notbeleg; the same [id] again is the same sale. */
    suspend fun late(
        id: String,
        outage: String,
        slip: Int,
        at: Instant,
        dineIn: Boolean,
        lines: List<BasketLine>,
        method: CounterPayment,
        paper: Double,
        cardTerminal: Int?,
    ): CounterSale
}

/**
 * [KassenausfallBackend] over PostgREST, as the signed-in account. Throws
 * [OutageException] for the till's refusals and [NeedsServerUpdateException]
 * on a database without the Kassenausfall.
 */
class SupabaseKassenausfallBackend internal constructor(private val rest: SupabaseRest, private val device: () -> CounterDevice) :
    KassenausfallBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager, device: () -> CounterDevice) :
        this(SupabaseRest(config, http, sessions), device)

    override suspend fun outages(day: LocalDate?): List<TillOutage>? {
        val body =
            try {
                rest.rpc("till_outages", buildJsonObject {
                    if (day == null) put("p_day", JsonNull) else put("p_day", day.toString())
                })
            } catch (e: BackendException) {
                if (e.missingFunction) return null
                throw e
            }
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TillOutage.serializer()), body)
    }

    override suspend fun report(id: String, req: OutageRequest): TillOutage {
        val text = call("till_outage_report", Kassenausfall.reportBody(id, req, device()))
        return json.decodeFromString(TillOutage.serializer(), text)
    }

    override suspend fun late(
        id: String,
        outage: String,
        slip: Int,
        at: Instant,
        dineIn: Boolean,
        lines: List<BasketLine>,
        method: CounterPayment,
        paper: Double,
        cardTerminal: Int?,
    ): CounterSale {
        val text =
            call("counter_sale_late", Kassenausfall.lateBody(id, outage, slip, at, dineIn, lines, method, paper, device(), cardTerminal))
        return json.decodeFromString(CounterSale.serializer(), text)
    }

    private suspend fun call(function: String, args: JsonObject): String =
        try {
            rest.rpc(function, args)
        } catch (e: Exception) {
            if (e is BackendException && e.missingFunction) throw NeedsServerUpdateException(Kassenausfall.MIGRATION)
            throw Kassenausfall.error(e) ?: e
        }
}
