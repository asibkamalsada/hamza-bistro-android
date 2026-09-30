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
import kotlinx.coroutines.withTimeoutOrNull

/** How the station is doing, for the notification and the screen. */
data class StationStatus(
    /** When the queue was last read, on the wall clock. */
    val lastLook: Long? = null,
    /** The number of the last order whose ticket came out here. */
    val lastPrinted: Long? = null,
    /** What went wrong at the last look, until a look goes right. */
    val problem: Problem? = null,
)

sealed interface Problem {
    /** The queue, the claim or the ticket could not be fetched. */
    data class Offline(val reason: String) : Problem

    /** A ticket did not come out — almost always the printer being off. */
    data class NotPrinted(val order: Long, val reason: String) : Problem

    /** Signed in, but the account is neither staff nor a print account. */
    data object NotAllowed : Problem
}

/**
 * Prints every accepted order, by itself: what
 * src/app/services/print-station.service.ts in hamza-bistro-web does on a
 * page, from a service that runs with the screen off.
 *
 * It looks at the queue when Realtime says something changed, and every 25
 * seconds besides; says it is there at least once a minute; takes each order
 * with claim_print() before printing it and reports with finish_print()
 * after, so a second printing device never prints the same order. A ticket
 * that fails stops the round — the printer is off, and the next one would
 * fail too — and is tried again at the next look, so switching the printer
 * back on is all it takes to catch up.
 */
class PrintStation(
    private val backend: PrintBackend,
    private val printer: TicketPrinter,
    private val log: PrintLog,
    private val station: String,
    private val label: String,
    private val lang: String,
    private val logger: Logger,
    /** A monotonic clock in milliseconds, for intervals. */
    private val now: () -> Long,
    /** The wall clock, for what is shown. */
    private val wallClock: () -> Long,
    private val timing: Timing = Timing(),
) {
    class Timing(
        /** The poll under Realtime, as on /orders. */
        val poll: Duration = 25.seconds,
        /** A burst of changes is one look. */
        val debounce: Duration = 1500.milliseconds,
        /**
         * How often the database hears the station is there. The push about a
         * ticket that did not print counts a station seen in the last two
         * minutes as running (STATION_LOOKING_MS in the site's staff-push.ts).
         */
        val seenEvery: Duration = 60.seconds,
        /** A ticket is a kilobyte; one still not sent after this is not going to be. */
        val ticketTimeout: Duration = 20.seconds,
    )

    private val _status = MutableStateFlow(StationStatus())
    val status: StateFlow<StationStatus> = _status.asStateFlow()

    private var lastLook = Long.MIN_VALUE / 2
    private var lastSeen = Long.MIN_VALUE / 2

    /**
     * Prints until cancelled. Ends by itself only when the session has ended
     * ([SignedOutException]): then nothing but signing in again helps.
     */
    @OptIn(FlowPreview::class)
    suspend fun run(changes: Flow<Unit>): Nothing = coroutineScope {
        // Conflated: a look asked for while one is under way is one more
        // look afterwards, however many times it was asked for.
        val asks = Channel<Unit>(Channel.CONFLATED)
        asks.trySend(Unit)
        launch { changes.debounce(timing.debounce).collect { asks.send(Unit) } }
        launch {
            while (true) {
                delay(timing.poll)
                // Not when Realtime had it look just now.
                if (now() - lastLook >= timing.poll.inWholeMilliseconds) asks.send(Unit)
            }
        }
        for (ask in asks) look()
        error("the look channel never closes")
    }

    /**
     * Counts every order accepted so far as none of this station's business.
     * Switching printing on is not a request to print the whole evening
     * again — the same rule as the switch on the site.
     */
    suspend fun skipWaiting() {
        val waiting = backend.openOrders()
        log.markDone(waiting.map { it.id })
        logger.info("Printing switched on; ${waiting.size} accepted orders left to others")
    }

    private suspend fun look() {
        lastLook = now()
        seenIfDue()
        val orders =
            try {
                backend.openOrders()
            } catch (e: Exception) {
                failed(e, "the queue could not be read")
                return
            }
        _status.update { it.copy(lastLook = wallClock()) }
        try {
            printQueue(orders)
            _status.update { it.copy(problem = null) }
        } catch (e: TicketFailed) {
            _status.update { it.copy(problem = e.problem) }
        } catch (e: Exception) {
            failed(e, "a ticket could not be fetched")
        }
    }

    /** Throws [TicketFailed] at the first ticket that does not come out. */
    private suspend fun printQueue(orders: List<QueuedOrder>) {
        // Printed here, not yet recorded: say so again before anything else.
        for (order in log.unreported()) report(order)

        for (order in orders) {
            if (log.isDone(order.id) || order.id in log.unreported()) continue
            when (backend.claim(order.id)) {
                // Printed elsewhere, or no longer waiting to be cooked:
                // never this station's.
                Claim.PRINTED, Claim.MOVED -> {
                    log.markDone(listOf(order.id))
                    continue
                }
                // Another station is at it; looked at again next time, in
                // case it fails.
                Claim.BUSY -> continue
                Claim.CLAIMED -> Unit
            }

            val ticket =
                try {
                    backend.ticket(order.id, lang)
                } catch (e: Exception) {
                    giveBack(order)
                    throw e
                }
            if (ticket == null) {
                // Moved on in the moment between the claim and the ticket.
                giveBack(order)
                log.markDone(listOf(order.id))
                continue
            }

            try {
                withTimeoutOrNull(timing.ticketTimeout) { printer.print(ticket) }
                    ?: throw PrinterException("the printer did not answer")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                giveBack(order)
                logger.warn("Ticket for #${order.number} did not print", e)
                throw TicketFailed(Problem.NotPrinted(order.number, e.message ?: e.javaClass.simpleName))
            }
            log.markDone(listOf(order.id))
            log.setUnreported(order.id, true)
            logger.info("Printed #${order.number}")
            _status.update { it.copy(lastPrinted = order.number) }
            report(order.id)
        }
    }

    /** Tells the database a ticket came out; if it cannot be told, at the next look. */
    private suspend fun report(order: String) {
        try {
            backend.finish(order, true)
            log.setUnreported(order, false)
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            logger.warn("Could not record a printed ticket; trying again next time", e)
        }
    }

    /** The claim back, so the next station takes the order at once rather than in a minute. */
    private suspend fun giveBack(order: QueuedOrder) {
        try {
            backend.finish(order.id, false)
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            // The claim runs out by itself after a minute.
        }
    }

    /** Here I am, and I print — at most once a minute; a failure is tried at the next look. */
    private suspend fun seenIfDue() {
        if (now() - lastSeen < timing.seenEvery.inWholeMilliseconds) return
        try {
            backend.seen(station, label)
            lastSeen = now()
        } catch (e: Exception) {
            if (e is CancellationException || e is SignedOutException) throw e
            logger.warn("Could not tell the database this station is there", e)
        }
    }

    private fun failed(e: Exception, what: String) {
        if (e is CancellationException || e is SignedOutException) throw e
        val problem = if (e is NotAllowedException) Problem.NotAllowed else Problem.Offline(e.message ?: what)
        logger.warn("Print station: $what", e)
        _status.update { it.copy(problem = problem) }
    }

    private class TicketFailed(val problem: Problem) : Exception()
}
