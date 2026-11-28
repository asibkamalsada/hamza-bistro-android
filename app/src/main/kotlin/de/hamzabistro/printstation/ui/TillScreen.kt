package de.hamzabistro.printstation.ui

import android.app.Application
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.CardChoice
import de.hamzabistro.printstation.core.CardTerminal
import de.hamzabistro.printstation.core.CardTerminals
import de.hamzabistro.printstation.core.Bestsellers
import de.hamzabistro.printstation.core.CounterError
import de.hamzabistro.printstation.core.CounterSale
import de.hamzabistro.printstation.core.CounterSales
import de.hamzabistro.printstation.core.NeedsServerUpdateException
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.PrinterException
import de.hamzabistro.printstation.core.Till
import de.hamzabistro.printstation.core.TillProblem
import de.hamzabistro.printstation.core.TillState
import de.hamzabistro.printstation.core.TillStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The menu to tap from, at the board's prices, and how the receipt's printing goes. */
data class TillView(
    val menu: OrderMenu? = null,
    val menuError: String? = null,
    /** The dishes to show first, the best seller of the day first: ids, as [Bestsellers] ranks them. */
    val bestsellers: List<Long> = emptyList(),
    val printing: Boolean = false,
    val printed: Boolean = false,
    val printError: String? = null,
)

/** What the Kasse's screen can be asked to do: [TillViewModel] does it, a drawing in a test only notes it. */
interface TillActions {
    /** Whether there is a printer to print the receipt on. */
    val canPrint: Boolean

    fun loadPhoto(url: String)

    fun loadMenu()

    fun add(line: BasketLine)

    fun setQty(index: Int, qty: Int)

    fun setDineIn(dineIn: Boolean)

    fun setTraining(training: Boolean)

    fun clear()

    fun ringUp()

    fun resume(sale: CounterSale)

    fun chooseCash()

    fun chooseCard()

    fun setGiven(text: String)

    fun cashPaid()

    fun cardPaid()

    /**
     * What "Karte bezahlt" offers: the S-POS phones in use and this device's
     * usual one (hamza-bistro-web 20261128100000). None by default: the
     * card is booked with no phone, as before.
     */
    val cardChoice: CardChoice
        get() = CardChoice.NoList

    /**
     * "Karte bezahlt" on [terminal], the S-POS phone it went through (null:
     * none named), with [tip] on top of the bill when there is one.
     */
    fun cardPaidOn(terminal: CardTerminal?, tip: Double? = null) = cardPaid()

    fun cardDeclined()

    fun back()

    fun abort()

    fun park()

    fun next()

    fun dismissProblem()

    fun printReceipt()
}

/**
 * The Kasse's screen over [Till], which is the process's, not the screen's:
 * a sale being paid is not lost by leaving the screen. Every step runs
 * outside the screen's own life, on the main thread like the taps.
 */
class TillViewModel(application: Application) : AndroidViewModel(application), TillActions {
    private val app = application
    private val graph = (application as PrintStationApp).graph
    private val till: Till = graph.till

    val state: StateFlow<TillState> = till.state

    private val _view = MutableStateFlow(TillView())
    val view: StateFlow<TillView> = _view.asStateFlow()

    private val dishPhotos = DishPhotos(graph, viewModelScope)
    val photos: StateFlow<Map<String, ImageBitmap?>> = dishPhotos.photos

    private var pricing: Job? = null

    /** The S-POS phones and this device's usual one, as Compose state: the card pane redraws when either changes. */
    private val choice = mutableStateOf(graph.cardChoice())

    override val cardChoice: CardChoice
        get() = choice.value

    init {
        // Only the graph's flows: nothing of this class is read here.
        viewModelScope.launch {
            combine(graph.cardTerminals, graph.settings.device) { terminals, prefs -> CardTerminals.choice(terminals, prefs.cardTerminal) }
                .collect { choice.value = it }
        }
    }

    override fun loadPhoto(url: String) = dishPhotos.load(url)

    /** On every visit: whether the till is on, what is open, and what has sold out since; and the S-POS phones. */
    fun look() {
        step { till.refresh() }
        viewModelScope.launch { graph.refreshCardTerminals() }
        loadMenu()
    }

    override fun loadMenu() {
        _view.update { it.copy(menuError = null) }
        val ranking = ranking()
        viewModelScope.launch {
            val menu = attempt(app, { error -> _view.update { it.copy(menuError = error) } }) { graph.phoneOrders.menu() } ?: return@launch
            // Waited for, so no tile moves once there are some; not for long: the dishes come first.
            val best = withTimeoutOrNull(BESTSELLERS_WAIT_MS) { ranking.await() } ?: graph.bestsellers.known
            _view.update { it.copy(menu = CounterSales.boardMenu(menu), bestsellers = if (it.menu == null || idle()) best else it.bestsellers) }
        }
    }

    /**
     * Another day, another ranking: a tablet may stand on this screen for
     * days. Never while somebody is tapping dishes.
     */
    fun rank() {
        // The first one comes with the menu.
        if (_view.value.menu == null) return
        val ranking = ranking()
        viewModelScope.launch {
            val best = ranking.await()
            if (idle()) _view.update { it.copy(bestsellers = best) }
        }
    }

    /** Outlives the screen: what is read late is there for the next visit. */
    private fun ranking(): Deferred<List<Long>> = graph.scope.async { graph.bestsellers.today() }

    private fun idle(): Boolean = till.state.value.lines.isEmpty()

    override fun add(line: BasketLine) {
        till.add(line)
        reprice()
    }

    override fun setQty(index: Int, qty: Int) {
        till.setQty(index, qty)
        reprice()
    }

    override fun setDineIn(dineIn: Boolean) = till.setDineIn(dineIn)

    override fun setTraining(training: Boolean) = till.setTraining(training)

    override fun clear() = till.clear()

    /** Asked once the tapping pauses: the menu's sum is shown meanwhile. */
    private fun reprice() {
        pricing?.cancel()
        pricing =
            viewModelScope.launch {
                delay(PRICE_PAUSE_MS)
                till.reprice()
            }
    }

    override fun ringUp() = step {
        till.ringUp()
        // The price moved: the new one is on the screen before the next tap.
        if (till.state.value.problem == TillProblem.Refused(CounterError.TOTAL_CHANGED)) till.reprice()
    }

    override fun resume(sale: CounterSale) = till.resume(sale)

    override fun chooseCash() = till.chooseCash()

    override fun chooseCard() = step { till.chooseCard() }

    override fun setGiven(text: String) = till.setGiven(text)

    override fun cashPaid() = step { till.cashPaid() }

    override fun cardPaid() = step { till.cardPaid() }

    override fun cardPaidOn(terminal: CardTerminal?, tip: Double?) {
        terminal?.let { graph.rememberCardTerminal(it) }
        step { till.cardPaid(terminal, tip) }
    }

    override fun cardDeclined() = step { till.cardDeclined() }

    override fun back() = step { till.back() }

    override fun abort() = step { till.abort() }

    override fun park() = step { till.park() }

    override fun next() {
        _view.update { it.copy(printed = false, printError = null) }
        step {
            till.next()
            rank()
        }
    }

    override fun dismissProblem() = till.dismissProblem()

    suspend fun awaitReceipt() = till.awaitReceipt()

    override val canPrint: Boolean
        get() = graph.settings.printer != null

    /** The customer's receipt on this device's printer. */
    override fun printReceipt() {
        val order = till.state.value.sale?.orderId ?: return
        if (_view.value.printing) return
        _view.update { it.copy(printing = true, printed = false, printError = null) }
        viewModelScope.launch {
            val failure =
                try {
                    graph.printReceipt(order)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NeedsServerUpdateException) {
                    app.getString(R.string.till_print_old)
                } catch (e: PrinterException) {
                    app.getString(R.string.till_print_failed, e.message.orEmpty())
                } catch (e: Exception) {
                    failureText(app, e)
                }
            _view.update { it.copy(printing = false, printed = failure == null, printError = failure) }
        }
    }

    private fun step(block: suspend () -> Unit) {
        graph.scope.launch(Dispatchers.Main.immediate) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.logger.warn("The till failed", e)
            }
        }
    }

    private companion object {
        const val PRICE_PAUSE_MS = 300L
        const val BESTSELLERS_WAIT_MS = 1_500L
    }
}

/**
 * "Kasse": a sale at the counter. Tap dishes, "Kassieren", Bar or Karte, the
 * receipt. See [Till] for what is recorded when, and where.
 */
@Composable
fun TillScreen(
    onBack: () -> Unit,
    navigation: @Composable () -> Unit,
    leaving: Leaving = remember { Leaving() },
    model: TillViewModel = viewModel(),
    storno: StornoViewModel = viewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val view by model.view.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.look() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.rank() }
    LaunchedEffect(state.sale?.id, state.step) { if (state.step == TillStep.DONE) model.awaitReceipt() }
    TillContent(state, view, photos, model, onBack, navigation, leaving, onStorno = storno::open)
    // A sale just paid, taken back at once ("falsch getippt"): the Storno's own receipt follows.
    StornoDialog(storno, onDone = { _, _ -> })
}

/**
 * The Kasse as it is drawn: what it shows, and what its taps do.
 *
 * How much is on the screen at once counts as much as how big it is: the text
 * is set bigger than the rest of the app's, and grows with the device's font size.
 */
@Composable
internal fun TillContent(
    state: TillState,
    view: TillView,
    photos: Map<String, ImageBitmap?>,
    model: TillActions,
    onBack: () -> Unit,
    navigation: @Composable () -> Unit,
    leaving: Leaving = remember { Leaving() },
    /** "Storno" on the paid sale; null where nothing can be taken back (the previews). */
    onStorno: ((StornoTarget) -> Unit)? = null,
) {
    val sale = state.sale
    var aborting by remember(sale?.id) { mutableStateOf(false) }
    val leave: (() -> Unit) -> Unit = { go ->
        // A sale that is paid is over; an open one stays where it is.
        if (state.step == TillStep.DONE) model.next()
        go()
    }
    BackHandler { if (state.step == TillStep.CASH) model.back() else leave(onBack) }
    leaving.Ask(leave)

    Scaffold(topBar = { TillHeader(state, model, navigation) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.step) {
                TillStep.DISHES ->
                    if (state.available == false) TillMessage(stringResource(R.string.till_unavailable))
                    else TillDishesPane(state, view, photos, model)
                TillStep.PAY -> if (sale != null) TillPayPane(state, sale, model, onAbort = { aborting = true })
                TillStep.CASH -> if (sale != null) TillCashPane(state, sale, model)
                TillStep.CARD -> if (sale != null) TillCardPane(state, sale, model)
                TillStep.DONE -> if (sale != null) TillDonePane(state, sale, view, model, onStorno)
            }
        }
    }

    if (aborting && sale != null && state.step == TillStep.PAY) {
        AskFirst(
            title = stringResource(R.string.till_abort_title, sale.number),
            text = stringResource(R.string.till_abort_text),
            yes = stringResource(R.string.till_abort),
            onDismiss = { aborting = false },
        ) {
            aborting = false
            model.abort()
        }
    }
}

/** The bar, in its own colour while it is only training, and why. */
@Composable
private fun TillHeader(state: TillState, model: TillActions, navigation: @Composable () -> Unit) {
    val sale = state.sale
    val training = state.training || sale?.training == true
    SlimHeader(
        if (sale == null) stringResource(R.string.till_title) else stringResource(R.string.till_sale, sale.number),
        navigation,
        note =
            when {
                !training -> null
                state.off -> stringResource(R.string.till_off_strip)
                else -> stringResource(R.string.till_training_strip)
            },
        tint = training,
        busy = state.busy,
        action = {
            // Off, there is nothing to choose: the bar says why.
            if (state.step == TillStep.DISHES && !state.off && state.available != false) {
                FilterChip(
                    selected = state.training,
                    onClick = { model.setTraining(!state.training) },
                    label = {
                        Text(
                            stringResource(if (state.training) R.string.till_training_end else R.string.till_training),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                )
            }
        },
    )
}

@Composable
private fun TillMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
    }
}

@Composable
internal fun TillProblemCard(problem: TillProblem, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ProblemCard(tillProblemText(LocalContext.current, problem), onDismiss, modifier)
}

/** What went wrong, for the person at the counter. */
internal fun tillProblemText(context: Context, problem: TillProblem): String =
    when (problem) {
        is TillProblem.Offline -> context.getString(if (problem.unsure) R.string.till_offline_unsure else R.string.till_offline)
        TillProblem.SignedOut -> context.getString(R.string.stopped_signed_out)
        TillProblem.Unavailable -> context.getString(R.string.till_unavailable)
        is TillProblem.Other -> context.getString(R.string.till_failed, problem.message)
        is TillProblem.Refused ->
            context.getString(
                when (problem.reason) {
                    CounterError.NOT_STAFF -> R.string.phone_error_not_staff
                    CounterError.TOTAL_CHANGED -> R.string.till_total_changed
                    CounterError.BAD_VALUE -> R.string.till_bad_value
                    CounterError.BASKET -> R.string.phone_error_basket
                    CounterError.OFF -> R.string.till_off
                    CounterError.GONE -> R.string.till_gone
                    CounterError.ALREADY -> R.string.till_already
                    CounterError.KEY_REUSED -> R.string.till_key_reused
                    CounterError.STALE -> R.string.till_stale
                    CounterError.NO_SPLIT -> R.string.till_no_split
                    CounterError.DISMISSED -> R.string.till_dismissed
                    CounterError.CARD_TERMINAL -> R.string.till_card_terminal
                    CounterError.TIP -> R.string.till_tip
                }
            )
    }
