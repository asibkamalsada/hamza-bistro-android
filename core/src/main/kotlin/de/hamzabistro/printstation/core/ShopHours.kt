package de.hamzabistro.printstation.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
 * A stretch of time in which the shop takes no orders — or, with a [scope]
 * other than all (hamza-bistro-web#72), no deliveries, or none to some rings.
 */
@Serializable
data class ShopClosure(
    val id: Long,
    @SerialName("starts_at") @Serializable(with = InstantSerializer::class) val startsAt: Instant,
    /** Null: until somebody opens again. */
    @SerialName("ends_at") @Serializable(with = InstantSerializer::class) val endsAt: Instant? = null,
    /** Who closed — told to staff only. */
    val by: String? = null,
    /** A [ClosureScope]; absent from a database without scopes, where every closure stops everything. */
    val scope: String = ClosureScope.ALL,
    /** For [ClosureScope.RINGS]: the rings not delivered to. */
    val rings: List<String>? = null,
) {
    /** Whether it stops everything, rather than only delivery. */
    val stopsAll: Boolean
        get() = scope != ClosureScope.DELIVERY && scope != ClosureScope.RINGS

    fun covers(at: Instant): Boolean = !startsAt.isAfter(at) && (endsAt == null || endsAt.isAfter(at))
}

/**
 * One weekday's delivery hours, as delivery_hours keeps them: minutes since
 * midnight, Leipzig time, on the quarter hour. A day without delivery keeps
 * its times, so a Ruhetag switched back on comes back with its hours.
 */
@Serializable
data class DeliveryDay(
    /** 0 = Sunday, as the database counts. */
    val day: Int,
    val delivers: Boolean,
    val opens: Int,
    /** 1440 is midnight at the end of the day. */
    val closes: Int,
) {
    /** What the table's own checks allow — on a day off too, since it keeps its times. */
    val valid: Boolean
        get() = opens % QUARTER == 0 && closes % QUARTER == 0 && opens >= 0 && closes <= MIDNIGHT && opens < closes

    companion object {
        const val QUARTER = 15
        const val MIDNIGHT = 24 * 60

        /** Monday first, as the shop's week is read. */
        val WEEK_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
    }
}

/** Where the shop stands, for the line above the queue. */
enum class ShopState {
    /** Orders come in. */
    OPEN,

    /** Somebody closed it: a pause, the evening, until further notice. */
    CLOSED,

    /** Outside the week's delivery hours; nobody closed anything. */
    OUTSIDE,
}

/**
 * Whether the shop takes orders, as shop_hours() in hamza-bistro-web works it
 * out (supabase/migrations/20261001150000_shop_hours.sql), with the week and
 * the closures it follows from.
 */
@Serializable
data class ShopHours(
    /** The week, by day; empty from a database that sent none. */
    val delivery: List<DeliveryDay> = emptyList(),
    @SerialName("open_now") val openNow: Boolean,
    /** While open: when that stops, by the hours or by a closure. */
    @SerialName("open_until") @Serializable(with = InstantSerializer::class) val openUntil: Instant? = null,
    /** While not: when orders come in again; null when nobody can say. */
    @SerialName("next_open") @Serializable(with = InstantSerializer::class) val nextOpen: Instant? = null,
    /** Running now or still to come. */
    val closures: List<ShopClosure> = emptyList(),
    /** Only in the answer to closing: open orders already booked for a time inside it. */
    @SerialName("preorders_inside") val preordersInside: Int = 0,
    /** Busy mode's minutes on every promise: 0 while off or over, and from a database without it. */
    @SerialName("busy_extra_minutes") val busyExtraMinutes: Int = 0,
    /** When busy mode ends by itself; null while off. */
    @SerialName("busy_until") @Serializable(with = InstantSerializer::class) val busyUntil: Instant? = null,
    /** Who switched busy mode on — told to staff only. */
    @SerialName("busy_by") val busyBy: String? = null,
    /** The weekly delivery breaks: the active ones, and for staff the switched-off ones too. */
    @SerialName("delivery_breaks") val deliveryBreaks: List<DeliveryBreak> = emptyList(),
    /** From this many minutes before a break, delivery for right now is refused. */
    @SerialName("delivery_break_lead_minutes") val breakLeadMinutes: Int = DeliveryBreak.DEFAULT_LEAD,
    /**
     * The special days from today on (hamza-bistro-web#119), by date; null
     * from a database without them, which needs the server update first.
     */
    @SerialName("special_days") val specialDays: List<SpecialDay>? = null,
    /** The hours in force today, special or not, as read; null from a database without special days. */
    val today: DayHours? = null,
) {
    /** The special day on [date], or null for the normal week. */
    fun specialOn(date: LocalDate): SpecialDay? = specialDays?.firstOrNull { it.day == date }

    /**
     * The hours in force on [date]: its special day where it has one, its
     * weekday's otherwise; null when the week was not sent.
     */
    fun hoursOn(date: LocalDate): DayHours? {
        specialOn(date)?.let { special ->
            val open = special.hours
            return DayHours(date, delivers = open != null, opens = open?.opens, closes = open?.closes, special = true, label = special.label)
        }
        val week = delivery.firstOrNull { it.day == SpecialCalendar.weekday(date) } ?: return null
        return DayHours(date, week.delivers, week.opens, week.closes)
    }

    /**
     * Today's hours at [now]: [today] as the database said them, or — once
     * midnight has passed since this was read — worked out for the new day
     * from the week and the special days.
     */
    fun todayAt(now: Instant): DayHours? {
        val date = SpecialCalendar.today(now)
        return today?.takeIf { it.day == date } ?: hoursOn(date)
    }

    /**
     * Busy mode at [now], or null while it is off — or over since this was
     * read: it ends by itself at [busyUntil], and the app stops adding the
     * minutes then, as the site does (busyAt() in opening-hours.ts).
     */
    fun busyAt(now: Instant): Busy? {
        val until = busyUntil ?: return null
        return if (busyExtraMinutes > 0 && until.isAfter(now)) Busy(busyExtraMinutes, until, busyBy) else null
    }

    /** The minutes busy mode adds at [now]: 0 while it is off. */
    fun busyMinutes(now: Instant): Int = busyAt(now)?.minutes ?: 0

    /**
     * The closure of everything running at [now], the one lasting longest
     * where several overlap. A pause of delivery alone is not one — the shop
     * takes collection orders through it: see [deliveryPauseAt].
     */
    fun closureAt(now: Instant): ShopClosure? =
        closures.filter { it.stopsAll && it.covers(now) }.maxWithOrNull(compareBy(nullsLast()) { it.endsAt })

    /**
     * The pause of delivery running at [now], or null: delivery_pause_at()
     * in 20261002180000_scoped_pause.sql, worked out from [closures] so it
     * ends by itself between two reads. A pause of all delivery outranks any
     * of rings; where several of the same kind overlap, the rings add up and
     * it lasts until the end of the longest.
     */
    fun deliveryPauseAt(now: Instant): DeliveryPause? {
        val running = closures.filter { !it.stopsAll && it.covers(now) }
        if (running.isEmpty()) return null
        val scope = if (running.any { it.scope == ClosureScope.DELIVERY }) ClosureScope.DELIVERY else ClosureScope.RINGS
        val picked = running.filter { it.scope == scope }
        val until = if (picked.any { it.endsAt == null }) null else picked.maxOf { it.endsAt!! }
        val rings = if (scope == ClosureScope.RINGS) picked.flatMap { it.rings.orEmpty() }.distinct().sorted() else emptyList()
        return DeliveryPause(scope, rings, until, picked.firstNotNullOfOrNull { it.by })
    }

    /**
     * The active delivery break covering [now] or starting within [lead]
     * minutes of it, the same Leipzig day; the earliest where several match.
     * delivery_break_at() in 20261002180000_scoped_pause.sql.
     */
    fun breakAt(now: Instant, lead: Int = breakLeadMinutes): DeliveryBreak? {
        val local = now.atZone(AlarmPolicy.LEIPZIG)
        val day = local.dayOfWeek.value % 7
        val minutes = local.hour * 60 + local.minute
        return deliveryBreaks
            .filter { it.active && it.day == day && it.starts <= minutes + maxOf(lead, 0) && it.ends > minutes }
            .minWithOrNull(compareBy({ it.starts }, { it.id ?: Long.MAX_VALUE }))
    }

    /**
     * The delivery break to say above the queue at [now]: running, or
     * starting within the lead — while the shop is open, since closed says
     * enough.
     */
    fun breakNotice(now: Instant): BreakNotice? {
        if (state(now) != ShopState.OPEN) return null
        val brk = breakAt(now) ?: return null
        val local = now.atZone(AlarmPolicy.LEIPZIG)
        return BreakNotice(brk, running = brk.starts <= local.hour * 60 + local.minute)
    }

    /**
     * Where the shop stands at [now], which may be up to a poll later than
     * this was read: a closure that has started since is closed, and a
     * pause that has run out since is open again.
     */
    fun state(now: Instant): ShopState =
        when {
            closureAt(now) != null -> ShopState.CLOSED
            openNow && (openUntil == null || openUntil.isAfter(now)) -> ShopState.OPEN
            !openNow && nextOpen != null && !nextOpen.isAfter(now) -> ShopState.OPEN
            else -> ShopState.OUTSIDE
        }

    /** While closed: when orders come in again, as far as is known at [now]. */
    fun reopensAt(now: Instant): Instant? = nextOpen?.takeIf { it.isAfter(now) } ?: closureAt(now)?.endsAt
}

/** Busy mode while it is on: every promise [minutes] longer, until [until]. */
data class Busy(val minutes: Int, val until: Instant, val by: String?) {
    companion object {
        /** The extra minutes the switch offers. */
        val MINUTES: List<Int> = listOf(15, 30, 45)

        /** For how long: 30 Min., 1 Std.; null is the rest of the day. */
        val FOR: List<Duration?> = listOf(Duration.ofMinutes(30), Duration.ofHours(1), null)
    }
}

/**
 * The shop's settings, as shop_settings() answers staff
 * (20261002150000_auto_decline.sql, 20261002160000_busy_mode.sql and
 * 20261003160000_kitchen_capacity.sql).
 */
@Serializable
data class ShopSettings(
    /** Minutes an order may wait unanswered before the database declines it; null is off. */
    @SerialName("auto_decline_minutes") val autoDeclineMinutes: Int? = null,
    /** How long before its time an unanswered pre-order is declined. */
    @SerialName("auto_decline_preorder_lead_minutes") val autoDeclinePreorderLeadMinutes: Int = 30,
    @SerialName("updated_at") @Serializable(with = InstantSerializer::class) val updatedAt: Instant? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("busy_extra_minutes") val busyExtraMinutes: Int = 0,
    @SerialName("busy_until") @Serializable(with = InstantSerializer::class) val busyUntil: Instant? = null,
    @SerialName("busy_set_at") @Serializable(with = InstantSerializer::class) val busySetAt: Instant? = null,
    @SerialName("busy_set_by") val busySetBy: String? = null,
    /** The kitchen's cap: dishes per quarter hour, 0 being off; null on a database without it. */
    @SerialName("dishes_per_slot") val dishesPerSlot: Int? = null,
)

/**
 * Opening and closing the shop, and its delivery hours, as staff. The
 * database checks that the account is staff; a print account is refused
 * like anybody else. Every call answers with the hours as they now stand.
 */
interface ShopBackend {
    /** Where the shop stands; null on a database that does not know about closing yet. */
    suspend fun hours(): ShopHours?

    /**
     * Closes from [from] (now, when null) until [until] (until somebody opens
     * again, when null) — everything, or with [what] only delivery, or
     * delivery to the outer rings. Throws [InvalidHoursException] for one
     * that ends before it starts.
     */
    suspend fun close(until: Instant?, from: Instant? = null, what: PauseWhat = PauseWhat.ALL): ShopHours

    /** Ends every closure running now, a pause of delivery included. One planned for later stays planned. */
    suspend fun open(): ShopHours

    /**
     * Adds a weekly delivery break ([DeliveryBreak.id] null) or changes one.
     * Throws [InvalidHoursException] for one the database refuses: off the
     * quarter hour, starting after it ends, or gone meanwhile.
     */
    suspend fun saveBreak(brk: DeliveryBreak): ShopHours

    suspend fun deleteBreak(id: Long): ShopHours

    /**
     * From [minutes] before a break, no delivery for right now. Throws
     * [InvalidHoursException] outside 0–120 on the five.
     */
    suspend fun setBreakLead(minutes: Int): ShopHours

    /** Takes a closure off the list altogether: the holiday is not happening after all. */
    suspend fun removeClosure(id: Long): ShopHours

    /**
     * The same [hours] on every date in [days], replacing what they had
     * (hamza-bistro-web#119). The answer's [ShopHours.preordersInside] says
     * how many orders already booked on those dates the new hours do not
     * take. Throws [InvalidSpecialDayException] for dates or hours the
     * database refuses, and [NeedsServerUpdateException] on a database
     * without special days.
     */
    suspend fun setSpecialDays(days: Collection<LocalDate>, hours: SpecialHours, label: String): ShopHours

    /** "Zurück auf normal": [days] go back to the normal week. A date without special hours is no error. */
    suspend fun clearSpecialDays(days: Collection<LocalDate>): ShopHours

    /**
     * The whole week at once, so it is never saved half-changed. Throws
     * [InvalidHoursException] for a day that closes before it opens.
     */
    suspend fun saveWeek(week: List<DeliveryDay>): ShopHours

    /**
     * Busy mode: every promise [minutes] longer, for [duration] from now, or
     * to midnight in Leipzig when null. Replaces busy mode already on.
     * Throws [InvalidSettingException] for minutes or a length the database
     * does not take.
     */
    suspend fun busy(minutes: Int, duration: Duration?): ShopHours

    /** Back to normal, now. */
    suspend fun notBusy(): ShopHours

    /** The shop's settings; null on a database that has none yet. */
    suspend fun settings(): ShopSettings?

    /**
     * Declines orders left unanswered for [minutes]; null switches it off.
     * Throws [InvalidSettingException] outside [AutoDecline.ALLOWED].
     */
    suspend fun setAutoDecline(minutes: Int?): ShopSettings

    /**
     * At most [dishes] dishes per quarter hour for customers to book; 0
     * switches the cap off. Throws [InvalidSettingException] outside
     * [Kitchen.ALLOWED].
     */
    suspend fun setDishesPerSlot(dishes: Int): ShopSettings

    /**
     * How full the kitchen is in every quarter hour from [from]'s up to
     * [to]; null on a database without the kitchen's cap.
     */
    suspend fun kitchenSlots(from: Instant, to: Instant): List<KitchenSlot>?
}

/** [ShopBackend] over PostgREST, as the signed-in account. */
class SupabaseShopBackend internal constructor(private val rest: SupabaseRest) : ShopBackend {
    constructor(config: SupabaseConfig, http: OkHttpClient, sessions: SessionManager) :
        this(SupabaseRest(config, http, sessions))

    override suspend fun hours(): ShopHours? =
        try {
            parse(rest.rpc("shop_hours", buildJsonObject {}))
        } catch (e: BackendException) {
            if (e.missingFunction) null else throw e
        }

    override suspend fun close(until: Instant?, from: Instant?, what: PauseWhat): ShopHours =
        call(
            "shop_close",
            buildJsonObject {
                if (until == null) put("p_until", JsonNull) else put("p_until", until.toString())
                if (from != null) put("p_from", from.toString())
                // Everything is the call as it was before scopes, which a
                // database without them still takes.
                if (what != PauseWhat.ALL) {
                    put("p_scope", what.scope)
                    what.rings?.let { rings -> put("p_rings", JsonArray(rings.map(::JsonPrimitive))) }
                }
            },
        )

    override suspend fun open(): ShopHours = call("shop_open", buildJsonObject {})

    override suspend fun saveBreak(brk: DeliveryBreak): ShopHours =
        call(
            "delivery_break_save",
            buildJsonObject {
                if (brk.id == null) put("p_id", JsonNull) else put("p_id", brk.id)
                put("p_day", brk.day)
                put("p_starts", brk.starts)
                put("p_ends", brk.ends)
                put("p_label", brk.label.trim())
                put("p_active", brk.active)
            },
        )

    override suspend fun deleteBreak(id: Long): ShopHours = call("delivery_break_delete", buildJsonObject { put("p_id", id) })

    override suspend fun setBreakLead(minutes: Int): ShopHours =
        call("set_delivery_break_lead", buildJsonObject { put("p_minutes", minutes) })

    override suspend fun removeClosure(id: Long): ShopHours = call("shop_closure_delete", buildJsonObject { put("p_id", id) })

    override suspend fun setSpecialDays(days: Collection<LocalDate>, hours: SpecialHours, label: String): ShopHours =
        special("special_days_set", specialDaysSetBody(days, hours, label))

    override suspend fun clearSpecialDays(days: Collection<LocalDate>): ShopHours =
        special("special_days_clear", buildJsonObject { put("p_days", daysArray(days)) })

    /** The special days' calls: their own refusals, and a database that has not got them yet. */
    private suspend fun special(name: String, args: JsonObject): ShopHours =
        try {
            parse(rest.rpc(name, args))
        } catch (e: BackendException) {
            when {
                e.code == INVALID_SPECIAL_DATE -> throw InvalidSpecialDayException(SpecialDayError.DATE, e.message ?: name)
                e.code == INVALID_HOURS -> throw InvalidSpecialDayException(SpecialDayError.HOURS, e.message ?: name)
                e.missingFunction -> throw NeedsServerUpdateException(SPECIAL_DAYS_MIGRATION)
                else -> throw e
            }
        }

    override suspend fun saveWeek(week: List<DeliveryDay>): ShopHours =
        call(
            "set_delivery_hours",
            buildJsonObject { put("p_hours", json.encodeToJsonElement(ListSerializer(DeliveryDay.serializer()), week)) },
        )

    override suspend fun busy(minutes: Int, duration: Duration?): ShopHours =
        call(
            "shop_busy",
            buildJsonObject {
                put("p_minutes", minutes)
                // An ISO interval, "PT30M", which Postgres reads as one.
                if (duration == null) put("p_for", JsonNull) else put("p_for", duration.toString())
            },
        )

    override suspend fun notBusy(): ShopHours = call("shop_not_busy", buildJsonObject {})

    override suspend fun settings(): ShopSettings? =
        try {
            json.decodeFromString(ShopSettings.serializer(), rest.rpc("shop_settings", buildJsonObject {}))
        } catch (e: BackendException) {
            if (e.missingFunction) null else throw e
        }

    override suspend fun setAutoDecline(minutes: Int?): ShopSettings =
        try {
            val body =
                rest.rpc(
                    "set_auto_decline_minutes",
                    buildJsonObject { if (minutes == null) put("p_minutes", JsonNull) else put("p_minutes", minutes) },
                )
            json.decodeFromString(ShopSettings.serializer(), body)
        } catch (e: BackendException) {
            if (e.code == INVALID_AUTO_DECLINE) throw InvalidSettingException(e.message ?: "auto decline")
            throw e
        }

    override suspend fun setDishesPerSlot(dishes: Int): ShopSettings =
        try {
            json.decodeFromString(
                ShopSettings.serializer(),
                rest.rpc("set_dishes_per_slot", buildJsonObject { put("p_dishes", dishes) }),
            )
        } catch (e: BackendException) {
            if (e.code == INVALID_HOURS) throw InvalidSettingException(e.message ?: "dishes per slot")
            throw e
        }

    override suspend fun kitchenSlots(from: Instant, to: Instant): List<KitchenSlot>? =
        try {
            Kitchen.parse(
                rest.rpc(
                    "kitchen_slots",
                    buildJsonObject {
                        put("p_from", from.toString())
                        put("p_to", to.toString())
                    },
                )
            )
        } catch (e: BackendException) {
            if (e.missingFunction) null else throw e
        }

    private suspend fun call(name: String, args: JsonObject): ShopHours =
        try {
            parse(rest.rpc(name, args))
        } catch (e: BackendException) {
            // The database refusing what was sent as hours or as a closure.
            if (e.code == INVALID_HOURS) throw InvalidHoursException(e.message ?: name)
            if (e.code == INVALID_BUSY) throw InvalidSettingException(e.message ?: name)
            throw e
        }

    private fun parse(body: String): ShopHours = json.decodeFromString(ShopHours.serializer(), body)

    private companion object {
        /** See 20261001150000_shop_hours.sql; set_dishes_per_slot() refuses with it too. */
        const val INVALID_HOURS = "HB432"

        /** See 20261002150000_auto_decline.sql. */
        const val INVALID_AUTO_DECLINE = "HB433"

        /** See 20261002160000_busy_mode.sql. */
        const val INVALID_BUSY = "HB434"

        /** A date in the past, more than 366 days ahead, or none: 20261003140000_special_days.sql. */
        const val INVALID_SPECIAL_DATE = "HB456"

        const val SPECIAL_DAYS_MIGRATION = "20261003140000_special_days"
    }
}
