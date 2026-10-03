package de.hamzabistro.printstation.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A date with its own hours, or closed — "Besondere Tage"
 * (hamza-bistro-web#119, 20261003140000_special_days.sql). On its date it
 * replaces that weekday's [DeliveryDay], for delivery and collection alike;
 * pauses, busy mode and the weekly delivery breaks still apply on top.
 */
@Serializable
data class SpecialDay(
    /** A Leipzig date. */
    @Serializable(with = LocalDateSerializer::class) val day: LocalDate,
    val closed: Boolean,
    /** Minutes since midnight on the quarter hour, as in [DeliveryDay]; null when [closed]. */
    val opens: Int? = null,
    val closes: Int? = null,
    /** "Heiligabend"; null or empty for none. */
    val label: String? = null,
    /** Who set it — told to staff only. */
    val by: String? = null,
) {
    /** The label trimmed, or empty. */
    val labelText: String
        get() = label?.trim().orEmpty()

    /** The hours it sets, or null for closed (or a row that says no times). */
    val hours: SpecialHours.Open?
        get() = if (closed || opens == null || closes == null) null else SpecialHours.Open(opens, closes)
}

/**
 * The hours in force on one date, special or not: shop_hours().today, and
 * [ShopHours.hoursOn] for any other date.
 */
@Serializable
data class DayHours(
    @Serializable(with = LocalDateSerializer::class) val day: LocalDate,
    /** False on a closed special day, and on a weekday without delivery. */
    val delivers: Boolean,
    /** On a weekday without delivery, the times it keeps; null on a closed special day. */
    val opens: Int? = null,
    val closes: Int? = null,
    /** Whether a special day sets them. */
    val special: Boolean = false,
    val label: String? = null,
) {
    /** The label trimmed, or empty. */
    val labelText: String
        get() = label?.trim().orEmpty()
}

/** What the form sets the picked dates to: closed, or open from–to. */
sealed interface SpecialHours {
    data object Closed : SpecialHours

    /** [opens] to [closes] in minutes since midnight; 1440 is midnight at the end of the day. */
    data class Open(val opens: Int, val closes: Int) : SpecialHours {
        /** What special_days_set() takes: on the quarter hour, within the day, the end after the start. */
        val valid: Boolean
            get() =
                opens % DeliveryDay.QUARTER == 0 &&
                    closes % DeliveryDay.QUARTER == 0 &&
                    opens >= 0 &&
                    closes <= DeliveryDay.MIDNIGHT &&
                    opens < closes
    }
}

/**
 * The "Besondere Tage" form as it is filled in: which hours and a label,
 * for the dates picked in the calendar.
 */
data class SpecialForm(
    val closed: Boolean = false,
    val opens: Int = 17 * 60,
    val closes: Int = 21 * 60,
    val label: String = "",
) {
    /** What to send, or null for times the database would refuse. */
    val hours: SpecialHours?
        get() = if (closed) SpecialHours.Closed else SpecialHours.Open(opens, closes).takeIf { it.valid }

    companion object {
        /**
         * The form from the first date picked: its special hours where it has
         * them, and its weekday's normal hours otherwise — the usual starting
         * point for "shorter that day". As on the site, a weekday without
         * delivery offers its stored times, open.
         */
        fun from(date: LocalDate, hours: ShopHours?): SpecialForm {
            val special = hours?.specialOn(date)
            if (special != null) {
                val open = special.hours
                return SpecialForm(
                    closed = open == null,
                    opens = open?.opens ?: SpecialForm().opens,
                    closes = open?.closes ?: SpecialForm().closes,
                    label = special.labelText,
                )
            }
            val week = hours?.delivery?.firstOrNull { it.day == SpecialCalendar.weekday(date) } ?: return SpecialForm()
            return SpecialForm(closed = false, opens = week.opens, closes = week.closes)
        }
    }
}

/** One line of "Kommende besondere Tage": a special day, or a pause running or planned. */
sealed interface Upcoming {
    /** Where it sorts: the start of its day in Leipzig, or when the pause starts. */
    val from: Instant

    data class Special(val day: SpecialDay) : Upcoming {
        override val from: Instant
            get() = day.day.atStartOfDay(AlarmPolicy.LEIPZIG).toInstant()
    }

    data class Pause(val closure: ShopClosure) : Upcoming {
        override val from: Instant
            get() = closure.startsAt
    }
}

/**
 * The month calendar behind "Besondere Tage", as special-days-calendar.ts on
 * the site: Leipzig dates, Monday first, from today to [AHEAD] days on.
 */
object SpecialCalendar {
    /** How far ahead a special day can be set, as special_days_check() allows. */
    const val AHEAD = 366L

    /** At most this many characters of label. */
    const val MAX_LABEL = 60

    /** Today in Leipzig at [now], whatever the device's zone. */
    fun today(now: Instant): LocalDate = now.atZone(AlarmPolicy.LEIPZIG).toLocalDate()

    /** The last date a special day can be set on, seen from [today]. */
    fun last(today: LocalDate): LocalDate = today.plusDays(AHEAD)

    /** Whether a special day can be set on [date], seen from [today]. */
    fun selectable(date: LocalDate, today: LocalDate): Boolean = !date.isBefore(today) && !date.isAfter(last(today))

    /** The month the arrows may go back to: today's. */
    fun firstMonth(today: LocalDate): YearMonth = YearMonth.from(today)

    /** The month the arrows may go on to: the last selectable date's. */
    fun lastMonth(today: LocalDate): YearMonth = YearMonth.from(last(today))

    /** [month] moved by [by], kept between [firstMonth] and [lastMonth]. */
    fun shift(month: YearMonth, by: Long, today: LocalDate): YearMonth {
        val next = month.plusMonths(by)
        return when {
            next.isBefore(firstMonth(today)) -> firstMonth(today)
            next.isAfter(lastMonth(today)) -> lastMonth(today)
            else -> next
        }
    }

    /**
     * The month in weeks from Monday, as a wall calendar in Germany hangs:
     * the squares before the 1st and after the last day are null.
     */
    fun grid(month: YearMonth): List<List<LocalDate?>> {
        val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
        val cells = ArrayList<LocalDate?>()
        repeat(lead) { cells.add(null) }
        for (day in 1..month.lengthOfMonth()) cells.add(month.atDay(day))
        while (cells.size % 7 != 0) cells.add(null)
        return cells.chunked(7)
    }

    /** The selection with [date] added, or taken out when it was in; sorted. */
    fun toggle(selected: List<LocalDate>, date: LocalDate): List<LocalDate> =
        if (date in selected) selected - date else (selected + date).sorted()

    /** 0 = Sunday, as the database counts. */
    fun weekday(date: LocalDate): Int = date.dayOfWeek.value % 7
}

/** The special days and pauses to list, soonest first, from [now] on. */
fun ShopHours.upcoming(now: Instant): List<Upcoming> {
    val today = SpecialCalendar.today(now)
    val specials = specialDays.orEmpty().filter { !it.day.isBefore(today) }.map { Upcoming.Special(it) }
    // A pause that ran out drops off, as one ended by "Wieder öffnen" does.
    val pauses = closures.filter { it.endsAt == null || it.endsAt.isAfter(now) }.map { Upcoming.Pause(it) }
    return (specials + pauses).sortedBy { it.from }
}

/** `p_days` as special_days_set() and special_days_clear() take it: "2026-12-24", sorted, once each. */
internal fun daysArray(days: Collection<LocalDate>): JsonArray = JsonArray(days.distinct().sorted().map { JsonPrimitive(it.toString()) })

/** The body of special_days_set(): closed sends no times. */
internal fun specialDaysSetBody(days: Collection<LocalDate>, hours: SpecialHours, label: String): JsonObject =
    buildJsonObject {
        put("p_days", daysArray(days))
        put("p_closed", hours is SpecialHours.Closed)
        if (hours is SpecialHours.Open) {
            put("p_opens", hours.opens)
            put("p_closes", hours.closes)
        } else {
            put("p_opens", JsonNull)
            put("p_closes", JsonNull)
        }
        put("p_label", label.trim().take(SpecialCalendar.MAX_LABEL))
    }

/** A Leipzig date written "2026-12-24". */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDate {
        val text = decoder.decodeString()
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            // A timestamp where a date was meant: its first ten characters are the date.
            LocalDate.parse(text.take(10))
        }
    }

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
}
