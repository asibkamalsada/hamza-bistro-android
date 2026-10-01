package de.hamzabistro.printstation.ui

import android.content.Context
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.StaffOrder
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Currency
import java.util.Locale

/**
 * How times and money read, on a Leipzig clock whatever the device is set
 * to — the same clock as the site, the ticket and the customer's email.
 */
object Format {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** "18:32". */
    fun clock(at: Instant): String = CLOCK.format(at.atZone(AlarmPolicy.LEIPZIG))

    /** "22:00", for a time of day with no date. */
    fun time(at: LocalTime): String = CLOCK.format(at)

    /** "18:32", from the wall clock in milliseconds. */
    fun clock(at: Long): String = clock(Instant.ofEpochMilli(at))

    /** "23,40 €" — in German whatever the device's language, as on the ticket. */
    fun euro(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.GERMANY).apply { currency = Currency.getInstance("EUR") }.format(amount)

    /** "2× Döner, 1× Cola", for a notification. */
    fun dishes(order: StaffOrder): String = order.items.joinToString(", ") { "${it.qty}× ${it.name}" }

    /** "heute 18:30", "morgen 18:30", "Sa. 18:30" — the day and time a pre-order is for. */
    fun slot(context: Context, at: Instant, now: Instant = Instant.now()): String {
        val day = at.atZone(AlarmPolicy.LEIPZIG).toLocalDate()
        val today = now.atZone(AlarmPolicy.LEIPZIG).toLocalDate()
        val name =
            when (day) {
                today -> context.getString(R.string.slot_today)
                today.plusDays(1) -> context.getString(R.string.slot_tomorrow)
                else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, context.resources.configuration.locales[0])
            }
        return "$name ${clock(at)}"
    }
}
