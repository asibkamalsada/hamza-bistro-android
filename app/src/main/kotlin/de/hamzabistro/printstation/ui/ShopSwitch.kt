package de.hamzabistro.printstation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
}

/**
 * Whether customers can order right now, with the switch for it — the
 * same line as above the queue on /orders. Closing takes two taps, the
 * second saying for how long; opening again is one.
 *
 * The week's hours and closures planned ahead are set on the website, which
 * the link opens.
 */
@Composable
fun ShopSwitch(shop: ShopView, now: Instant, actions: ShopActions) {
    val hours = shop.hours ?: return
    val context = LocalContext.current
    val state = hours.state(now)
    var choosing by remember { mutableStateOf(false) }

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
                    else ->
                        OutlinedButton(onClick = { choosing = true }) {
                            Text(stringResource(if (state == ShopState.OPEN) R.string.shop_pause else R.string.shop_close))
                        }
                }
                TextButton(onClick = { context.openShopHours() }) { Text(stringResource(R.string.shop_hours_link)) }
            }
        }
    }
}

/** "Bestellungen werden angenommen — bis 20:00 Uhr." and the like. */
private fun shopLine(context: Context, hours: ShopHours, state: ShopState, now: Instant): String =
    when (state) {
        ShopState.OPEN ->
            hours.openUntil?.takeIf { it.isAfter(now) }?.let { context.getString(R.string.shop_open_until, Format.clock(it)) }
                ?: context.getString(R.string.shop_open)
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

/** The week and the closures planned ahead are set on the website. */
private fun Context.openShopHours() {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_HOURS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser on this tablet: nothing to open.
    }
}

private val DAY_MONTH = DateTimeFormatter.ofPattern("d.M.")

private const val WEBSITE_HOURS = "https://www.hamzabistro.de/orders/hours"
