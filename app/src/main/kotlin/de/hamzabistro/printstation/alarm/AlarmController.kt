package de.hamzabistro.printstation.alarm

import android.content.Context
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.core.AlarmDecision
import de.hamzabistro.printstation.core.Chime
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.IssuesState
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.Problem
import de.hamzabistro.printstation.core.QueueState
import de.hamzabistro.printstation.core.RatingsState
import de.hamzabistro.printstation.core.StaffQueue
import de.hamzabistro.printstation.station.DevicePrefs
import de.hamzabistro.printstation.station.StationNotifications
import de.hamzabistro.printstation.station.StationState
import de.hamzabistro.printstation.station.alarmForDevice
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
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

    private class Step(val inputs: Inputs, val printingSince: Instant?, val issues: IssuesState, val ratings: RatingsState)

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
            val customers = combine(graph.liveIssues, graph.liveRatings) { issues, ratings -> issues to ratings }
            combine(inputs, graph.alarmPoke, ticks, printingSince(), customers) { read, _, _, since, (issues, ratings) ->
                    Step(read, since, issues, ratings)
                }
                .collect { act(it.inputs, it.printingSince, it.issues, it.ratings) }
        } finally {
            graph.alarmPlayer.stopLoop()
            StationNotifications.cancelAlarm(context)
            StationNotifications.clearIssues(context)
            graph.alarm.value = AlarmDecision()
            shown = emptySet()
        }
    }

    /**
     * Since when some device has been meant to print every accepted order —
     * the earliest print station's registration — read now and every couple
     * of minutes; null until known, and when there is none. A failed read
     * keeps what was known.
     */
    private fun printingSince(): Flow<Instant?> = flow {
        var known: Instant? = null
        emit(known)
        while (true) {
            try {
                known = graph.devices.printStations(graph.settings.stationId).mapNotNull { it.registeredAt }.minOrNull()
                emit(known)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.logger.warn("Reading the print stations failed: ${e.javaClass.simpleName}")
            }
            delay(STATIONS_POLL)
        }
    }

    private fun act(inputs: Inputs, printingSince: Instant?, issues: IssuesState, ratings: RatingsState) {
        val queue = inputs.queue
        val prefs = inputs.prefs
        val orders = if (queue.loaded && !queue.notAllowed) queue.orders.filterNot { it.id in inputs.handled } else null
        // A device that prints by itself hears about its own printer, more
        // precisely and sooner; it does not also chime about the same ticket.
        val elsewhere = printingSince.takeUnless { graph.settings.enabled }
        val decision =
            graph.alarmPolicy.decide(
                orders,
                queue.failingSince != null,
                inputs.printer,
                prefs.alarmForDevice,
                Instant.now(),
                elsewhere,
                issues.ids.takeIf { issues.available == true },
                ratings.recent.takeIf { ratings.available == true },
            ) {
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
            // the alarm's own, on the alarm stream. Two minutes before an
            // order is declined unanswered, a sound of its own, over the loop.
            when {
                decision.quiet || chime is Chime.NewOrder -> Unit
                chime is Chime.DecliningSoon -> graph.alarmPlayer.escalate(prefs.fullVolume, prefs.vibrate)
                else -> graph.alarmPlayer.chime(prefs.sound, prefs.fullVolume, prefs.vibrate)
            }
        }

        // Every report answered, here or elsewhere: "Neue Reklamation" goes.
        if (issues.available == true && issues.count == 0) StationNotifications.clearIssues(context)

        // An order answered anywhere takes its notification with it; a
        // pre-order keeps its "jetzt kochen", and a packed bag its "ist
        // fertig", until it is on its way.
        if (orders != null) {
            StationNotifications.clearOrders(
                context,
                orders.filter {
                    it.status == OrderStatus.NEW ||
                        (it.status == OrderStatus.CONFIRMED && it.scheduledFor != null) ||
                        StaffQueue.isPacked(it)
                },
            )
        }
    }

    private companion object {
        val TICK = 5.seconds

        /** Print stations come and go rarely; the "unprinted" chime waits two minutes anyway. */
        val STATIONS_POLL = 2.minutes
    }
}
