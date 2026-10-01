package de.hamzabistro.printstation.alarm

import android.content.Context
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.core.AlarmDecision
import de.hamzabistro.printstation.core.Chime
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.Problem
import de.hamzabistro.printstation.core.QueueState
import de.hamzabistro.printstation.station.DevicePrefs
import de.hamzabistro.printstation.station.StationNotifications
import de.hamzabistro.printstation.station.StationState
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

/**
 * Turns the queue into noise, from the shift service: asks the [AlarmPolicy]
 * of the graph what to do every few seconds and whenever anything changes,
 * then rings, stops, or chimes, and shows the notifications that go with it.
 *
 * An order with a step on its undo window, or one just sent, is as good as
 * answered: it does not ring while the step is on its way.
 */
class AlarmController(private val context: Context, private val graph: AppGraph) {
    private var shown: Set<String> = emptySet()

    private class Inputs(
        val queue: QueueState,
        val printer: Problem?,
        val prefs: DevicePrefs,
        val handled: Set<String>,
    )

    suspend fun run(queue: Flow<QueueState>) {
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(TICK)
            }
        }
        val inputs =
            combine(queue, graph.stationState, graph.settings.device, graph.steps.busy, graph.steps.pending) {
                q,
                station,
                prefs,
                busy,
                pending ->
                Inputs(q, (station as? StationState.Running)?.status?.problem, prefs, busy + pending.keys)
            }
        try {
            combine(inputs, graph.alarmPoke, ticks) { now, _, _ -> now }.collect(::act)
        } finally {
            graph.alarmPlayer.stopLoop()
            StationNotifications.cancelAlarm(context)
            graph.alarm.value = AlarmDecision()
            shown = emptySet()
        }
    }

    private fun act(inputs: Inputs) {
        val queue = inputs.queue
        val prefs = inputs.prefs
        val orders = if (queue.loaded && !queue.notAllowed) queue.orders.filterNot { it.id in inputs.handled } else null
        val decision =
            graph.alarmPolicy.decide(orders, queue.failingSince != null, inputs.printer, prefs.alarm, Instant.now()) {
                Eta.estimate(it, queue.prep)
            }
        graph.alarm.value = decision

        val ringing = decision.ringing.map { it.id }.toSet()
        if (ringing.isNotEmpty()) {
            graph.alarmPlayer.startLoop(prefs.sound, prefs.fullVolume, prefs.vibrate)
            // Up again, over whatever is on the screen, when an order joined.
            if (ringing != shown) StationNotifications.alarm(context, decision.ringing, alert = !shown.containsAll(ringing))
        } else {
            graph.alarmPlayer.stopLoop()
            if (shown.isNotEmpty()) StationNotifications.cancelAlarm(context)
        }
        shown = ringing

        for (chime in decision.chimes) {
            StationNotifications.chime(context, chime, decision.quiet)
            // A message makes its channel's sound; the kitchen's chimes are
            // the alarm's own, on the alarm stream.
            if (!decision.quiet && chime !is Chime.NewOrder) {
                graph.alarmPlayer.chime(prefs.sound, prefs.fullVolume, prefs.vibrate)
            }
        }

        // An order answered anywhere takes its notification with it; a
        // pre-order keeps its "jetzt kochen" until it is on its way.
        if (orders != null) {
            StationNotifications.clearOrders(
                context,
                orders.filter { it.status == OrderStatus.NEW || (it.status == OrderStatus.CONFIRMED && it.scheduledFor != null) },
            )
        }
    }

    private companion object {
        val TICK = 5.seconds
    }
}
