package de.hamzabistro.printstation.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.Busy
import de.hamzabistro.printstation.core.ShopHours
import de.hamzabistro.printstation.core.ShopState
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Whether the shop takes orders, as last read, and whether a switch is on its way. */
data class ShopView(
    /** Null until read, and on a database that does not know about closing. */
    val hours: ShopHours? = null,
    val busy: Boolean = false,
)

/** What the line's buttons do. */
interface ShopActions {
    fun pause(minutes: Long)

    fun closeForToday()

    fun closeForGood()

    fun open()

    /** Busy mode: every promise [minutes] longer, for [duration], or the rest of the day when null. */
    fun busy(minutes: Int, duration: Duration?)

    /** Busy mode off: "Wieder normal". */
    fun notBusy()
}

/**
 * Whether customers can order right now, with the switch for it — the
 * same line as above the queue on /orders. Closing takes two taps, the
 * second saying for how long; opening again is one.
 *
 * [onHours] opens the week's hours and the closures planned ahead; without
 * it — on that screen itself — there is no link.
 */
@Composable
fun ShopSwitch(shop: ShopView, now: Instant, actions: ShopActions, onHours: (() -> Unit)? = null) {
    val hours = shop.hours ?: return
    val context = LocalContext.current
    val state = hours.state(now)
    var choosing by remember { mutableStateOf(false) }
    // Busy mode in two taps, as on /orders: how much longer, then for how long.
    var busyStep by remember { mutableStateOf<BusyStep?>(null) }
    val busy = hours.busyAt(now)

    val colors =
        when (state) {
            ShopState.CLOSED -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ShopState.OPEN -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ShopState.OUTSIDE -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        }
    val textColor = if (state == ShopState.CLOSED) MaterialTheme.colorScheme.onErrorContainer else Color.Unspecified

    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = colors) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                shopLine(context, hours, state, now),
                color = textColor,
                fontWeight = if (state == ShopState.CLOSED) FontWeight.Bold else null,
            )
            hours.closureAt(now)?.by?.let {
                Text(
                    stringResource(R.string.shop_closed_by, it),
                    color = textColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            busy?.by?.takeIf { state == ShopState.OPEN }?.let {
                Text(stringResource(R.string.shop_busy_by, it), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    state == ShopState.CLOSED ->
                        Button(onClick = actions::open, enabled = !shop.busy) { Text(stringResource(R.string.shop_open_now)) }
                    choosing -> {
                        val done = { choosing = false }
                        if (state == ShopState.OPEN) {
                            OutlinedButton(onClick = { actions.pause(30); done() }, enabled = !shop.busy) {
                                Text(stringResource(R.string.shop_for_30))
                            }
                            OutlinedButton(onClick = { actions.pause(60); done() }, enabled = !shop.busy) {
                                Text(stringResource(R.string.shop_for_60))
                            }
                        }
                        OutlinedButton(onClick = { actions.closeForToday(); done() }, enabled = !shop.busy) {
                            Text(stringResource(R.string.shop_for_today))
                        }
                        OutlinedButton(onClick = { actions.closeForGood(); done() }, enabled = !shop.busy) {
                            Text(stringResource(R.string.shop_for_good))
                        }
                        TextButton(onClick = done) { Text(stringResource(R.string.shop_keep_open)) }
                    }
                    busyStep == BusyStep.HowMuch -> {
                        Text(stringResource(R.string.shop_busy_how_much))
                        for (extra in Busy.MINUTES) {
                            OutlinedButton(onClick = { busyStep = BusyStep.HowLong(extra) }) {
                                Text(stringResource(R.string.shop_busy_extra, extra))
                            }
                        }
                        TextButton(onClick = { busyStep = null }) { Text(stringResource(R.string.shop_keep_open)) }
                    }
                    busyStep is BusyStep.HowLong -> {
                        val extra = (busyStep as BusyStep.HowLong).minutes
                        Text(stringResource(R.string.shop_busy_how_long, extra))
                        for (duration in Busy.FOR) {
                            OutlinedButton(onClick = { actions.busy(extra, duration); busyStep = null }, enabled = !shop.busy) {
                                Text(stringResource(durationLabel(duration)))
                            }
                        }
                        TextButton(onClick = { busyStep = null }) { Text(stringResource(R.string.shop_keep_open)) }
                    }
                    else -> {
                        OutlinedButton(onClick = { choosing = true }) {
                            Text(stringResource(if (state == ShopState.OPEN) R.string.shop_pause else R.string.shop_close))
                        }
                        if (state == ShopState.OPEN) {
                            OutlinedButton(onClick = { busyStep = BusyStep.HowMuch }) { Text(stringResource(R.string.shop_busy)) }
                        }
                        if (busy != null) {
                            Button(onClick = actions::notBusy, enabled = !shop.busy) { Text(stringResource(R.string.shop_busy_off)) }
                        }
                    }
                }
                if (onHours != null) TextButton(onClick = onHours) { Text(stringResource(R.string.shop_hours_link)) }
            }
        }
    }
}

/** Where busy mode's switch is: how much longer, then for how long. */
private sealed interface BusyStep {
    data object HowMuch : BusyStep

    data class HowLong(val minutes: Int) : BusyStep
}

private fun durationLabel(duration: Duration?): Int =
    when (duration) {
        null -> R.string.shop_for_today
        Duration.ofMinutes(30) -> R.string.shop_for_30
        else -> R.string.shop_for_60
    }

/**
 * "Bestellungen werden angenommen — bis 20:00 Uhr." and the like; with busy
 * mode on, "… — bis 20:00 Uhr · +30 Min. bis 20:15 Uhr."
 */
private fun shopLine(context: Context, hours: ShopHours, state: ShopState, now: Instant): String =
    when (state) {
        ShopState.OPEN -> {
            val open =
                hours.openUntil?.takeIf { it.isAfter(now) }?.let { context.getString(R.string.shop_open_until, Format.clock(it)) }
                    ?: context.getString(R.string.shop_open)
            hours.busyAt(now)?.let {
                context.getString(R.string.shop_busy_suffix, open.removeSuffix("."), it.minutes, Format.clock(it.until))
            } ?: open
        }
        ShopState.CLOSED ->
            hours.reopensAt(now)?.let { context.getString(R.string.shop_closed_until, whenOpen(context, it, now)) }
                ?: context.getString(R.string.shop_closed_for_good)
        ShopState.OUTSIDE ->
            hours.nextOpen?.let { context.getString(R.string.shop_outside_hours, whenOpen(context, it, now)) }
                ?: context.getString(R.string.shop_no_hours)
    }

/**
 * "heute ab 13:10 Uhr", "morgen ab 11:00 Uhr", "Freitag ab 11:00 Uhr",
 * "am 27.10. ab 11:00 Uhr" — a weekday name only while it cannot mean two
 * days, as on the website.
 */
internal fun whenOpen(context: Context, at: Instant, now: Instant): String {
    val day = at.atZone(AlarmPolicy.LEIPZIG).toLocalDate()
    val today = now.atZone(AlarmPolicy.LEIPZIG).toLocalDate()
    val time = Format.clock(at)
    return when {
        day == today -> context.getString(R.string.shop_when_today, time)
        day == today.plusDays(1) -> context.getString(R.string.shop_when_tomorrow, time)
        Duration.between(now, at) < Duration.ofDays(6) ->
            context.getString(
                R.string.shop_when_day,
                day.dayOfWeek.getDisplayName(TextStyle.FULL, context.resources.configuration.locales[0]),
                time,
            )
        else -> context.getString(R.string.shop_when_date, DAY_MONTH.format(day), time)
    }
}

private val DAY_MONTH = DateTimeFormatter.ofPattern("d.M.")
