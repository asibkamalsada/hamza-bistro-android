package de.hamzabistro.printstation.core

import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

// The Kassenbuch (hamza-bistro-web: 20261128130000_kassenbuch): what goes into
// and out of the drawer besides sales — the opening float, cash taken to the
// safe or the bank and back, private money in and out, and the count. Each
// entry that moves money is a Beleg of its own the TSE signs; the database is
// the till and decides everything, this app sends what staff typed.

/** What an entry is; the DSFinV-K GV_TYP in brackets. */
@Serializable
enum class CashBookKind(val wire: String) {
    /** Anfangsbestand: the cash counted when the day starts. Once a day. */
    @SerialName("opening") OPENING("opening"),

    /** DifferenzSollIst: the cash counted, against what the till expects. */
    @SerialName("count") COUNT("count"),

    /** Geldtransit out: to the safe in the shop, or to the bank. */
    @SerialName("transit_out") TRANSIT_OUT("transit_out"),

    /** Geldtransit in: change from the safe or the bank. */
    @SerialName("transit_in") TRANSIT_IN("transit_in"),

    /** Privatentnahme. */
    @SerialName("withdrawal") WITHDRAWAL("withdrawal"),

    /** Privateinlage. */
    @SerialName("deposit") DEPOSIT("deposit");

    /** The amount is what was counted, rather than what moved. */
    val counted: Boolean
        get() = this == OPENING || this == COUNT

    /** Geldtransit: where it went or came from. */
    val needsPlace: Boolean
        get() = this == TRANSIT_OUT || this == TRANSIT_IN

    /** Private money: what for. */
    val needsNote: Boolean
        get() = this == WITHDRAWAL || this == DEPOSIT

    /** Takes cash out of the drawer. */
    val out: Boolean
        get() = this == TRANSIT_OUT || this == WITHDRAWAL
}

/** Where Geldtransit went or came from. */
@Serializable
enum class CashBookPlace(val wire: String) {
    @SerialName("safe") SAFE("safe"),
    @SerialName("bank") BANK("bank"),
}

/** One entry, as cash_book_add() and cash_up().drawer.entries give it. */
@Serializable
data class CashBookEntry(
    val id: String,
    /** Its Bon number, from the orders' sequence. */
    val number: Long,
    val day: String,
    val kind: CashBookKind,
    /** What it did to the drawer: + in, − out; for a count the difference. */
    val amount: Double = 0.0,
    /** OPENING and COUNT: the cash counted. */
    val counted: Double? = null,
    /** COUNT: what the till expected. */
    val expected: Double? = null,
    val place: CashBookPlace? = null,
    val note: String? = null,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
    @SerialName("created_by") val createdBy: String = "",
    @SerialName("created_on") val createdOn: String? = null,
    /** This till's TSE signs it: signing on, and money moved. */
    val signing: Boolean = false,
    @SerialName("tse") val receipt: CounterReceipt? = null,
)

/** The last count of the day. */
@Serializable
data class CashCount(
    val counted: Double = 0.0,
    val expected: Double = 0.0,
    val difference: Double = 0.0,
    @Serializable(with = InstantSerializer::class) val at: Instant? = null,
    val by: String = "",
)

/** cash_up(day).drawer: what the till expects in the drawer. In euros. */
@Serializable
data class CashDrawer(
    val day: String,
    /** The float booked; null while none is. */
    val opening: Double? = null,
    /** The day's cash takings, card tips paid out already taken off. */
    val cash: Double = 0.0,
    @SerialName("transit_in") val transitIn: Double = 0.0,
    /** Negative. */
    @SerialName("transit_out") val transitOut: Double = 0.0,
    val deposits: Double = 0.0,
    /** Negative. */
    val withdrawals: Double = 0.0,
    val differences: Double = 0.0,
    /** What should be in the drawer now. */
    val expected: Double = 0.0,
    @SerialName("last_count") val lastCount: CashCount? = null,
    /** What moved since the last count, sales included; null without one. */
    @SerialName("since_count") val sinceCount: Double? = null,
    val entries: List<CashBookEntry> = emptyList(),
) {
    val date: LocalDate
        get() = LocalDate.parse(day)
}

/** Why the till refused an entry, by its error code. */
enum class CashBookError(val code: String) {
    /** 42501: the account is not staff (any more). */
    NOT_STAFF("42501"),

    /** HB508 (and HB497, a device without an id): not an entry the till takes. */
    BAD_VALUE("HB508"),

    /** HB509: this id is another entry. */
    KEY_REUSED("HB509"),

    /** HB510: today's float is booked already. */
    OPENING_TWICE("HB510"),

    /** HB511: more out than the drawer should hold. */
    TOO_MUCH("HB511"),

    /** HB512: private money without a note. */
    NO_NOTE("HB512");

    companion object {
        fun of(code: String?): CashBookError? =
            when (code) {
                "HB497" -> BAD_VALUE
                else -> entries.firstOrNull { it.code == code }
            }
    }
}

/** The till refused the entry, for [reason]. */
class CashBookException(val reason: CashBookError, message: String) : Exception(message)

/** The rules the database keeps, mirrored so the app offers only what it will take. */
object Kassenbuch {
    /** The server update that brought the Kassenbuch. */
    const val MIGRATION = "20261128130000_kassenbuch"

    /** What the drawer usually starts with (the owner, 28 November 2026). */
    const val USUAL_FLOAT = 85.0

    /** Mirrors cash_book_add(): the most one entry may say. */
    const val MAX = 10_000.0

    /** Mirrors the column: the most a note may say. */
    const val NOTE_MAX = 300

    /** The order the screen offers them in: the daily ones first. */
    val KINDS: List<CashBookKind> =
        listOf(
            CashBookKind.OPENING,
            CashBookKind.COUNT,
            CashBookKind.TRANSIT_OUT,
            CashBookKind.TRANSIT_IN,
            CashBookKind.WITHDRAWAL,
            CashBookKind.DEPOSIT,
        )

    /** The kinds that can be booked now: the float only while none is. */
    fun kinds(drawer: CashDrawer?): List<CashBookKind> =
        KINDS.filter { it != CashBookKind.OPENING || drawer?.opening == null }

    private val EURO = Regex("""^\d+(\.\d{1,2})?$""")

    /** A euro amount as staff type it: "85", "85,5", "1.234,50", "85.00 €". Null otherwise, or finer than a cent. */
    fun parseEuro(text: String): Double? {
        var s = text.trim().replace(Regex("""[\s€]"""), "")
        if (s.isEmpty()) return null
        if (s.contains(',')) s = s.replace(".", "").replace(',', '.')
        if (!EURO.matches(s)) return null
        return s.toDouble()
    }

    /** Mirrors HB508: 0,00 to 10 000,00 €, cents; 0,00 only for what was counted. */
    fun amountAllowed(kind: CashBookKind, amount: Double?): Boolean {
        if (amount == null || amount.isNaN() || amount < 0 || amount > MAX) return false
        if (cents(amount) != amount) return false
        return amount > 0 || kind.counted
    }

    /** Whether the entry can be booked, as far as the app knows. */
    fun canBook(drawer: CashDrawer?, kind: CashBookKind, amount: Double?, note: String): Boolean {
        if (drawer == null || !amountAllowed(kind, amount)) return false
        if (kind == CashBookKind.OPENING && drawer.opening != null) return false
        if (kind.needsNote && note.isBlank()) return false
        if (kind.out && amount!! > drawer.expected) return false
        return true
    }

    /** The difference a count of [counted] would book. */
    fun countDifference(drawer: CashDrawer, counted: Double): Double = cents(counted - drawer.expected)

    /** What the drawer would hold after the entry. */
    fun drawerAfter(drawer: CashDrawer, kind: CashBookKind, amount: Double): Double =
        when (kind) {
            CashBookKind.COUNT -> amount
            CashBookKind.TRANSIT_OUT, CashBookKind.WITHDRAWAL -> cents(drawer.expected - amount)
            CashBookKind.OPENING, CashBookKind.TRANSIT_IN, CashBookKind.DEPOSIT -> cents(drawer.expected + amount)
        }

    fun body(
        id: String,
        kind: CashBookKind,
        amount: Double,
        note: String?,
        place: CashBookPlace?,
        device: CounterDevice,
    ): JsonObject =
        buildJsonObject {
            put("p_id", id)
            put("p_kind", kind.wire)
            put("p_amount", amount)
            val n = note?.trim()?.take(NOTE_MAX)?.takeIf { it.isNotEmpty() }
            if (n == null) put("p_note", JsonNull) else put("p_note", n)
            val p = place?.takeIf { kind.needsPlace }
            if (p == null) put("p_place", JsonNull) else put("p_place", p.wire)
            put("p_device", device.toJson())
        }

    /** The till's refusal as a [CashBookException]; null for anything else. */
    fun error(e: Exception): CashBookException? {
        if (e is NotAllowedException) return CashBookException(CashBookError.NOT_STAFF, e.message ?: "staff only")
        if (e !is BackendException) return null
        val reason = CashBookError.of(e.code) ?: return null
        return CashBookException(reason, e.message ?: reason.code)
    }

    private fun cents(amount: Double): Double = (amount * 100).roundToLong() / 100.0
}

/** The Kassenbuch: [SupabaseKassenbuchBackend] over the database's functions. */
interface KassenbuchBackend {
    /** The drawer of [day] (today when null), from cash_up(); null on a database without it. */
    suspend fun drawer(day: LocalDate?): CashDrawer?

    /** Books an entry, today. The same [id] again is the same entry. */
    suspend fun add(id: String, kind: CashBookKind, amount: Double, note: String?, place: CashBookPlace?): CashBookEntry
}

/**
 * [KassenbuchBackend] over PostgREST, as the signed-in account. Throws
 * [CashBookException] for the till's refusals and
 * [NeedsServerUpdateException] on a database without the Kassenbuch.
 */
class SupabaseKassenbuchBackend internal constructor(private val rest: SupabaseRest, private val device: () -> CounterDevice) :
    KassenbuchBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager, device: () -> CounterDevice) :
        this(SupabaseRest(config, http, sessions), device)

    override suspend fun drawer(day: LocalDate?): CashDrawer? {
        val body =
            try {
                rest.rpc("cash_up", buildJsonObject {
                    if (day == null) put("p_day", JsonNull) else put("p_day", day.toString())
                })
            } catch (e: BackendException) {
                if (e.missingFunction) return null
                throw e
            }
        return json.decodeFromString(DrawerOnly.serializer(), body).drawer
            ?: throw NeedsServerUpdateException(Kassenbuch.MIGRATION)
    }

    override suspend fun add(
        id: String,
        kind: CashBookKind,
        amount: Double,
        note: String?,
        place: CashBookPlace?,
    ): CashBookEntry {
        val text =
            try {
                rest.rpc("cash_book_add", Kassenbuch.body(id, kind, amount, note, place, device()))
            } catch (e: Exception) {
                if (e is BackendException && e.missingFunction) throw NeedsServerUpdateException(Kassenbuch.MIGRATION)
                throw Kassenbuch.error(e) ?: e
            }
        return json.decodeFromString(CashBookEntry.serializer(), text)
    }

    @Serializable private class DrawerOnly(val drawer: CashDrawer? = null)
}
