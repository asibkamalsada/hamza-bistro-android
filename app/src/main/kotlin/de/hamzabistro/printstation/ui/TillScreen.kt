package de.hamzabistro.printstation.ui

import android.app.Application
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.CounterError
import de.hamzabistro.printstation.core.CounterPayment
import de.hamzabistro.printstation.core.CounterReceipt
import de.hamzabistro.printstation.core.CounterSale
import de.hamzabistro.printstation.core.CounterSales
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.NeedsServerUpdateException
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OptionRules
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.PrinterException
import de.hamzabistro.printstation.core.Qr
import de.hamzabistro.printstation.core.Till
import de.hamzabistro.printstation.core.TillProblem
import de.hamzabistro.printstation.core.TillState
import de.hamzabistro.printstation.core.TillStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The menu to tap from, at the board's prices, and how the receipt's printing goes. */
data class TillView(
    val menu: OrderMenu? = null,
    val menuError: String? = null,
    val printing: Boolean = false,
    val printed: Boolean = false,
    val printError: String? = null,
)

/**
 * The Kasse's screen over [Till], which is the process's, not the screen's:
 * a sale being paid is not lost by leaving the screen. Every step runs
 * outside the screen's own life, on the main thread like the taps.
 */
class TillViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph
    private val till: Till = graph.till

    val state: StateFlow<TillState> = till.state

    private val _view = MutableStateFlow(TillView())
    val view: StateFlow<TillView> = _view.asStateFlow()

    private val dishPhotos = DishPhotos(graph, viewModelScope)
    val photos: StateFlow<Map<String, ImageBitmap?>> = dishPhotos.photos

    private var pricing: Job? = null

    fun loadPhoto(url: String) = dishPhotos.load(url)

    /** On every visit: whether the till is on, what is open, and what has sold out since. */
    fun look() {
        step { till.refresh() }
        loadMenu()
    }

    fun loadMenu() {
        _view.update { it.copy(menuError = null) }
        viewModelScope.launch {
            val menu = attempt(app, { error -> _view.update { it.copy(menuError = error) } }) { graph.phoneOrders.menu() } ?: return@launch
            _view.update { it.copy(menu = CounterSales.boardMenu(menu)) }
        }
    }

    fun add(line: BasketLine) {
        till.add(line)
        reprice()
    }

    fun setQty(index: Int, qty: Int) {
        till.setQty(index, qty)
        reprice()
    }

    fun setDineIn(dineIn: Boolean) = till.setDineIn(dineIn)

    fun setTraining(training: Boolean) = till.setTraining(training)

    fun clear() = till.clear()

    /** Asked once the tapping pauses: the menu's sum is shown meanwhile. */
    private fun reprice() {
        pricing?.cancel()
        pricing =
            viewModelScope.launch {
                delay(PRICE_PAUSE_MS)
                till.reprice()
            }
    }

    fun ringUp() = step {
        till.ringUp()
        // The price moved: the new one is on the screen before the next tap.
        if (till.state.value.problem == TillProblem.Refused(CounterError.TOTAL_CHANGED)) till.reprice()
    }

    fun resume(sale: CounterSale) = till.resume(sale)

    fun chooseCash() = till.chooseCash()

    fun chooseCard() = step { till.chooseCard() }

    fun setGiven(text: String) = till.setGiven(text)

    fun cashPaid() = step { till.cashPaid() }

    fun cardPaid() = step { till.cardPaid() }

    fun cardDeclined() = step { till.cardDeclined() }

    fun back() = step { till.back() }

    fun abort() = step { till.abort() }

    fun park() = step { till.park() }

    fun next() {
        _view.update { it.copy(printed = false, printError = null) }
        step { till.next() }
    }

    fun dismissProblem() = till.dismissProblem()

    suspend fun awaitReceipt() = till.awaitReceipt()

    val canPrint: Boolean
        get() = graph.settings.printer != null

    /** The customer's receipt on this device's printer. */
    fun printReceipt() {
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
    }
}

/** From this width on, the basket stands beside the dishes. */
private val BASKET_BESIDE_FROM = 720.dp

/**
 * "Kasse": a sale at the counter. Tap dishes, "Kassieren", Bar or Karte, the
 * receipt. See [Till] for what is recorded when, and where.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TillScreen(onBack: () -> Unit, model: TillViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val view by model.view.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.look() }
    val sale = state.sale
    LaunchedEffect(sale?.id, state.step) { if (state.step == TillStep.DONE) model.awaitReceipt() }
    var aborting by remember { mutableStateOf(false) }

    val leave = {
        // A sale that is paid is over; an open one stays where it is.
        if (state.step == TillStep.DONE) model.next()
        onBack()
    }
    BackHandler { if (state.step == TillStep.CASH) model.back() else leave() }

    Zoomed {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.till_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = leave) { Text("✕", style = MaterialTheme.typography.titleLarge) } },
                    actions = {
                        if (state.step == TillStep.DISHES) {
                            FilterChip(
                                selected = state.training,
                                onClick = { model.setTraining(!state.training) },
                                enabled = !state.off,
                                label = { Text(stringResource(R.string.till_training)) },
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.training || sale?.training == true) TillStrip(stringResource(R.string.till_training_strip))
                state.problem?.let { TillProblemCard(it, onDismiss = model::dismissProblem) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.step) {
                        TillStep.DISHES ->
                            when (state.available) {
                                false -> TillMessage(stringResource(R.string.till_unavailable))
                                else -> TillDishesPane(state, view, photos, model)
                            }
                        TillStep.PAY -> if (sale != null) TillPayPane(state, sale, model, onAbort = { aborting = true })
                        TillStep.CASH -> if (sale != null) TillCashPane(state, sale, model)
                        TillStep.CARD -> if (sale != null) TillCardPane(state, sale, model)
                        TillStep.DONE -> if (sale != null) TillDonePane(state, sale, view, model)
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(12.dp))
                }
            }
        }
    }

    if (aborting && sale != null && state.step == TillStep.PAY) {
        AlertDialog(
            onDismissRequest = { aborting = false },
            title = { Text(stringResource(R.string.till_abort_title, sale.number)) },
            text = { Text(stringResource(R.string.till_abort_text), style = MaterialTheme.typography.titleMedium) },
            confirmButton = {
                Button(onClick = {
                    aborting = false
                    model.abort()
                }) { Text(stringResource(R.string.till_abort_yes)) }
            },
            dismissButton = { TextButton(onClick = { aborting = false }) { Text(stringResource(R.string.cancel_back)) } },
        )
    }
}

/** A line across the top that cannot be missed. */
@Composable
private fun TillStrip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(8.dp),
    )
}

@Composable
private fun TillMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TillProblemCard(problem: TillProblem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onDismiss),
    ) {
        Text(tillProblemText(context, problem), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
    }
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
                }
            )
    }

// ---------------------------------------------------------------------------
// Tapping dishes
// ---------------------------------------------------------------------------

@Composable
private fun TillDishesPane(state: TillState, view: TillView, photos: Map<String, ImageBitmap?>, model: TillViewModel) {
    var picking by remember { mutableStateOf<MenuDish?>(null) }
    var basketUp by rememberSaveable { mutableStateOf(false) }
    val menu = view.menu
    val tap: (MenuDish, List<OptionGroup>) -> Unit = { dish, groups ->
        // Nothing to choose: in the basket with one tap.
        if (groups.isEmpty()) model.add(BasketLine(dish, 1)) else picking = dish
    }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val beside = maxWidth >= BASKET_BESIDE_FROM
            if (beside) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        TillNotices(state, model)
                        TillDishes(menu, view.menuError, state, photos, model, tap)
                    }
                    TillBasket(state, model, Modifier.width(360.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (basketUp && state.lines.isNotEmpty()) {
                        TillBasket(state, model, Modifier.weight(1f).fillMaxWidth())
                    } else {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            TillNotices(state, model)
                            TillDishes(menu, view.menuError, state, photos, model, tap)
                        }
                    }
                    HorizontalDivider()
                    val count = state.lines.sumOf { it.qty }
                    BottomBar {
                        BigButton(
                            if (basketUp && count > 0) stringResource(R.string.till_dishes)
                            else pluralStringResource(R.plurals.till_basket, count, count, Format.euro(CounterSales.euros(state.totalCents))),
                            onClick = { basketUp = !basketUp },
                            enabled = count > 0,
                            outlined = true,
                            modifier = Modifier.weight(1f),
                        )
                        // Where it is eaten is asked beside it, in the basket: there first.
                        if (!basketUp) BigButton(stringResource(R.string.till_to_basket), onClick = { basketUp = true }, enabled = count > 0)
                    }
                }
            }
        }
        val dish = picking
        if (dish != null && menu != null) {
            val groups = remember(dish, menu) { OptionRules.groups(dish, menu.groups) }
            OptionsPanel(dish, groups, onDismiss = { picking = null }) {
                model.add(it)
                picking = null
            }
        }
    }
}

@Composable
private fun TillDishes(
    menu: OrderMenu?,
    error: String?,
    state: TillState,
    photos: Map<String, ImageBitmap?>,
    model: TillViewModel,
    onTap: (MenuDish, List<OptionGroup>) -> Unit,
) {
    when {
        menu != null -> DishGrid(menu, state.lines, photos, model::loadPhoto, onTap)
        error != null ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusLine(stringResource(R.string.new_order_menu_failed, error), bad = true)
                BigButton(stringResource(R.string.retry), onClick = model::loadMenu, outlined = true)
            }
        else ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.new_order_menu_loading), style = MaterialTheme.typography.titleLarge)
            }
    }
}

/** The till is off, and the sales somebody has to see to. */
@Composable
private fun TillNotices(state: TillState, model: TillViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        if (state.off) StatusLine(stringResource(R.string.till_off))
        for (sale in state.open) OpenSaleCard(sale, enabled = !state.busy) { model.resume(sale) }
    }
}

/** A sale rung up and not finished, here or on another device. */
@Composable
internal fun OpenSaleCard(sale: CounterSale, enabled: Boolean, onResume: () -> Unit) {
    Card(
        onClick = onResume,
        enabled = enabled,
        colors =
            if (sale.stale) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (sale.training) R.string.till_open_training else R.string.till_open_sale, sale.number, Format.euro(sale.total)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val at = sale.openedAt?.let { if (sale.stale) Format.day(it) + " " + Format.clock(it) else Format.clock(it) }.orEmpty()
                Text(
                    listOfNotNull(at.ifEmpty { null }, sale.openedOn?.ifEmpty { null }, sale.openedBy.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (sale.cardPending) Text(stringResource(R.string.till_card_pending), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (sale.stale) Text(stringResource(R.string.till_open_stale), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(R.string.till_resume), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TillBasket(state: TillState, model: TillViewModel, modifier: Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.lines.isEmpty()) {
                Text(stringResource(R.string.till_empty), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.lines.forEachIndexed { index, line ->
                Column {
                    Text(line.dish.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (line.options.isNotEmpty()) Text(line.options.joinToString(", ") { it.name }, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.euro(line.unitPrice * line.qty), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        BigStepper(line.qty) { model.setQty(index, it) }
                    }
                }
                HorizontalDivider()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TillWhereChip(stringResource(R.string.till_takeaway), !state.dineIn, Modifier.weight(1f)) { model.setDineIn(false) }
            TillWhereChip(stringResource(R.string.till_dine_in), state.dineIn, Modifier.weight(1f)) { model.setDineIn(true) }
        }
        val price = state.price
        if (state.priced && price != null && price.dealDiscount > 0) BigPriceLine(stringResource(R.string.new_order_deal), -price.dealDiscount)
        BigPriceLine(stringResource(R.string.new_order_total), CounterSales.euros(state.totalCents), big = true)
        BigButton(
            stringResource(R.string.till_ring_up, Format.euro(CounterSales.euros(state.totalCents))),
            onClick = model::ringUp,
            enabled = state.lines.isNotEmpty() && !state.busy,
            modifier = Modifier.fillMaxWidth().testTag(TILL_RING_UP_TAG),
        )
        if (state.lines.isNotEmpty()) {
            TextButton(onClick = model::clear, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.till_clear))
            }
        }
    }
}

const val TILL_RING_UP_TAG = "till-ring-up"

@Composable
private fun TillWhereChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        modifier = modifier.heightIn(min = 52.dp),
    )
}

// ---------------------------------------------------------------------------
// The money
// ---------------------------------------------------------------------------

/** A step about one sale: its number and amount on top, what to tap below. */
@Composable
private fun TillSalePage(sale: CounterSale, caption: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.till_sale, sale.number), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(caption, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(Format.euro(sale.total), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        content()
    }
}

@Composable
private fun TillPayPane(state: TillState, sale: CounterSale, model: TillViewModel, onAbort: () -> Unit) {
    TillSalePage(sale, stringResource(if (sale.dineIn) R.string.till_dine_in else R.string.till_takeaway)) {
        if (state.declined) StatusLine(stringResource(R.string.till_declined), bad = true)
        Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton(stringResource(R.string.till_cash), onClick = model::chooseCash, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 110.dp))
            BigButton(stringResource(R.string.till_card), onClick = model::chooseCard, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 110.dp))
        }
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (line in sale.lines) {
                val name = if (line.options.isEmpty()) line.name else "${line.name} (${line.options.joinToString(", ")})"
                BigPriceLine("${line.qty}× $name", line.price * line.qty)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAbort, enabled = !state.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.till_abort)) }
            OutlinedButton(onClick = model::park, enabled = !state.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.till_park)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TillCashPane(state: TillState, sale: CounterSale, model: TillViewModel) {
    val total = CounterSales.cents(sale.total)
    TillSalePage(sale, stringResource(R.string.till_cash_title)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { model.setGiven("") }, modifier = Modifier.heightIn(min = 60.dp)) {
                Text(stringResource(R.string.till_exact), style = MaterialTheme.typography.titleLarge)
            }
            for (note in CounterSales.notes(total)) {
                OutlinedButton(onClick = { model.setGiven(tillPlain(note)) }, modifier = Modifier.heightIn(min = 60.dp)) {
                    Text(Format.euro(CounterSales.euros(note)), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        OutlinedTextField(
            value = state.given,
            onValueChange = model::setGiven,
            label = { Text(stringResource(R.string.till_given)) },
            textStyle = MaterialTheme.typography.headlineMedium,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        )
        val change = state.changeCents
        if (change != null) {
            Text(
                stringResource(R.string.till_change, Format.euro(CounterSales.euros(change))),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = toneColor(Tone.OK),
            )
        } else if (state.given.isNotBlank()) {
            StatusLine(stringResource(R.string.till_not_enough), bad = true)
        }
        BigButton(
            stringResource(R.string.till_cash_paid),
            onClick = model::cashPaid,
            enabled = state.cashReady && !state.busy,
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).heightIn(min = 88.dp),
        )
        OutlinedButton(onClick = model::back, enabled = !state.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.cancel_back)) }
    }
}

/** "20" or "12,50": an amount as it would be typed. */
private fun tillPlain(cents: Long): String = if (cents % 100 == 0L) "${cents / 100}" else "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"

@Composable
private fun TillCardPane(state: TillState, sale: CounterSale, model: TillViewModel) {
    TillSalePage(sale, stringResource(R.string.till_card_title)) {
        Text(stringResource(R.string.till_card_text), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 720.dp))
        Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton(stringResource(R.string.till_card_declined), onClick = model::cardDeclined, enabled = !state.busy, outlined = true, modifier = Modifier.weight(1f).heightIn(min = 110.dp))
            BigButton(stringResource(R.string.till_card_paid), onClick = model::cardPaid, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 110.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// The receipt
// ---------------------------------------------------------------------------

@Composable
private fun TillDonePane(state: TillState, sale: CounterSale, view: TillView, model: TillViewModel) {
    val method = stringResource(if (sale.payment == CounterPayment.CARD) R.string.pay_card else R.string.pay_cash)
    TillSalePage(sale, stringResource(R.string.till_paid, method)) {
        state.changeCents?.takeIf { it > 0 && sale.payment == CounterPayment.CASH }?.let {
            Text(
                stringResource(R.string.till_change, Format.euro(CounterSales.euros(it))),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = toneColor(Tone.OK),
            )
        }
        val receipt = sale.receipt
        if (receipt == null) {
            StatusLine(stringResource(R.string.till_no_receipt))
        } else {
            TillReceiptState(receipt, state.stoppedWaiting)
            QrImage(receipt.link, stringResource(R.string.till_qr))
            Text(stringResource(R.string.till_scan), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        view.printError?.let { StatusLine(it, bad = true) }
        if (view.printed) StatusLine(stringResource(R.string.till_printed), good = true)
        Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton(
                stringResource(if (view.printing) R.string.till_printing else R.string.till_print),
                onClick = model::printReceipt,
                enabled = model.canPrint && !view.printing && sale.orderId != null,
                outlined = true,
                modifier = Modifier.weight(1f),
            )
            BigButton(stringResource(R.string.till_next), onClick = model::next, enabled = !state.busy, modifier = Modifier.weight(1f))
        }
        if (!model.canPrint) Text(stringResource(R.string.till_no_printer), style = MaterialTheme.typography.bodyLarge)
    }
}

/** What the receipt says about its signature, in short. */
@Composable
private fun TillReceiptState(receipt: CounterReceipt, stoppedWaiting: Boolean) {
    when {
        receipt.training -> StatusLine(stringResource(R.string.till_receipt_training))
        receipt.signed && receipt.outage -> StatusLine(stringResource(R.string.till_receipt_late))
        receipt.signed -> StatusLine(stringResource(R.string.till_receipt_signed), good = true)
        receipt.pending && !receipt.outage && !stoppedWaiting ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(stringResource(R.string.till_receipt_pending), style = MaterialTheme.typography.titleLarge)
            }
        // The sale stands: the receipt says the TSE did not answer, and the signature follows.
        else -> StatusLine(stringResource(R.string.till_receipt_outage), bad = true)
    }
    if (receipt.env == "TEST") StatusLine(stringResource(R.string.till_receipt_test))
}

/** A QR code, black on white whatever the theme, with the border it needs. */
@Composable
internal fun QrImage(text: String, description: String) {
    val qr = remember(text) { Qr.of(text) }
    Canvas(
        Modifier.size(240.dp).background(Color.White).padding(16.dp).semantics { contentDescription = description }
    ) {
        val side = size.minDimension / qr.size
        for (row in 0 until qr.size) {
            for (column in 0 until qr.size) {
                if (qr.dark(row, column)) drawRect(Color.Black, Offset(column * side, row * side), Size(side, side))
            }
        }
    }
}
