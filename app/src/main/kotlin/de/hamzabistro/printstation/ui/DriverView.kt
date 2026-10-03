package de.hamzabistro.printstation.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Driver
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.OrderStep
import de.hamzabistro.printstation.core.Payment
import de.hamzabistro.printstation.core.PaymentMethod
import de.hamzabistro.printstation.core.Ring
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.core.StaffQueue
import de.hamzabistro.printstation.queue.Pending

/**
 * "Fahrer" (#8): the bags in the kitchen to tick and take along in one tap,
 * and the stops on the way in the order they are ridden, with one link to
 * the map for the lot. The same steps as the cards of the queue, each with
 * its own undo window.
 */
@Composable
internal fun DriverView(
    state: StaffState,
    chosen: Set<String>,
    stopOrder: List<String>,
    viewModel: StaffViewModel,
    actions: Actions,
) {
    val orders = state.queue.orders
    val collect = Driver.toCollect(orders)
    val stops = Driver.stops(orders, stopOrder)
    // A stop whose "Geliefert" waits on its undo window is as good as done: the route leaves it out.
    val riding = stops.filter { it.id !in state.pending && it.id !in state.busy }
    val taking = Driver.batch(chosen, orders).filter { it.id !in state.pending && it.id !in state.busy }

    val collectHeading = "${stringResource(R.string.driver_collect)} · ${collect.size}"
    val stopsHeading = "${stringResource(R.string.status_on_the_way)} · ${stops.size}"

    Column(Modifier.fillMaxSize()) {
        if (collect.isEmpty() && stops.isEmpty()) {
            Text(
                stringResource(R.string.driver_empty),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (collect.isNotEmpty()) {
                heading("collect", collectHeading)
                item(key = "collect-hint") {
                    Text(stringResource(R.string.driver_collect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(collect, key = { _, it -> "collect-${it.id}" }) { _, order ->
                    CollectCard(order, state, order.id in chosen, viewModel, actions)
                }
            }
            if (stops.isNotEmpty()) {
                heading("stops", stopsHeading)
                item(key = "route") { Route(riding, state, actions) }
                itemsIndexed(stops, key = { _, it -> "stop-${it.id}" }) { index, order ->
                    StopCard(index, stops.size, order, state, viewModel, actions)
                }
            }
        }
        if (taking.isNotEmpty()) {
            Button(
                onClick = viewModel::takeAlong,
                modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.driver_take, taking.size), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun LazyListScope.heading(key: String, text: String) {
    item(key = "heading-$key") {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

/** A bag in the kitchen: tick it to take it along. */
@Composable
private fun CollectCard(order: StaffOrder, state: StaffState, ticked: Boolean, viewModel: StaffViewModel, actions: Actions) {
    val context = LocalContext.current
    val pending = state.pending[order.id]
    val busy = order.id in state.busy
    val free = pending == null && !busy
    Card(
        modifier =
            Modifier.fillMaxWidth().toggleable(value = ticked, enabled = free, role = Role.Checkbox, onValueChange = { viewModel.choose(order) }),
        border = if (ticked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (free) Checkbox(checked = ticked, onCheckedChange = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Title(order)
                timing(context, order, Eta.estimate(order, state.queue.prep), state.now)?.let {
                    Text(it.text, color = toneColor(it.tone), fontWeight = FontWeight.SemiBold)
                }
                order.packedAt?.let {
                    Text(stringResource(R.string.driver_packed_at, Format.clock(it)), color = toneColor(Tone.SOON), fontWeight = FontWeight.SemiBold)
                }
                Where(order)
                when {
                    pending != null -> PendingLine(order, pending, actions)
                    busy -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** A stop on the way: where, what to collect, the call, and "Bar" / "Karte" at the door. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopCard(index: Int, count: Int, order: StaffOrder, state: StaffState, viewModel: StaffViewModel, actions: Actions) {
    val context = LocalContext.current
    val pending = state.pending[order.id]
    val busy = order.id in state.busy
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${index + 1}.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Title(order)
                Where(order, onRoute = { actions.route(order) })
                when {
                    pending != null -> PendingLine(order, pending, actions)
                    busy -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (order.phone.isNotBlank()) {
                                OutlinedButton(onClick = { actions.call(order.phone) }) { Text(stringResource(R.string.driver_call)) }
                            }
                            if (Payment.asks(order)) {
                                for (method in PaymentMethod.DOOR) {
                                    Button(onClick = { actions.deliver(order, method) }) { Text(paymentLabel(context, method)) }
                                }
                            } else {
                                Button(onClick = { actions.moveOn(order) }) { Text(stepLabel(context, order, OrderStep.Done())) }
                            }
                        }
                }
            }
            // ↑ / ↓ rather than dragging: one hand, a bag in the other.
            Column {
                TextButton(onClick = { viewModel.moveStop(order, -1) }, enabled = index > 0) {
                    Text("↑", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = { viewModel.moveStop(order, 1) }, enabled = index < count - 1) {
                    Text("↓", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** "#57", and the ring it goes to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Title(order: StaffOrder) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("#${order.orderNumber}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Ring.of(order)?.let { Badge(ringLabel(it), MaterialTheme.colorScheme.secondaryContainer) }
    }
}

/** The street, the note for the door, and the money: what the driver needs on the doorstep. */
@Composable
private fun Where(order: StaffOrder, onRoute: (() -> Unit)? = null) {
    Text(
        StaffQueue.addressLine(order),
        style = MaterialTheme.typography.bodyLarge,
        color = if (onRoute != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = if (onRoute != null) Modifier.clickable(onClick = onRoute) else Modifier,
    )
    StaffQueue.doorNote(order)?.let { Note(stringResource(R.string.door_note), it, MaterialTheme.colorScheme.surfaceVariant) }
    Text(
        if (order.paymentMethod == PaymentMethod.ONLINE) stringResource(R.string.driver_paid)
        else stringResource(R.string.collect, Format.euro(order.total)),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * "Route öffnen": in Google Maps every stop of a trip in one link, the trip
 * cut in parts past three waypoints; in an app that takes one destination,
 * the next stop only.
 */
@Composable
private fun Route(riding: List<StaffOrder>, state: StaffState, actions: Actions) {
    if (riding.isEmpty()) return
    val prefs = state.prefs
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!Driver.multiStop(prefs.navApp)) {
            val next = riding.first()
            Button(onClick = { actions.link(Driver.routeUrl(listOf(next), prefs.navApp, prefs.travelMode)) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.route_next, next.orderNumber, StaffQueue.addressLine(next)))
            }
            return@Column
        }
        val trips = Driver.trips(riding)
        if (trips.size == 1) {
            Button(onClick = { actions.link(Driver.googleRouteUrl(trips[0], prefs.travelMode)) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.route_open))
            }
            return@Column
        }
        Text(
            stringResource(R.string.route_split, Driver.STOPS_PER_TRIP, trips.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var first = 1
        for ((i, trip) in trips.withIndex()) {
            val from = first
            first += trip.size
            val label = stringResource(R.string.route_open_part, i + 1, trips.size, from, from + trip.size - 1)
            val open = { actions.link(Driver.googleRouteUrl(trip, prefs.travelMode)) }
            // The first part is the one to ride now; the rest wait their turn.
            if (i == 0) Button(onClick = open, modifier = Modifier.fillMaxWidth()) { Text(label) }
            else OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }
}

/** Tapped, not yet sent: the bar runs down over the undo window, as on a queue card. */
@Composable
private fun PendingLine(order: StaffOrder, pending: Pending, actions: Actions) {
    val context = LocalContext.current
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
}

@Composable
private fun ringLabel(ring: Ring): String =
    stringResource(
        when (ring) {
            Ring.INNER -> R.string.report_ring_inner
            Ring.NEAR -> R.string.report_ring_near
            Ring.FAR -> R.string.report_ring_far
            Ring.EDGE -> R.string.report_ring_edge
        }
    )
