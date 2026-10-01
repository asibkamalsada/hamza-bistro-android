package de.hamzabistro.printstation.queue

import de.hamzabistro.printstation.core.Logger
import de.hamzabistro.printstation.core.OrderMovedException
import de.hamzabistro.printstation.core.OrderQueue
import de.hamzabistro.printstation.core.OrderStep
import de.hamzabistro.printstation.core.SignedOutException
import de.hamzabistro.printstation.core.StaffBackend
import de.hamzabistro.printstation.core.StaffOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A step tapped and not yet sent: it can still be taken back. */
data class Pending(val order: StaffOrder, val step: OrderStep, val seconds: Int, val until: Long)

/** Why a step did not go through, for the screen to say. */
sealed interface StepFailure {
    /** It had moved on elsewhere; the queue now shows where it stands. */
    data object Moved : StepFailure

    data class Failed(val reason: String) : StepFailure
}

/**
 * Every step waits out the device's undo window before it is sent, because
 * sending it emails the customer: "Geliefert" pressed on the wrong one of
 * two cards in the bag is not something to put right afterwards. The same
 * rule as /orders, and the same for the queue and the alarm screen, which is
 * why it lives with the process rather than with a screen.
 *
 * A step only lands if the order is still where this device saw it.
 */
class PendingSteps(
    private val scope: CoroutineScope,
    private val backend: StaffBackend,
    private val queue: OrderQueue,
    private val logger: Logger,
    private val undoSeconds: () -> Int,
    private val now: () -> Long,
) {
    private val _pending = MutableStateFlow<Map<String, Pending>>(emptyMap())
    val pending: StateFlow<Map<String, Pending>> = _pending.asStateFlow()

    private val _failures = MutableSharedFlow<StepFailure>(extraBufferCapacity = 4)
    val failures: SharedFlow<StepFailure> = _failures.asSharedFlow()

    private val timers = mutableMapOf<String, Job>()

    /** The order ids being sent or waiting to be: the alarm leaves them alone. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    @Synchronized
    fun take(order: StaffOrder, step: OrderStep) {
        if (order.id in _pending.value || order.id in _busy.value) return
        val seconds = undoSeconds()
        if (seconds <= 0) {
            commit(order, step)
            return
        }
        _pending.update { it + (order.id to Pending(order, step, seconds, now() + seconds * 1000L)) }
        timers[order.id] = scope.launch {
            delay(seconds * 1000L)
            send(order.id)
        }
    }

    @Synchronized
    fun undo(order: String) {
        timers.remove(order)?.cancel()
        _pending.update { it - order }
    }

    /** Sends whatever waits, now — the screen is going away. */
    @Synchronized
    fun flushAll() {
        for (id in _pending.value.keys.toList()) send(id)
    }

    @Synchronized
    private fun send(id: String) {
        val pending = _pending.value[id] ?: return
        timers.remove(id)?.cancel()
        _pending.update { it - id }
        commit(pending.order, pending.step)
    }

    private fun commit(order: StaffOrder, step: OrderStep) {
        _busy.update { it + order.id }
        scope.launch {
            try {
                backend.move(order, step)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OrderMovedException) {
                _failures.tryEmit(StepFailure.Moved)
            } catch (e: SignedOutException) {
                _failures.tryEmit(StepFailure.Failed(e.message ?: "signed out"))
            } catch (e: Exception) {
                logger.warn("Could not move #${order.orderNumber}", e)
                _failures.tryEmit(StepFailure.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                queue.refresh()
                // Until the queue has been read again, the order would still
                // look as it did, and ring as it did.
                delay(BUSY_AFTER_MS)
                _busy.update { it - order.id }
            }
        }
    }

    private companion object {
        const val BUSY_AFTER_MS = 3_000L
    }
}
