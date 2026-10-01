package de.hamzabistro.printstation.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The queue as this device last read it. */
data class QueueState(
    val orders: List<StaffOrder> = emptyList(),
    /** False until the first read came back: an empty list is not "nothing to do" yet. */
    val loaded: Boolean = false,
    /** When the queue was last read, on the wall clock. */
    val lastLoad: Long? = null,
    /** Since when reading it fails, on the wall clock; null while it works. */
    val failingSince: Long? = null,
    /** Why the last read failed. */
    val problem: String? = null,
    /** Signed in, but not staff (any more). */
    val notAllowed: Boolean = false,
    /** Minutes per dish, for the suggested ETA; empty until the menu answered. */
    val prep: Map<Long, Int> = emptyMap(),
)

/**
 * Keeps the open orders in view: the queue on the screen and what the alarm
 * listens to. What /orders does on a page — Realtime says within a second
 * that something changed, a 25-second poll underneath catches a socket that
 * died without saying so — from a service that runs with the screen off.
 *
 * While [report] gives a device, it also says at least once a minute that
 * this device is on shift (staff_app_seen), which is how the site knows who
 * will hear about the next order, and how this device learns it was taken
 * off the staff list.
 */
class OrderQueue(
    private val backend: StaffBackend,
    private val logger: Logger,
    /** A monotonic clock in milliseconds, for intervals. */
    private val now: () -> Long,
    /** The wall clock, for what is shown. */
    private val wallClock: () -> Long,
    private val report: () -> DeviceReport? = { null },
    private val timing: Timing = Timing(),
) {
    class Timing(
        val poll: Duration = 25.seconds,
        /** One accept is two events; a burst is one look. */
        val debounce: Duration = 400.milliseconds,
        val seenEvery: Duration = 60.seconds,
        /** The prep times change when the owner edits the menu: read again now and then. */
        val prepEvery: Duration = 30.seconds * 60,
    )

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val asks = Channel<Unit>(Channel.CONFLATED)
    private var lastLook = Long.MIN_VALUE / 2
    private var lastSeen = Long.MIN_VALUE / 2
    private var lastPrep = Long.MIN_VALUE / 2

    /** Read the queue again now — after a step was sent, or the screen came back. */
    fun refresh() {
        asks.trySend(Unit)
    }

    /** Forgets what was read: another account is about to sign in. */
    fun reset() {
        _state.value = QueueState()
        lastSeen = Long.MIN_VALUE / 2
        lastPrep = Long.MIN_VALUE / 2
    }

    /**
     * Watches until cancelled. Ends by itself only when the session has
     * ended ([SignedOutException]).
     */
    @OptIn(FlowPreview::class)
    suspend fun run(changes: Flow<Unit>): Nothing = coroutineScope {
        asks.trySend(Unit)
        launch { changes.debounce(timing.debounce).collect { asks.send(Unit) } }
        launch {
            while (true) {
                delay(timing.poll)
                if (now() - lastLook >= timing.poll.inWholeMilliseconds) asks.send(Unit)
            }
        }
        for (ask in asks) look()
        error("the look channel never closes")
    }

    private suspend fun look() {
        lastLook = now()
        seenIfDue()
        prepIfDue()
        try {
            val orders = backend.openOrders()
            _state.update {
                it.copy(orders = orders, loaded = true, lastLoad = wallClock(), failingSince = null, problem = null)
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            logger.warn("Order queue could not be read", e)
            _state.update {
                it.copy(failingSince = it.failingSince ?: wallClock(), problem = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun seenIfDue() {
        val device = report() ?: return
        if (now() - lastSeen < timing.seenEvery.inWholeMilliseconds) return
        try {
            backend.seen(device)
            lastSeen = now()
            _state.update { it.copy(notAllowed = false) }
        } catch (e: NotAllowedException) {
            lastSeen = now()
            logger.warn("This account is not staff any more")
            _state.update { it.copy(notAllowed = true) }
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            logger.warn("Could not tell the shop this device is on shift", e)
        }
    }

    private suspend fun prepIfDue() {
        if (now() - lastPrep < timing.prepEvery.inWholeMilliseconds && _state.value.prep.isNotEmpty()) return
        try {
            val prep = backend.prepMinutes()
            lastPrep = now()
            _state.update { it.copy(prep = prep) }
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            // The estimate falls back to a rougher guess; the queue matters more.
            logger.warn("Prep times could not be read", e)
        }
    }
}
