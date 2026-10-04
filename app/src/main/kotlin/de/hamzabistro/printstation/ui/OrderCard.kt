package de.hamzabistro.printstation.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AutoDecline
import de.hamzabistro.printstation.core.CancelReason
import de.hamzabistro.printstation.core.DeclineUrgency
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.NoShows
import de.hamzabistro.printstation.core.OrderRating
import de.hamzabistro.printstation.core.OrderRatings
import de.hamzabistro.printstation.core.OrderSource
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.OrderStep
import de.hamzabistro.printstation.core.PackedUrgency
import de.hamzabistro.printstation.core.Payment
import de.hamzabistro.printstation.core.PaymentMethod
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.core.StaffQueue
import de.hamzabistro.printstation.queue.Pending
import de.hamzabistro.printstation.station.DevicePrefs
import java.time.Duration
import java.time.Instant

/** What a card's buttons do. */
interface OrderActions {
    fun accept(order: StaffOrder, minutes: Int)

    fun acceptScheduled(order: StaffOrder)

    fun moveOn(order: StaffOrder)

    /** "Bar" or "Karte": delivered or collected, and how it was paid. */
    fun deliver(order: StaffOrder, payment: PaymentMethod)

    fun cancel(order: StaffOrder, reason: CancelReason?)

    /**
     * "Nicht angetroffen", confirmed: cancelled as no_show, through the undo
     * window like any step (hamza-bistro-web#83).
     */
    fun noShow(order: StaffOrder) = cancel(order, CancelReason.NO_SHOW)

    /** "Zähler zurücksetzen": the account's no-shows back to 0; [done] hears how it went. */
    fun resetNoShows(order: StaffOrder, done: (NoShowReset) -> Unit)

    /** "+10 Min." on an accepted order. */
    fun delay(order: StaffOrder, minutes: Int)

    /** "Fertig": the bag is packed and waits for the driver. */
    fun pack(order: StaffOrder)

    /** "Doch nicht fertig". */
    fun unpack(order: StaffOrder)

    fun undo(order: StaffOrder)

    fun print(order: StaffOrder)

    fun call(phone: String)

    fun route(order: StaffOrder)
}

/** How "Zähler zurücksetzen" went. */
sealed interface NoShowReset {
    /** Back to 0; [before] is the count the account had. */
    data class Done(val before: Int) : NoShowReset

    /** It did not go through, said in words. */
    data class Failed(val reason: String) : NoShowReset
}

/** How a time line reads: in time, close, or late. */
enum class Tone {
    OK,
    SOON,
    LATE,
}

/** The line a kitchen reads first: how long it has waited, when it is due, when to start it. */
data class Timing(val text: String, val tone: Tone)

/** The time line of a card — timing() of the site's orders-page.ts. */
fun timing(context: Context, order: StaffOrder, lead: Int, now: Instant): Timing? {
    if (order.status == OrderStatus.NEW) {
        val declining = declineTiming(context, order, now)
        if (order.scheduledFor != null) return declining
        val waited = maxOf(0, -StaffQueue.minutesUntil(order.createdAt, now))
        val tone = if (waited >= 6) Tone.LATE else if (waited >= 3) Tone.SOON else Tone.OK
        val waiting = Timing(context.getString(R.string.waiting_for, waited), tone)
        if (declining == null) return waiting
        return Timing("${waiting.text} · ${declining.text}", if (declining.tone == Tone.LATE) Tone.LATE else tone)
    }
    val due = StaffQueue.promisedAt(order) ?: return null
    val left = StaffQueue.minutesUntil(due, now)
    val dueText = dueText(context, order, due)
    if (order.status == OrderStatus.CONFIRMED && order.scheduledFor != null) {
        val from = StaffQueue.cookFrom(order, lead)
        if (from != null && from.isAfter(now)) {
            return Timing("${context.getString(R.string.cook_from, Format.clock(from))} · $dueText", Tone.OK)
        }
        if (left >= 0) return Timing("${context.getString(R.string.cook_now)} · $dueText", Tone.SOON)
    }
    if (left < 0) return Timing("$dueText · ${context.getString(R.string.due_late, -left)}", Tone.LATE)
    return Timing("$dueText · ${context.getString(R.string.due_in, left)}", if (left <= 5) Tone.SOON else Tone.OK)
}

/**
 * "fällig 18:45", and "fällig 18:45 · verschoben +10 Min." once delayed: the
 * time is already the new one, and whoever reads the card should know it
 * moved.
 */
fun dueText(context: Context, order: StaffOrder, due: Instant): String {
    val at = context.getString(R.string.due_at, Format.clock(due))
    if (!StaffQueue.isDelayed(order)) return at
    return "$at · ${context.getString(R.string.due_delayed, order.delayMinutes)}"
}

/**
 * "wird in 3 Min. automatisch abgelehnt" — declineTiming() of the site: red
 * for the last two minutes; a pre-order more than an hour off says when.
 */
private fun declineTiming(context: Context, order: StaffOrder, now: Instant): Timing? {
    val countdown = AutoDecline.countdown(order, now) ?: return null
    val text =
        when {
            countdown.showTime -> context.getString(R.string.auto_decline_at, Format.slot(context, countdown.deadline, now))
            countdown.minutesLeft <= 0 -> context.getString(R.string.auto_decline_now)
            else -> context.getString(R.string.auto_decline_in, countdown.minutesLeft)
        }
    val tone =
        when (countdown.urgency) {
            DeclineUrgency.URGENT -> Tone.LATE
            DeclineUrgency.SOON -> Tone.SOON
            DeclineUrgency.CALM -> Tone.OK
        }
    return Timing(text, tone)
}

/**
 * "fertig seit 3 Min." on a packed bag — packedTiming() of the site: amber
 * from five minutes, red from ten.
 */
fun packedTiming(context: Context, order: StaffOrder, now: Instant): Timing? {
    val wait = StaffQueue.packedWait(order, now) ?: return null
    val tone =
        when (wait.urgency) {
            PackedUrgency.CALM -> Tone.OK
            PackedUrgency.SOON -> Tone.SOON
            PackedUrgency.LATE -> Tone.LATE
        }
    return Timing(context.getString(R.string.packed_for, wait.minutes), tone)
}

@Composable
fun toneColor(tone: Tone): Color =
    when (tone) {
        Tone.OK -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.SOON -> if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFF9A5B00)
        Tone.LATE -> MaterialTheme.colorScheme.error
    }

/** What an order's status is called in the kitchen — statusLabel() of the site. */
fun statusLabel(context: Context, order: StaffOrder): String =
    context.getString(
        when (order.status) {
            OrderStatus.NEW -> R.string.status_new
            // Packed and waiting for the driver: the badge the pick-up list is read by.
            OrderStatus.CONFIRMED -> if (StaffQueue.isPacked(order)) R.string.status_packed else R.string.status_confirmed
            OrderStatus.ON_THE_WAY -> if (order.pickup) R.string.status_ready else R.string.status_on_the_way
            OrderStatus.DELIVERED -> if (order.pickup) R.string.status_collected else R.string.status_delivered
            OrderStatus.CANCELLED -> R.string.status_cancelled
        }
    )

fun reasonLabel(context: Context, reason: CancelReason?): String =
    context.getString(
        when (reason) {
            CancelReason.BUSY -> R.string.reason_busy
            CancelReason.SOLD_OUT -> R.string.reason_sold_out
            CancelReason.UNREACHABLE -> R.string.reason_unreachable
            CancelReason.ADDRESS -> R.string.reason_address
            CancelReason.TIMEOUT -> R.string.reason_timeout
            CancelReason.NO_SHOW -> R.string.reason_no_show
            CancelReason.UNKNOWN -> R.string.reason_unknown
            null -> R.string.reason_none
        }
    )

/** "Telefon" or "Vor Ort / Theke" for an order staff typed in; null for one from the site. */
fun sourceLabel(context: Context, order: StaffOrder): String? =
    when (OrderSource.of(order.source)) {
        OrderSource.PHONE -> context.getString(R.string.source_phone)
        OrderSource.COUNTER -> context.getString(R.string.source_counter)
        null -> null
    }

/** "Bar", "Karte", "Online" — or "unbekannt": delivered from Telegram or by an older app. */
fun paymentLabel(context: Context, method: PaymentMethod?): String =
    context.getString(
        when (method) {
            PaymentMethod.CASH -> R.string.pay_cash
            PaymentMethod.CARD -> R.string.pay_card
            PaymentMethod.ONLINE -> R.string.pay_online
            null -> R.string.pay_unknown
        }
    )

/** What a step says while it waits on its undo window. */
fun stepLabel(context: Context, order: StaffOrder, step: OrderStep): String =
    when (step) {
        is OrderStep.Accept -> context.getString(R.string.pending_accepted, step.etaMinutes)
        OrderStep.AcceptScheduled ->
            context.getString(R.string.accept_scheduled, Format.slot(context, order.scheduledFor ?: Instant.now()))
        OrderStep.Out -> context.getString(if (order.pickup) R.string.step_ready else R.string.step_on_the_way)
        is OrderStep.Done -> {
            val done = context.getString(if (order.pickup) R.string.step_collected else R.string.step_delivered)
            step.payment?.let { "$done · ${paymentLabel(context, it)}" } ?: done
        }
        is OrderStep.Cancel ->
            when {
                step.reason == CancelReason.NO_SHOW -> context.getString(R.string.reason_no_show)
                order.status == OrderStatus.NEW -> context.getString(R.string.pending_declined)
                else -> context.getString(R.string.pending_cancelled)
            }
        is OrderStep.Delay -> context.getString(R.string.pending_delayed, step.minutes)
        OrderStep.Pack -> context.getString(R.string.step_packed)
        OrderStep.Unpack -> context.getString(R.string.step_unpack)
    }

/**
 * One order, as /orders draws it: number and badges, the time line, what to
 * cook, the note, what to collect, who and where — and the one or two
 * buttons that move it on. [compact] leaves out who and where, for the
 * alarm screen over the lock screen; [readOnly] leaves out the buttons, for
 * the history, which is for reading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderCard(
    order: StaffOrder,
    now: Instant,
    prep: Map<Long, Int>,
    prefs: DevicePrefs,
    pending: Pending?,
    busy: Boolean,
    canPrint: Boolean,
    actions: OrderActions,
    modifier: Modifier = Modifier,
    /** Busy mode's minutes, added to the pre-selected accept button only — as is the kitchen's backlog. */
    busyMinutes: Int = 0,
    focused: Boolean = false,
    compact: Boolean = false,
    readOnly: Boolean = false,
    /** Whether the database knows "Fertig": without it, no button for it. */
    packing: Boolean = true,
    /** The customer reported a problem with it ("Reklamation", hamza-bistro-web#88): in the history. */
    reported: Boolean = false,
    /** The customer's rating ("Wie war's?", hamza-bistro-web#89): in the history. */
    rating: OrderRating? = null,
) {
    val context = LocalContext.current
    val estimate = Eta.estimate(order, prep)
    val isNew = order.status == OrderStatus.NEW
    Card(
        modifier = modifier.fillMaxWidth(),
        border =
            when {
                focused -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                isNew -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
                else -> null
            },
        colors =
            if (isNew) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            else CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${order.orderNumber}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Badge(statusLabel(context, order), MaterialTheme.colorScheme.secondaryContainer)
                order.scheduledFor?.let {
                    Badge(stringResource(R.string.preorder_badge, Format.slot(context, it, now)), MaterialTheme.colorScheme.tertiaryContainer)
                }
                // Typed in on the tablet (#9): "Telefon" or "Vor Ort / Theke".
                sourceLabel(context, order)?.let { Badge(it, MaterialTheme.colorScheme.tertiaryContainer) }
                when {
                    order.dineIn -> Badge(stringResource(R.string.dine_in_badge), MaterialTheme.colorScheme.primaryContainer)
                    order.pickup -> Badge(stringResource(R.string.pickup_badge), MaterialTheme.colorScheme.primaryContainer)
                }
                if (order.feesWaived) Badge(stringResource(R.string.fees_waived_badge), MaterialTheme.colorScheme.secondaryContainer)
                if (reported) Badge(stringResource(R.string.issue_had_report), MaterialTheme.colorScheme.errorContainer)
                // "★★☆☆☆": one or two stars in the colour of a "Reklamation".
                rating?.let {
                    val label = stringResource(R.string.rating_label, it.stars)
                    Badge(
                        OrderRatings.starsText(it.stars),
                        if (OrderRatings.isLow(it)) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        Modifier.semantics { contentDescription = label },
                    )
                }
                Text(
                    stringResource(R.string.ordered_at, Format.clock(order.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            if (order.status.open) {
                timing(context, order, estimate, now)?.let {
                    Text(it.text, color = toneColor(it.tone), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                packedTiming(context, order, now)?.let {
                    Text(it.text, color = toneColor(it.tone), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            } else if (StaffQueue.isDelayed(order)) {
                // In the history: it was late, and the customer was told so.
                StaffQueue.promisedAt(order)?.let {
                    Text(dueText(context, order, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Line by line as placed: two Döner with different notes are two lines.
                for (item in order.items) {
                    Text(
                        buildString {
                            append("${item.qty}× ${item.name}")
                            if (item.options.isNotEmpty()) append(" — ").append(item.options.joinToString(", "))
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    item.dishNote?.let { DishNote(it) }
                }
            }

            if (order.notes.isNotBlank()) {
                Note(stringResource(R.string.kitchen_note), order.notes, MaterialTheme.colorScheme.errorContainer)
            }

            rating?.let(OrderRatings::comment)?.let {
                Text(stringResource(R.string.rating_comment, it), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            }

            Text(
                buildString {
                    append(stringResource(R.string.collect, Format.euro(order.total)))
                    if (order.fees > 0) append(" (").append(stringResource(R.string.fee_label, Format.euro(order.fees))).append(")")
                    if (order.depositTotal > 0) {
                        append(" (").append(stringResource(R.string.deposit_label, Format.euro(order.depositTotal))).append(")")
                    }
                    if (order.pickupDiscount > 0) {
                        append(" (").append(stringResource(R.string.pickup_discount_label, Format.euro(order.pickupDiscount))).append(")")
                    }
                    if (order.discount > 0) {
                        append(" (").append(stringResource(R.string.discount_label, order.discountCode ?: "", Format.euro(order.discount))).append(")")
                    }
                    if (order.stampDiscount > 0) {
                        append(" (").append(stringResource(R.string.stamp_label, Format.euro(order.stampDiscount))).append(")")
                    }
                },
                style = MaterialTheme.typography.titleSmall,
            )

            if (!compact) Contact(order, actions)

            if (order.status == OrderStatus.DELIVERED) {
                Text(stringResource(R.string.paid_with, paymentLabel(context, order.paymentMethod)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (order.status == OrderStatus.CANCELLED && order.cancelReason != null) {
                Text(stringResource(R.string.cancel_reason_shown, reasonLabel(context, order.cancelReason)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!compact && NoShows.canReset(order)) ResetNoShows(order, actions)

            if (order.status == OrderStatus.CONFIRMED) PrintLine(order, now)

            if (order.status.open && !readOnly) {
                Actions(order, Eta.estimate(order, prep, busyMinutes, now), prefs, pending, busy, canPrint && !compact, packing, actions)
            }
        }
    }
}

@Composable
internal fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
internal fun Note(label: String, text: String, color: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(8.dp)).padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * "ohne Zwiebeln", under its dish: the order note's highlight, a bar on the
 * left and no label — .order-item-note on the site. Wrapped, never cut.
 */
@Composable
private fun DishNote(text: String) {
    val bar = MaterialTheme.colorScheme.error
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier =
            Modifier.padding(start = 16.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }
                .padding(start = 9.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun Contact(order: StaffOrder, actions: OrderActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A walk-in eaten here may have given neither.
        if (order.customerName.isNotBlank() || order.phone.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (order.customerName.isNotBlank()) {
                    Text(order.customerName + if (order.phone.isNotBlank()) " · " else "", style = MaterialTheme.typography.bodyLarge)
                }
                if (order.phone.isNotBlank()) {
                    Text(
                        order.phone,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { actions.call(order.phone) },
                    )
                }
            }
        }
        // Nothing is paid online: the call is the only check that somebody wants the food.
        if (order.newCustomer && order.status == OrderStatus.NEW) {
            Text(stringResource(R.string.new_customer), color = toneColor(Tone.SOON), fontWeight = FontWeight.SemiBold)
        }
        // A delivery to this account has failed before (#83): like the first-order flag.
        NoShows.flag(order)?.let {
            Text(stringResource(R.string.no_shows_before, it), color = toneColor(Tone.SOON), fontWeight = FontWeight.SemiBold)
        }
        if (!order.pickup) {
            Text(
                StaffQueue.addressLine(order),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { actions.route(order) },
            )
            StaffQueue.doorNote(order)?.let { Note(stringResource(R.string.door_note), it, MaterialTheme.colorScheme.surfaceVariant) }
            if (order.unverifiedAddress) {
                Text(stringResource(R.string.unverified_address), color = toneColor(Tone.SOON), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Whether its ticket came out: the kitchen's check that nothing was missed. */
@Composable
private fun PrintLine(order: StaffOrder, now: Instant) {
    val printed = order.printedAt
    when {
        printed != null ->
            Text(stringResource(R.string.ticket_printed, Format.clock(printed)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        order.confirmedAt?.let { Duration.between(it, now) > Duration.ofMinutes(2) } == true ->
            Text(stringResource(R.string.ticket_not_printed), style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.SOON))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    order: StaffOrder,
    estimate: Int,
    prefs: DevicePrefs,
    pending: Pending?,
    busy: Boolean,
    canPrint: Boolean,
    packing: Boolean,
    actions: OrderActions,
) {
    val context = LocalContext.current
    var declining by rememberSaveable(order.id) { mutableStateOf(false) }
    var noShowAsking by rememberSaveable(order.id) { mutableStateOf(false) }

    if (pending != null) {
        // Tapped, not yet sent. The bar runs down over the undo window.
        var started by remember(pending) { mutableStateOf(false) }
        val left by animateFloatAsState(
            targetValue = if (started) 0f else 1f,
            animationSpec = tween(durationMillis = pending.seconds * 1000, easing = LinearEasing),
            label = "undo",
        )
        LaunchedEffect(pending) { started = true }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pending_sending, stepLabel(context, order, pending.step)), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { actions.undo(order) }) { Text(stringResource(R.string.undo)) }
            }
            LinearProgressIndicator(progress = { left }, modifier = Modifier.fillMaxWidth())
        }
        return
    }

    if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        return
    }

    if (declining) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cancel_why), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (reason in CancelReason.CHOSEN + listOf(null)) {
                    OutlinedButton(
                        onClick = {
                            declining = false
                            actions.cancel(order, reason)
                        },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(reasonLabel(context, reason))
                    }
                }
                TextButton(onClick = { declining = false }) { Text(stringResource(R.string.cancel_back)) }
            }
        }
        return
    }

    if (noShowAsking) {
        NoShowConfirm(order, actions) { noShowAsking = false }
        return
    }

    val scheduled = order.scheduledFor
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            order.status == OrderStatus.NEW && scheduled != null ->
                // A pre-order already has its time; the only question is whether it can be kept.
                Button(onClick = { actions.acceptScheduled(order) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.accept_scheduled, Format.slot(context, scheduled)))
                }
            order.status == OrderStatus.NEW -> {
                Text(
                    stringResource(if (order.pickup) R.string.accept_pickup_in else R.string.accept_delivery_in),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // One tap accepts; the filled one is the estimate from the dishes and the ring.
                    for (minutes in Eta.options(estimate, prefs.etaLadder)) {
                        val label = stringResource(R.string.eta_button, minutes)
                        if (minutes == estimate) {
                            Button(onClick = { actions.accept(order, minutes) }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { actions.accept(order, minutes) }) { Text(label) }
                        }
                    }
                }
            }
            Payment.asks(order) -> {
                // Delivered and paid in one tap: "Bar" or "Karte", for the
                // Kassensturz. The undo window takes back the wrong one.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (order.pickup) R.string.step_collected_paid else R.string.step_delivered_paid),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    for (method in PaymentMethod.DOOR) {
                        Button(onClick = { actions.deliver(order, method) }, modifier = Modifier.weight(1f)) {
                            Text(paymentLabel(context, method))
                        }
                    }
                }
                DelayButtons(order, actions)
            }
            else -> {
                // "Fertig" first, filled, while the bag is not packed; "Losfahren"
                // stays, outlined — a shop where the cook rides skips "Fertig".
                val canPack = packing && StaffQueue.canPack(order)
                if (canPack) {
                    Button(onClick = { actions.pack(order) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stepLabel(context, order, OrderStep.Pack))
                    }
                }
                val next = stepLabel(context, order, if (order.status == OrderStatus.CONFIRMED) OrderStep.Out else OrderStep.Done())
                if (canPack) {
                    OutlinedButton(onClick = { actions.moveOn(order) }, modifier = Modifier.fillMaxWidth()) { Text(next) }
                } else {
                    Button(onClick = { actions.moveOn(order) }, modifier = Modifier.fillMaxWidth()) { Text(next) }
                }
                if (packing && StaffQueue.isPacked(order)) {
                    TextButton(onClick = { actions.unpack(order) }) { Text(stepLabel(context, order, OrderStep.Unpack)) }
                }
                DelayButtons(order, actions)
            }
        }
        if (NoShows.canMark(order)) NoShowButton { noShowAsking = true }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canPrint) TextButton(onClick = { actions.print(order) }) { Text(stringResource(R.string.print_ticket)) }
            TextButton(
                onClick = { declining = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (order.status == OrderStatus.NEW) R.string.decline else R.string.cancel_later))
            }
        }
    }
}

/** "+5 Min.", "+10 Min.": the kitchen or the driver is behind — say so before the customer has to ring and ask. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DelayButtons(order: StaffOrder, actions: OrderActions) {
    val steps = StaffQueue.DELAY_STEPS.filter { StaffQueue.canDelay(order, it) }
    if (steps.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (minutes in steps) {
            OutlinedButton(onClick = { actions.delay(order, minutes) }) {
                Text(stringResource(R.string.delay_button, minutes))
            }
        }
    }
}

/** "Nicht angetroffen", next to Geliefert: the first tap only asks ([NoShowConfirm]). */
@Composable
internal fun NoShowButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.no_show))
    }
}

/**
 * "Angerufen und ein paar Minuten gewartet?": asked once, because it counts
 * against the account, with the call a tap away. "Ja" goes through the undo
 * window like any step.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoShowConfirm(order: StaffOrder, actions: OrderActions, onClose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.no_show_confirm, order.orderNumber), color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onClose()
                    actions.noShow(order)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) {
                Text(stringResource(R.string.no_show_yes))
            }
            if (order.phone.isNotBlank()) {
                OutlinedButton(onClick = { actions.call(order.phone) }) { Text(stringResource(R.string.driver_call)) }
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.cancel_back)) }
        }
    }
}

/** "Zähler zurücksetzen" on a flagged order or the no-show itself, and how it went. */
@Composable
private fun ResetNoShows(order: StaffOrder, actions: OrderActions) {
    var result by remember(order.id) { mutableStateOf<NoShowReset?>(null) }
    var resetting by remember(order.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val shown = result) {
            is NoShowReset.Done ->
                Text(stringResource(R.string.no_shows_reset_done, shown.before), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else ->
                TextButton(
                    onClick = {
                        resetting = true
                        actions.resetNoShows(order) {
                            resetting = false
                            result = it
                        }
                    },
                    enabled = !resetting,
                ) {
                    Text(stringResource(R.string.no_shows_reset))
                }
        }
    }
    (result as? NoShowReset.Failed)?.let { Text(it.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
