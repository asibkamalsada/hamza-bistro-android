package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.core.AlarmDecision
import de.hamzabistro.printstation.core.IssuesState
import de.hamzabistro.printstation.core.CancelReason
import de.hamzabistro.printstation.core.Driver
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.OrderStep
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.Kitchen
import de.hamzabistro.printstation.core.KitchenSlot
import de.hamzabistro.printstation.core.PauseWhat
import de.hamzabistro.printstation.core.Payment
import de.hamzabistro.printstation.core.PaymentMethod
import de.hamzabistro.printstation.core.QueueState
import de.hamzabistro.printstation.core.ShopHours
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.core.StaffQueue
import de.hamzabistro.printstation.core.UpdateChecker
import de.hamzabistro.printstation.core.UpdateState
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    /** Whether the database knows "Fertig": false on one from before it, and the button goes. */
    val packing: Boolean = true,
) {
    /** Busy mode's minutes on every promise, now: 0 while it is off, or once it ran out. */
    val busyMinutes: Int
        get() = shop.hours?.busyMinutes(now) ?: 0

    /** What "Alle +15 Min." would move now: shown only while this is not empty. */
    val delayableAll: List<StaffOrder>
        get() = StaffQueue.delayableAll(queue.orders, StaffQueue.DELAY_ALL_MINUTES, now)
}

/** What happened that the screen should say once. */
sealed interface StaffEvent {
    data class Step(val failure: StepFailure) : StaffEvent

    data class Printed(val order: Long) : StaffEvent

    data class PrintFailed(val reason: String) : StaffEvent

    /** Opening or closing the shop did not go through. */
    data class ShopFailed(val reason: String) : StaffEvent

    /** Orders already booked for a time inside a closure just made: they stand. */
    data class PreordersInside(val count: Int) : StaffEvent

    /** "Alle +15 Min." went through: [count] orders, 0 when there was none left to move. */
    data class DelayedAll(val count: Int, val minutes: Int) : StaffEvent

    data class DelayAllFailed(val reason: String) : StaffEvent
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
        combine(graph.liveQueue, graph.settings.device, graph.steps.pending, graph.steps.busy, graph.steps.packing) {
            queue,
            prefs,
            pending,
            busy,
            packing ->
            StaffState(queue = queue, prefs = prefs, pending = pending, busy = busy, packing = packing)
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

    /**
     * The open problem reports, for the badge on "Mehr → Reklamationen"
     * (hamza-bistro-web#88); not available on a database without them.
     */
    val issues: StateFlow<IssuesState> =
        graph.liveIssues.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IssuesState())

    private val kitchenRead = MutableStateFlow<List<KitchenSlot>?>(null)

    /**
     * The kitchen's next hour for the line above the queue (hamza-bistro-web#73),
     * read again whenever the queue is. Null on a database without the cap.
     */
    val kitchen: StateFlow<List<KitchenSlot>?> =
        merge(
            flow<Nothing> { graph.queue.state.map { it.lastLoad }.distinctUntilChanged().collect { readKitchen() } },
            kitchenRead,
        )
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A failure keeps what was last read: the queue's own banner says when the database is out of reach. */
    private suspend fun readKitchen() {
        try {
            val (from, to) = Kitchen.window(Instant.now())
            kitchenRead.value = graph.shop.kitchenSlots(from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.logger.warn("Reading the kitchen's load failed: ${e.javaClass.simpleName}")
        }
    }

    private val _events = MutableStateFlow<StaffEvent?>(null)
    val events: StateFlow<StaffEvent?> = _events.asStateFlow()

    init {
        viewModelScope.launch { graph.steps.failures.collect { _events.value = StaffEvent.Step(it) } }
        // What is read anyway: collecting liveQueue here would keep the queue running behind the screen.
        viewModelScope.launch { graph.queue.state.collect { forgetGone(it.orders) } }
    }

    fun eventShown() {
        _events.value = null
    }

    fun suggestedEta(order: StaffOrder): Int = graph.suggestedEta(order, state.value.queue.prep, Instant.now())

    fun accept(order: StaffOrder, minutes: Int) = graph.steps.take(order, OrderStep.Accept(minutes))

    fun acceptScheduled(order: StaffOrder) = graph.steps.take(order, OrderStep.AcceptScheduled)

    /**
     * Out of the door or onto the counter; then delivered or collected,
     * with the one button an order paid online keeps.
     */
    fun moveOn(order: StaffOrder) =
        graph.steps.take(
            order,
            if (order.status == OrderStatus.CONFIRMED) OrderStep.Out else Payment.done(order, null, graph.deviceLabel()),
        )

    /**
     * "Bar" or "Karte": delivered or collected, and how the money came in,
     * for the Kassensturz — with this device's label, so two drivers on one
     * account stay apart. Held for the undo window like any step, so the
     * wrong one of the two taken back records nothing.
     */
    fun deliver(order: StaffOrder, payment: PaymentMethod) = graph.steps.take(order, Payment.done(order, payment, graph.deviceLabel()))

    fun cancel(order: StaffOrder, reason: CancelReason?) = graph.steps.take(order, OrderStep.Cancel(reason))

    /**
     * "+10 Min.": the promise later, on every device, on /my-orders and in
     * Telegram, and an email to a customer who asked for updates — so it
     * waits out the undo window like a step.
     */
    fun delay(order: StaffOrder, minutes: Int) = graph.steps.take(order, OrderStep.Delay(minutes))

    private val _delayingAll = MutableStateFlow(false)

    /** "Alle +15 Min." is on its way: its button waits. */
    val delayingAll: StateFlow<Boolean> = _delayingAll.asStateFlow()

    /**
     * "Alle +15 Min.", asked about first on the screen: it emails every one
     * of those customers. Sent at once — the question was the pause.
     */
    fun delayAll() {
        if (_delayingAll.value) return
        _delayingAll.value = true
        viewModelScope.launch {
            val minutes = StaffQueue.DELAY_ALL_MINUTES
            try {
                _events.value = StaffEvent.DelayedAll(graph.staff.delayOpen(minutes), minutes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.logger.warn("Delaying every order failed", e)
                _events.value = StaffEvent.DelayAllFailed(e.message ?: e.javaClass.simpleName)
            } finally {
                _delayingAll.value = false
                graph.queue.refresh()
            }
        }
    }

    /**
     * "Fertig": the bag is packed and waits for the driver — whose phone
     * chimes. Held for the undo window like a step, so "Rückgängig" inside
     * it sends nothing.
     */
    fun pack(order: StaffOrder) = graph.steps.take(order, OrderStep.Pack)

    /** "Doch nicht fertig": the undo for a "Fertig" the window already sent, at once, as on /orders. */
    fun unpack(order: StaffOrder) = graph.steps.takeNow(order, OrderStep.Unpack)

    fun undo(order: StaffOrder) = graph.steps.undo(order.id)

    // -------------------------------------------------------------------
    // The driver: several bags at once, one route (#8)
    // -------------------------------------------------------------------

    /** Whether this device is a driver's phone, as it is set now: the queue opens on "Fahrer". */
    val driverDevice: Boolean
        get() = graph.settings.device.value.driver

    private val _chosen = MutableStateFlow<Set<String>>(emptySet())

    /** The bags ticked for "Mitnehmen". */
    val chosen: StateFlow<Set<String>> = _chosen.asStateFlow()

    private val _stopOrder = MutableStateFlow<List<String>>(emptyList())

    /** The order the driver put the stops in with ↑ / ↓; empty until they did. */
    val stopOrder: StateFlow<List<String>> = _stopOrder.asStateFlow()

    fun choose(order: StaffOrder) {
        if (!Driver.canTake(order)) return
        _chosen.value = if (order.id in _chosen.value) _chosen.value - order.id else _chosen.value + order.id
    }

    /**
     * "Mitnehmen (n)": every ticked bag still in the kitchen goes
     * "Unterwegs", each one its own step — with its own undo window, and
     * sent only from where this device saw it, so one that moved on
     * elsewhere meanwhile is refused alone and the rest go through.
     */
    fun takeAlong() {
        val batch = Driver.batch(_chosen.value, state.value.queue.orders)
        _chosen.value = emptySet()
        for (order in batch) graph.steps.take(order, OrderStep.Out)
    }

    /** ↑ / ↓ on a stop: the driver knows the streets better than the postcode does. */
    fun moveStop(order: StaffOrder, by: Int) {
        _stopOrder.value = Driver.move(Driver.stops(state.value.queue.orders, _stopOrder.value), order.id, by)
    }

    /** Ticks on bags that have left the kitchen meanwhile, here or anywhere, mean nothing any more. */
    private fun forgetGone(orders: List<StaffOrder>) {
        val still = Driver.stillChosen(_chosen.value, orders)
        if (still != _chosen.value) _chosen.value = still
    }

    /** The screen went away: what waits on its undo window goes now, as on /orders. */
    fun flush() = graph.steps.flushAll()

    /**
     * The queue now, and whether the address check works — on every return
     * to the screen. And whether a newer build is out, at most once an hour.
     */
    fun refresh() {
        graph.queue.refresh()
        viewModelScope.launch { checkAddress() }
        checkForUpdate(UpdateChecker.ON_OPEN)
    }

    /** Whether a newer build is out, for the line above the queue and the settings. */
    val update: StateFlow<UpdateState> = graph.updates.state

    /** Looks for a newer build unless it was looked for less than [maxAge] ago. Never fails. */
    fun checkForUpdate(maxAge: kotlin.time.Duration = kotlin.time.Duration.ZERO) {
        graph.scope.launch { graph.updates.check(maxAge) }
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

    /**
     * Closed for [minutes] from now, rounded up to five: "wieder ab 18:40",
     * not 18:37 — everything, or with [what] only delivery or the outer rings.
     */
    fun pauseShop(minutes: Long, what: PauseWhat = PauseWhat.ALL) {
        val step = Duration.ofMinutes(PAUSE_ROUNDING).toMillis()
        val until = Instant.now().plus(minutes, ChronoUnit.MINUTES).toEpochMilli()
        closeShop(Instant.ofEpochMilli((until + step - 1) / step * step), what)
    }

    /** Closed until midnight in Leipzig: tomorrow opens as usual. */
    fun closeShopForToday(what: PauseWhat = PauseWhat.ALL) =
        closeShop(
            Instant.now().atZone(AlarmPolicy.LEIPZIG).toLocalDate().plusDays(1).atStartOfDay(AlarmPolicy.LEIPZIG).toInstant(),
            what,
        )

    /** Closed until somebody opens again — here, on another device, or on the website. */
    fun closeShopForGood(what: PauseWhat = PauseWhat.ALL) = closeShop(null, what)

    /** Ends every closure running now, a pause of delivery too. */
    fun openShop() = switchShop { graph.shop.open() }

    /** Busy mode, here and on every device and the website: the database ends it by itself. */
    fun busyShop(minutes: Int, duration: Duration?) = switchShop { graph.shop.busy(minutes, duration) }

    fun notBusyShop() = switchShop { graph.shop.notBusy() }

    private fun closeShop(until: Instant?, what: PauseWhat) = switchShop { graph.shop.close(until, what = what) }

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
