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
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.QueueState
import de.hamzabistro.printstation.core.ShopHours
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.queue.Pending
import de.hamzabistro.printstation.queue.StepFailure
import de.hamzabistro.printstation.station.DevicePrefs
import de.hamzabistro.printstation.station.ShiftService
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
import kotlinx.coroutines.flow.merge
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
    /** Whether customers can order right now. */
    val shop: ShopView = ShopView(),
    /** The delivery address check is failing: orders are priced on what the customer typed. */
    val addressFailing: Boolean = false,
)

/** What happened that the screen should say once. */
sealed interface StaffEvent {
    data class Step(val failure: StepFailure) : StaffEvent

    data class Printed(val order: Long) : StaffEvent

    data class PrintFailed(val reason: String) : StaffEvent

    /** Opening or closing the shop did not go through. */
    data class ShopFailed(val reason: String) : StaffEvent

    /** Orders already booked for a time inside a closure just made: they stand. */
    data class PreordersInside(val count: Int) : StaffEvent
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

    /** Shared with the hours screen, which changes it too. */
    private val shop = graph.shopView

    private val addressFailing = MutableStateFlow(false)

    /** Reads whether the shop is open, now and then for as long as it is collected. */
    private val shopPoll: Flow<ShopView> =
        merge(
            flow<Nothing> {
                while (true) {
                    refreshShop()
                    delay(SHOP_POLL)
                }
            },
            shop,
        )

    /** Read only while the screen collects it: the poll stops a few seconds after it goes. */
    val state: StateFlow<StaffState> =
        combine(parts, graph.alarm, ticks, shopPoll, addressFailing) { state, alarm, now, shop, address ->
                state.copy(alarm = alarm, now = now, canPrint = graph.settings.printer != null, shop = shop, addressFailing = address)
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

    /** The queue now, and whether the address check works — on every return to the screen. */
    fun refresh() {
        graph.queue.refresh()
        viewModelScope.launch { checkAddress() }
    }

    /**
     * One line above the queue when the address check fails; the detail is
     * in the settings. Unknown is not failing, so a failed read says nothing.
     */
    private suspend fun checkAddress() {
        try {
            addressFailing.value = !graph.devices.addressHealth().verdict().ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.logger.warn("Reading the address check failed: ${e.javaClass.simpleName}")
        }
    }

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

    // -------------------------------------------------------------------
    // The shop: open, paused, closed
    // -------------------------------------------------------------------

    /** Closed for [minutes] from now, rounded up to five: "wieder ab 18:40", not 18:37. */
    fun pauseShop(minutes: Long) {
        val step = Duration.ofMinutes(PAUSE_ROUNDING).toMillis()
        val until = Instant.now().plus(minutes, ChronoUnit.MINUTES).toEpochMilli()
        closeShop(Instant.ofEpochMilli((until + step - 1) / step * step))
    }

    /** Closed until midnight in Leipzig: tomorrow opens as usual. */
    fun closeShopForToday() =
        closeShop(
            Instant.now().atZone(AlarmPolicy.LEIPZIG).toLocalDate().plusDays(1).atStartOfDay(AlarmPolicy.LEIPZIG).toInstant()
        )

    /** Closed until somebody opens again — here, on another device, or on the website. */
    fun closeShopForGood() = closeShop(null)

    fun openShop() = switchShop { graph.shop.open() }

    private fun closeShop(until: Instant?) = switchShop { graph.shop.close(until) }

    private fun switchShop(call: suspend () -> ShopHours) {
        shop.value = shop.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val hours = call()
                shop.value = ShopView(hours)
                if (hours.preordersInside > 0) _events.value = StaffEvent.PreordersInside(hours.preordersInside)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shop.value = shop.value.copy(busy = false)
                _events.value = StaffEvent.ShopFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** A failure keeps what was last read: the queue's own banner says when the shop is out of reach. */
    private suspend fun refreshShop() {
        try {
            val hours = graph.shop.hours()
            if (!shop.value.busy) shop.value = ShopView(hours)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.logger.warn("Reading the shop hours failed: ${e.javaClass.simpleName}")
        }
    }

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

        /** As often as the queue's own poll: somebody may close the shop from another device. */
        val SHOP_POLL = 25.seconds
        const val PAUSE_ROUNDING = 5L
    }
}
