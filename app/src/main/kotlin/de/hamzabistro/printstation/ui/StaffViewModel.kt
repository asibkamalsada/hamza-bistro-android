package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.core.AlarmDecision
import de.hamzabistro.printstation.core.CancelReason
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.OrderStep
import de.hamzabistro.printstation.core.QueueState
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.queue.Pending
import de.hamzabistro.printstation.queue.StepFailure
import de.hamzabistro.printstation.station.DevicePrefs
import de.hamzabistro.printstation.station.ShiftService
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the queue screen draws. */
data class StaffState(
    val queue: QueueState = QueueState(),
    val prefs: DevicePrefs = DevicePrefs(),
    val pending: Map<String, Pending> = emptyMap(),
    val busy: Set<String> = emptySet(),
    val alarm: AlarmDecision = AlarmDecision(),
    /** The clock the cards read, moved on every few seconds. */
    val now: Instant = Instant.now(),
    /** Whether this device has a printer to print a ticket on by hand. */
    val canPrint: Boolean = false,
)

/** What happened that the screen should say once. */
sealed interface StaffEvent {
    data class Step(val failure: StepFailure) : StaffEvent

    data class Printed(val order: Long) : StaffEvent

    data class PrintFailed(val reason: String) : StaffEvent
}

/**
 * The queue for a staff account: read while the screen is on, as /orders
 * reads it, with the steps that move an order on. The same queue the shift
 * service rings from, so the screen and the alarm never disagree.
 */
class StaffViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph: AppGraph = (application as PrintStationApp).graph

    private val ticks: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(TICK)
        }
    }

    private val parts =
        combine(graph.liveQueue, graph.settings.device, graph.steps.pending, graph.steps.busy) { queue, prefs, pending, busy ->
            StaffState(queue = queue, prefs = prefs, pending = pending, busy = busy)
        }

    /** Read only while the screen collects it: the poll stops a few seconds after it goes. */
    val state: StateFlow<StaffState> =
        combine(parts, graph.alarm, ticks) { state, alarm, now ->
                state.copy(alarm = alarm, now = now, canPrint = graph.settings.printer != null)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StaffState())

    private val _events = MutableStateFlow<StaffEvent?>(null)
    val events: StateFlow<StaffEvent?> = _events.asStateFlow()

    init {
        viewModelScope.launch { graph.steps.failures.collect { _events.value = StaffEvent.Step(it) } }
    }

    fun eventShown() {
        _events.value = null
    }

    fun suggestedEta(order: StaffOrder): Int = graph.suggestedEta(order, state.value.queue.prep)

    fun accept(order: StaffOrder, minutes: Int) = graph.steps.take(order, OrderStep.Accept(minutes))

    fun acceptScheduled(order: StaffOrder) = graph.steps.take(order, OrderStep.AcceptScheduled)

    /** Out of the door or onto the counter; then delivered or collected. */
    fun moveOn(order: StaffOrder) =
        graph.steps.take(order, if (order.status == OrderStatus.CONFIRMED) OrderStep.Out else OrderStep.Done)

    fun cancel(order: StaffOrder, reason: CancelReason?) = graph.steps.take(order, OrderStep.Cancel(reason))

    fun undo(order: StaffOrder) = graph.steps.undo(order.id)

    /** The screen went away: what waits on its undo window goes now, as on /orders. */
    fun flush() = graph.steps.flushAll()

    fun refresh() = graph.queue.refresh()

    fun silence() = graph.silence()

    fun print(order: StaffOrder) {
        viewModelScope.launch {
            try {
                graph.printByHand(order)
                _events.value = StaffEvent.Printed(order.orderNumber)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.value = StaffEvent.PrintFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Starts or ends the shift here. Ending it takes this device off "Wer
     * von Bestellungen erfährt" on the site at once, rather than when it
     * would have dropped off by itself.
     */
    fun setOnShift(on: Boolean) {
        graph.settings.update { it.copy(onShift = on) }
        ShiftService.update(app)
        if (!on) graph.scope.launch { runCatching { graph.staff.off(graph.settings.stationId) } }
    }

    fun updatePrefs(change: (DevicePrefs) -> DevicePrefs) = graph.settings.update(change)

    /** Three seconds of the alarm, to hear what it will sound like. */
    fun testSound() {
        val prefs = graph.settings.device.value
        if (graph.alarmPlayer.looping) return
        graph.alarmPlayer.startLoop(prefs.sound, prefs.fullVolume, prefs.vibrate)
        graph.scope.launch {
            delay(TEST_SOUND)
            // Unless an order started ringing meanwhile.
            if (graph.alarm.value.ringing.isEmpty()) graph.alarmPlayer.stopLoop()
        }
    }

    private companion object {
        val TICK = 5.seconds
        val TEST_SOUND = 3.seconds
    }
}
