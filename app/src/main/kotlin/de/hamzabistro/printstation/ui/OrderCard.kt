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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AutoDecline
import de.hamzabistro.printstation.core.CancelReason
import de.hamzabistro.printstation.core.DeclineUrgency
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.OrderStep
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

    fun cancel(order: StaffOrder, reason: CancelReason?)

    fun undo(order: StaffOrder)

    fun print(order: StaffOrder)

    fun call(phone: String)

    fun route(order: StaffOrder)
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
    val dueText = context.getString(R.string.due_at, Format.clock(due))
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
            OrderStatus.CONFIRMED -> R.string.status_confirmed
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
            null -> R.string.reason_none
        }
    )

/** What a step says while it waits on its undo window. */
fun stepLabel(context: Context, order: StaffOrder, step: OrderStep): String =
    when (step) {
        is OrderStep.Accept -> context.getString(R.string.pending_accepted, step.etaMinutes)
        OrderStep.AcceptScheduled ->
            context.getString(R.string.accept_scheduled, Format.slot(context, order.scheduledFor ?: Instant.now()))
        OrderStep.Out -> context.getString(if (order.pickup) R.string.step_ready else R.string.step_on_the_way)
        OrderStep.Done -> context.getString(if (order.pickup) R.string.step_collected else R.string.step_delivered)
        is OrderStep.Cancel ->
            context.getString(if (order.status == OrderStatus.NEW) R.string.pending_declined else R.string.pending_cancelled)
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
    /** Busy mode's minutes, added to the pre-selected accept button only. */
    busyMinutes: Int = 0,
    focused: Boolean = false,
    compact: Boolean = false,
    readOnly: Boolean = false,
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
                if (order.pickup) Badge(stringResource(R.string.pickup_badge), MaterialTheme.colorScheme.primaryContainer)
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
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (item in order.items) {
                    Text(
                        buildString {
                            append("${item.qty}× ${item.name}")
                            if (item.options.isNotEmpty()) append(" — ").append(item.options.joinToString(", "))
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            if (order.notes.isNotBlank()) {
                Note(stringResource(R.string.kitchen_note), order.notes, MaterialTheme.colorScheme.errorContainer)
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

            if (order.status == OrderStatus.CANCELLED && order.cancelReason != null) {
                Text(stringResource(R.string.cancel_reason_shown, reasonLabel(context, order.cancelReason)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (order.status == OrderStatus.CONFIRMED) PrintLine(order, now)

            if (order.status.open && !readOnly) {
                Actions(order, Eta.estimate(order, prep, busyMinutes), prefs, pending, busy, canPrint && !compact, actions)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Note(label: String, text: String, color: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(8.dp)).padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Contact(order: StaffOrder, actions: OrderActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${order.customerName} · ", style = MaterialTheme.typography.bodyLarge)
            Text(
                order.phone,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { actions.call(order.phone) },
            )
        }
        // Nothing is paid online: the call is the only check that somebody wants the food.
        if (!order.returningCustomer && order.status == OrderStatus.NEW) {
            Text(stringResource(R.string.new_customer), color = toneColor(Tone.SOON), fontWeight = FontWeight.SemiBold)
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
    actions: OrderActions,
) {
    val context = LocalContext.current
    var declining by rememberSaveable(order.id) { mutableStateOf(false) }

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
            else ->
                Button(onClick = { actions.moveOn(order) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stepLabel(context, order, if (order.status == OrderStatus.CONFIRMED) OrderStep.Out else OrderStep.Done))
                }
        }
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
