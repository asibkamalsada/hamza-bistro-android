package de.hamzabistro.printstation.core

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the Kasse is. */
enum class TillStep {
    /** Tapping dishes: nothing is recorded yet. */
    DISHES,

    /** Rung up: how is it paid? */
    PAY,

    /** Cash: the amount given, the change. */
    CASH,

    /** The amount is on the card terminal. Nothing is paid before somebody says so. */
    CARD,

    /** Paid: the receipt. */
    DONE,
}

/** What went wrong, for the screen to say in words. */
sealed interface TillProblem {
    /**
     * No connection, or no answer. [unsure]: the request may have got through
     * all the same; tapping again is safe, it is the same sale.
     */
    data class Offline(val unsure: Boolean) : TillProblem

    /** The till refused. */
    data class Refused(val reason: CounterError) : TillProblem

    data object SignedOut : TillProblem

    /** The database has no till (any more). */
    data object Unavailable : TillProblem

    data class Other(val message: String) : TillProblem
}

/** What the Kasse shows. */
data class TillState(
    /** Null until asked. */
    val status: CounterStatus? = null,
    /** Whether the database has a till at all; null until asked. */
    val available: Boolean? = null,
    /** Sales open on any device, the oldest first; not the one on this screen. */
    val open: List<CounterSale> = emptyList(),
    val lines: List<BasketLine> = emptyList(),
    val dineIn: Boolean = false,
    /** A training sale: recorded as one, counted nowhere. */
    val training: Boolean = false,
    /** The database's price for [pricedLines]. */
    val price: CounterPrice? = null,
    val pricedLines: List<BasketLine>? = null,
    /** The sale on the screen, from "Kassieren" on. */
    val sale: CounterSale? = null,
    val step: TillStep = TillStep.DISHES,
    /** What is typed as the cash given. */
    val given: String = "",
    /** A request is on its way: nothing else can be tapped. */
    val busy: Boolean = false,
    /** The card was declined just now. */
    val declined: Boolean = false,
    /** The receipt's signature has been waited for long enough. */
    val stoppedWaiting: Boolean = false,
    val problem: TillProblem? = null,
) {
    /** The running total in cents: the database's once it has answered for this basket, the menu's until then. */
    val totalCents: Long
        get() = if (priced) CounterSales.cents(price!!.total) else CounterSales.subtotal(lines)

    /** [price] is the one for the basket on the screen. */
    val priced: Boolean
        get() = price != null && pricedLines == lines

    /** Real sales are not taken: only training is offered. */
    val off: Boolean
        get() = status?.enabled == false

    /** What to give back; null while the amount typed is none, or not enough. */
    val changeCents: Long?
        get() = sale?.let { sale -> CounterSales.parse(given)?.let { CounterSales.change(CounterSales.cents(sale.total), it) } }

    /** Cash can be confirmed: nothing typed (the exact amount), or enough. */
    val cashReady: Boolean
        get() = given.isBlank() || changeCents != null

    val stale: List<CounterSale>
        get() = open.filter { it.stale && !it.training }
}

/**
 * The Kasse, without Android: the basket, then one sale from "Kassieren" to
 * its receipt.
 *
 * Nothing here is written down on the device. The basket is in memory until
 * "Kassieren"; from then on the sale is the database's, and is found again
 * there ([refresh]) after a restart. Without a connection every step says so
 * and stays where it is.
 *
 * ONE TAP, ONE SALE. "Kassieren" makes an id for this basket and keeps it
 * until the sale is open or the basket changes, so a second tap or a retry
 * sends the same one. While a request is on its way nothing else is taken.
 */
class Till(
    private val backend: CounterBackend,
    private val newId: () -> String,
    private val logger: Logger = Logger.NONE,
) {
    private val _state = MutableStateFlow(TillState())
    val state: StateFlow<TillState> = _state.asStateFlow()

    /** The id of the basket being rung up, until its sale is open. */
    private var key: String? = null

    /** Whether there is a till, whether it is on, and what is open on it. */
    suspend fun refresh() {
        try {
            val status = backend.status()
            if (status == null) {
                _state.update { it.copy(available = false, status = null, open = emptyList()) }
                return
            }
            val open = if (status.open > 0) backend.openSales() else emptyList()
            _state.update {
                it.copy(
                    available = true,
                    status = status,
                    open = open.filterNot { sale -> sale.id == it.sale?.id },
                    // It answers again.
                    problem =
                        it.problem.takeUnless { problem ->
                            problem == TillProblem.Offline(unsure = false) ||
                                problem == TillProblem.SignedOut ||
                                problem == TillProblem.Refused(CounterError.NOT_STAFF)
                        },
                )
            }
            // Off: only training is left.
            if (!status.enabled) _state.update { if (it.step == TillStep.DISHES) it.copy(training = true) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // What was read before stands; the screen says so only where somebody looks.
            logger.warn("Reading the till failed: ${e.javaClass.simpleName}")
            _state.update { it.copy(problem = problem(e, unsure = false)) }
        }
    }

    // -----------------------------------------------------------------------
    // The basket
    // -----------------------------------------------------------------------

    fun add(line: BasketLine) = edit { it.copy(lines = Baskets.add(it.lines, line)) }

    fun setQty(index: Int, qty: Int) = edit { it.copy(lines = Baskets.setQty(it.lines, index, qty)) }

    fun setDineIn(dineIn: Boolean) = edit { it.copy(dineIn = dineIn) }

    /** Back to real sales only while the till is on. */
    fun setTraining(training: Boolean) = edit { it.copy(training = training || it.off) }

    fun clear() = edit { it.copy(lines = emptyList(), dineIn = false) }

    /** Any change to the basket is another basket, and another sale. */
    private fun edit(change: (TillState) -> TillState) {
        val before = _state.value
        if (before.step != TillStep.DISHES || before.busy) return
        val after = change(before)
        if (after.lines != before.lines || after.dineIn != before.dineIn || after.training != before.training) key = null
        _state.value = after.copy(problem = null)
    }

    /** The database's price for the basket. Its failure leaves the menu's sum: "Kassieren" says the rest. */
    suspend fun reprice() {
        val lines = _state.value.lines
        if (lines.isEmpty()) return
        try {
            val price = backend.price(lines)
            _state.update { if (it.lines == lines) it.copy(price = price, pricedLines = lines) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { if (it.lines == lines && it.step == TillStep.DISHES) it.copy(problem = problem(e, unsure = false)) else it }
        }
    }

    // -----------------------------------------------------------------------
    // The sale
    // -----------------------------------------------------------------------

    /** "Kassieren": lines and price are fixed, the TSE's transaction starts. */
    suspend fun ringUp() {
        val basket = _state.value
        if (basket.step != TillStep.DISHES || basket.lines.isEmpty()) return
        val id = key ?: newId().also { key = it }
        step(unsure = true) {
            val sale = backend.open(id, basket.lines, basket.dineIn, basket.training, basket.price?.total?.takeIf { basket.priced })
            key = null
            show(sale)
        }
        // What stops this one is on that list; or the till was switched off meanwhile.
        val problem = _state.value.problem
        if (problem == TillProblem.Refused(CounterError.STALE) || problem == TillProblem.Refused(CounterError.OFF)) refresh()
    }

    /** An open sale, this device's or another's, taken up where it is. */
    fun resume(sale: CounterSale) {
        val now = _state.value
        if (now.busy || now.step != TillStep.DISHES) return
        key = null
        show(sale)
    }

    fun chooseCash() {
        if (onSale(TillStep.PAY)) _state.update { it.copy(step = TillStep.CASH, given = "", declined = false, problem = null) }
    }

    fun setGiven(text: String) = _state.update { if (it.step == TillStep.CASH) it.copy(given = text) else it }

    /** From the cash or the card back to the choice. A card payment is not waited for any more. */
    suspend fun back() {
        when (_state.value.step) {
            TillStep.CASH -> _state.update { it.copy(step = TillStep.PAY, problem = null) }
            TillStep.CARD -> leaveCard(declined = false)
            else -> Unit
        }
    }

    /** "Karte": the till notes it, then the amount is shown to type into the card app. */
    suspend fun chooseCard() {
        if (!onSale(TillStep.PAY)) return
        val id = _state.value.sale!!.id
        step(unsure = false) { take(backend.cardPending(id, true), TillStep.CARD) }
    }

    /** "Abgelehnt": nothing was booked; cash, the card again, or no sale. */
    suspend fun cardDeclined() {
        if (_state.value.step == TillStep.CARD) leaveCard(declined = true)
    }

    private suspend fun leaveCard(declined: Boolean) {
        val id = _state.value.sale?.id ?: return
        step(unsure = false) { take(backend.cardPending(id, false), TillStep.PAY) }
        if (_state.value.step == TillStep.PAY) _state.update { it.copy(declined = declined) }
    }

    /** "Bar bezahlt". */
    suspend fun cashPaid() {
        if (_state.value.step == TillStep.CASH && _state.value.cashReady) pay(CounterPayment.CASH)
    }

    /**
     * "Karte bezahlt": only now is it booked as paid by card, on [terminal],
     * the S-POS phone it went through (null: none picked, "unbekannt"), with
     * [tip] on top of the bill when the customer gave one (20261128110000).
     */
    suspend fun cardPaid(terminal: CardTerminal? = null, tip: Double? = null) {
        if (_state.value.step == TillStep.CARD) pay(CounterPayment.CARD, terminal?.id, tip)
    }

    private suspend fun pay(method: CounterPayment, cardTerminal: Int? = null, tip: Double? = null) {
        val id = _state.value.sale?.id ?: return
        step(unsure = true) { show(backend.pay(id, method, cardTerminal, tip)) }
        if (_state.value.problem == TillProblem.Refused(CounterError.ALREADY)) look(id)
    }

    /** No sale: recorded as broken off. */
    suspend fun abort() {
        val sale = _state.value.sale ?: return
        if (_state.value.step == TillStep.DONE || _state.value.step == TillStep.DISHES) return
        step(unsure = true) { show(backend.abort(sale.id)) }
        if (_state.value.problem == TillProblem.Refused(CounterError.ALREADY)) look(sale.id)
    }

    /** Somebody else finished it, or an answer was lost: what it really is. */
    private suspend fun look(id: String) {
        val refused = _state.value.problem
        step(unsure = false) { show(backend.get(id)) }
        // Open after all: the refusal stands. Otherwise the screen shows what became of it.
        if (_state.value.sale?.state == SaleState.OPEN) _state.update { it.copy(problem = refused) }
    }

    /** Left open, for later or for another device: it stays on the list. */
    suspend fun park() {
        val now = _state.value
        if (now.busy || now.sale == null || now.step == TillStep.DONE) return
        fresh()
        refresh()
    }

    /** The next customer. */
    suspend fun next() {
        if (_state.value.step != TillStep.DONE) return
        fresh()
        refresh()
    }

    /** Asks for the sale again until the TSE has signed it, or for [limit]: the receipt is complete then. */
    suspend fun awaitReceipt(every: Duration = 1_200.milliseconds, limit: Duration = 24.seconds) {
        var left = limit
        while (left > Duration.ZERO) {
            val now = _state.value
            val sale = now.sale ?: return
            if (now.step != TillStep.DONE || sale.receipt?.pending != true) return
            delay(every)
            left -= every
            try {
                val read = backend.get(sale.id)
                _state.update { if (it.step == TillStep.DONE && it.sale?.id == read.id) it.copy(sale = read) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Asking for the receipt failed: ${e.javaClass.simpleName}")
            }
        }
        _state.update { it.copy(stoppedWaiting = true) }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    // -----------------------------------------------------------------------

    private fun onSale(step: TillStep): Boolean = _state.value.let { !it.busy && it.sale != null && it.step == step }

    /** The sale as answered, on the step it belongs to. */
    private fun show(sale: CounterSale) {
        when (sale.state) {
            // A card payment was being taken when it was left: the amount again, and whether it went through.
            SaleState.OPEN -> take(sale, if (sale.cardPending) TillStep.CARD else TillStep.PAY)
            SaleState.PAID ->
                _state.update { it.copy(sale = sale, step = TillStep.DONE, declined = false, stoppedWaiting = false, problem = null, open = it.open.without(sale)) }
            SaleState.ABORTED,
            SaleState.UNKNOWN -> {
                _state.update { it.copy(open = it.open.without(sale)) }
                fresh()
            }
        }
    }

    private fun take(sale: CounterSale, step: TillStep) =
        _state.update {
            it.copy(sale = sale, step = step, given = "", declined = false, problem = null, open = it.open.without(sale))
        }

    private fun List<CounterSale>.without(sale: CounterSale) = filterNot { it.id == sale.id }

    /** An empty Kasse. Training does not outlive its sale, unless the till is off. */
    private fun fresh() {
        key = null
        _state.update {
            TillState(status = it.status, available = it.available, open = it.open, training = it.off, busy = it.busy)
        }
    }

    /** One request at a time; what it fails with is the screen's problem, and nothing moves. */
    private suspend fun step(unsure: Boolean, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, problem = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(problem = problem(e, unsure)) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun problem(e: Exception, unsure: Boolean): TillProblem =
        when (e) {
            is CounterException -> TillProblem.Refused(e.reason)
            is NotAllowedException -> TillProblem.Refused(CounterError.NOT_STAFF)
            is NeedsServerUpdateException -> TillProblem.Unavailable
            is SignedOutException -> TillProblem.SignedOut
            // The server answered, and not with a refusal of the till's: it may have done its work before it failed.
            is BackendException -> if (e.status >= 500) TillProblem.Offline(unsure) else TillProblem.Other(e.message ?: "HTTP ${e.status}")
            is IOException -> TillProblem.Offline(unsure)
            else -> TillProblem.Other(e.message ?: e.javaClass.simpleName)
        }
}
