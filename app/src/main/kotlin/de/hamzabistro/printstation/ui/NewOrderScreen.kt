package de.hamzabistro.printstation.ui

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AddressCheck
import de.hamzabistro.printstation.core.AddressStatus
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.DraftProblem
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.Fulfilment
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OptionRules
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.OrderSource
import de.hamzabistro.printstation.core.PhoneOrderDraft
import de.hamzabistro.printstation.core.PhoneOrderError
import de.hamzabistro.printstation.core.PhoneOrderException
import de.hamzabistro.printstation.core.PhoneOrderQuote
import de.hamzabistro.printstation.core.PhoneOrders
import de.hamzabistro.printstation.core.Selection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What "Neue Bestellung" shows. */
data class NewOrderState(
    val menu: OrderMenu? = null,
    val menuError: String? = null,
    val draft: PhoneOrderDraft = PhoneOrderDraft(),
    /** check-address's answer for [checkedAddress]. */
    val address: AddressCheck? = null,
    /** The address [address] is the answer for: street, postcode, town. */
    val checkedAddress: List<String>? = null,
    val checking: Boolean = false,
    val addressError: String? = null,
    /** The dry run's price for [quotedDraft]. */
    val quote: PhoneOrderQuote? = null,
    val quotedDraft: PhoneOrderDraft? = null,
    val quoting: Boolean = false,
    /** Why the dry run or the save was refused, in words. */
    val error: String? = null,
    /** The minutes picked for an order for now; null: the suggestion. */
    val minutes: Int? = null,
    val saving: Boolean = false,
    /** The order as saved: the screen says so and goes back. */
    val saved: PhoneOrderQuote? = null,
) {
    /** The price shown is the one for what is on the screen now. */
    val priced: Boolean
        get() = quote != null && quotedDraft == draft
}

/**
 * "Neue Bestellung" (#9): a phone or walk-in order typed in on the tablet,
 * priced by the database's own staff_place_order with a dry run, and saved
 * already accepted — straight into "In der Küche", where it prints like any
 * accepted order. A delivery's address goes through the site's own
 * check-address first, as the checkout's does.
 */
class NewOrderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow(NewOrderState())
    val state: StateFlow<NewOrderState> = _state.asStateFlow()

    private var pricing: Job? = null

    /** On every visit, so what sold out since the last order shows; the menu last read stays up meanwhile. */
    fun loadMenu() {
        _state.update { it.copy(menuError = null) }
        viewModelScope.launch {
            val menu = attempt(app, { error -> _state.update { it.copy(menuError = error) } }) { graph.phoneOrders.menu() } ?: return@launch
            _state.update { it.copy(menu = menu) }
        }
    }

    /** Any change to the order: the price shown is no longer it, and is worked out again after a pause in the typing. */
    fun edit(change: (PhoneOrderDraft) -> PhoneOrderDraft) {
        _state.update { it.copy(draft = change(it.draft), error = null) }
        reprice()
    }

    fun add(line: BasketLine) =
        edit { draft ->
            // The same dish with the same choices and note is one line with more of it, as in the site's basket.
            val same = draft.lines.indexOfFirst { it.dish.id == line.dish.id && it.options == line.options && it.note.trim() == line.note.trim() }
            if (same < 0) draft.copy(lines = draft.lines + line)
            else
                draft.copy(
                    lines = draft.lines.mapIndexed { i, it -> if (i == same) it.copy(qty = (it.qty + line.qty).coerceAtMost(PhoneOrders.MAX_QTY)) else it }
                )
        }

    fun setQty(index: Int, qty: Int) =
        edit { draft ->
            draft.copy(
                lines =
                    if (qty <= 0) draft.lines.filterIndexed { i, _ -> i != index }
                    else draft.lines.mapIndexed { i, it -> if (i == index) it.copy(qty = qty.coerceAtMost(PhoneOrders.MAX_QTY)) else it }
            )
        }

    /** Priced again too: whether the kitchen is full depends on the slot the minutes land in. */
    fun pickMinutes(minutes: Int) {
        _state.update { it.copy(minutes = minutes) }
        reprice(atOnce = true)
    }

    /** The minutes pre-selected for an order for now: the incoming order's rule, the ring from the price or the address check. */
    fun suggestedMinutes(state: NewOrderState): Int {
        val zone = state.quote?.deliveryZone ?: state.address?.zone
        val busy = graph.shopView.value.hours?.busyMinutes(Instant.now()) ?: 0
        return PhoneOrders.suggestedEta(state.draft, zone, graph.queue.state.value.prep, busy)
    }

    fun ladder(): List<Int> = graph.settings.device.value.etaLadder

    fun checkAgain() {
        _state.update { it.copy(checkedAddress = null, address = null) }
        reprice(atOnce = true)
    }

    private fun reprice(atOnce: Boolean = false) {
        pricing?.cancel()
        pricing =
            viewModelScope.launch {
                if (!atOnce) delay(PRICE_PAUSE_MS)
                val draft = _state.value.draft
                if (PhoneOrders.problems(draft, Instant.now()).isNotEmpty()) {
                    _state.update { it.copy(quoting = false, checking = false) }
                    return@launch
                }
                if (draft.delivery && !checkAddress(draft)) return@launch
                _state.update { it.copy(quoting = true) }
                // With the minutes, so a full kitchen is said before saving rather than after.
                val minutes = if (draft.scheduledFor == null) _state.value.let { it.minutes ?: suggestedMinutes(it) } else null
                try {
                    val quote = graph.phoneOrders.place(draft, minutes, null, dryRun = true)
                    _state.update { if (it.draft == draft) it.copy(quote = quote, quotedDraft = draft, quoting = false) else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(quoting = false, quote = null, quotedDraft = null, error = failureText(app, e)) }
                }
            }
    }

    /** check-address for [draft]'s address, once per address typed; false when it could not be asked. */
    private suspend fun checkAddress(draft: PhoneOrderDraft): Boolean {
        val key = listOf(draft.street.trim(), draft.postalCode.trim(), draft.city.trim())
        if (_state.value.checkedAddress == key) return true
        _state.update { it.copy(checking = true, addressError = null) }
        return try {
            val check = graph.phoneOrders.checkAddress(draft.street, draft.postalCode, draft.city)
            _state.update { it.copy(checking = false, address = check, checkedAddress = key) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(checking = false, address = null, checkedAddress = null, addressError = failureText(app, e)) }
            false
        }
    }

    /** Saved for real with the price shown, so a price that changed meanwhile is refused (HB409), not charged. */
    fun save() {
        val now = _state.value
        val quote = now.quote ?: return
        if (!now.priced || now.saving) return
        val draft = now.draft
        val minutes = if (draft.scheduledFor == null) now.minutes ?: suggestedMinutes(now) else null
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = graph.phoneOrders.place(draft, minutes, quote.total, dryRun = false)
                _state.update { it.copy(saving = false, saved = saved) }
                // On every device through Realtime anyway; here at once.
                graph.queue.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = failureText(app, e)) }
                // The new price, to look at before saving again.
                if (e is PhoneOrderException && e.reason == PhoneOrderError.TOTAL_CHANGED) {
                    _state.update { it.copy(quote = null, quotedDraft = null) }
                    reprice(atOnce = true)
                }
            }
        }
    }

    /** "Noch eine Bestellung": an empty form, the menu kept. */
    fun reset() {
        pricing?.cancel()
        _state.update { NewOrderState(menu = it.menu) }
    }

    private companion object {
        const val PRICE_PAUSE_MS = 600L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewOrderScreen(onBack: () -> Unit, model: NewOrderViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var discarding by remember { mutableStateOf(false) }
    val leave = {
        if (state.draft.lines.isEmpty() || state.saved != null) {
            model.reset()
            onBack()
        } else discarding = true
    }
    BackHandler(onBack = leave)
    LaunchedEffect(Unit) { model.loadMenu() }

    var picking by remember { mutableStateOf<MenuDish?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_order_title)) },
                navigationIcon = { TextButton(onClick = leave) { Text("←") } },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= 840.dp) {
                // The tablet: the menu on the left, the order on the right, as on a till.
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).fillMaxSize()) { DishList(state, model::loadMenu) { picking = it } }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        orderForm(state, model, showMenuButton = false) {}
                    }
                }
            } else {
                var menuOpen by rememberSaveable { mutableStateOf(false) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    orderForm(state, model, showMenuButton = true) { menuOpen = true }
                }
                if (menuOpen) {
                    Dialog(onDismissRequest = { menuOpen = false }) {
                        Card(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
                            DishList(state, model::loadMenu) {
                                menuOpen = false
                                picking = it
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { dish ->
        val groups = remember(dish, state.menu) { OptionRules.groups(dish, state.menu?.groups.orEmpty()) }
        DishDialog(dish, groups, onDismiss = { picking = null }) {
            model.add(it)
            picking = null
        }
    }

    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            text = { Text(stringResource(R.string.new_order_discard)) },
            confirmButton = {
                Button(onClick = {
                    discarding = false
                    model.reset()
                    onBack()
                }) { Text(stringResource(R.string.new_order_discard_yes)) }
            },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(R.string.cancel_back)) } },
        )
    }

    state.saved?.let { saved ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.new_order_saved_title, saved.orderNumber ?: 0L)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.new_order_saved))
                    if (saved.overCapacity) Text(stringResource(R.string.new_order_saved_full), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(onClick = {
                    model.reset()
                    onBack()
                }) { Text(stringResource(R.string.new_order_done)) }
            },
            dismissButton = { TextButton(onClick = model::reset) { Text(stringResource(R.string.new_order_another)) } },
        )
    }
}

/** The menu by category, with a search: a tap opens the dish's choices. */
@Composable
private fun DishList(state: NewOrderState, onRetry: () -> Unit, onPick: (MenuDish) -> Unit) {
    val menu = state.menu
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        when {
            menu != null -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.new_order_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                val shown = menu.dishes.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((category, dishes) in shown.groupBy { it.category }) {
                        item(key = "c-$category") {
                            Text(category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(dishes, key = { "d-${it.id}" }) { dish ->
                            val orderable = OptionRules.orderable(dish, OptionRules.groups(dish, menu.groups))
                            DishRow(dish, orderable) { onPick(dish) }
                        }
                    }
                }
            }
            state.menuError != null ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.new_order_menu_failed, state.menuError), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            else ->
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.new_order_menu_loading))
                }
        }
    }
}

@Composable
private fun DishRow(dish: MenuDish, orderable: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = orderable, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                dish.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (orderable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (orderable) Format.euro(dish.price) else stringResource(R.string.new_order_sold_out),
                style = MaterialTheme.typography.bodyMedium,
                color = if (orderable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The site's options dialog: the dish's groups in order, the quantity and the dish's note. */
@Composable
private fun DishDialog(dish: MenuDish, groups: List<OptionGroup>, onDismiss: () -> Unit, onAdd: (BasketLine) -> Unit) {
    var chosen by remember(dish.id) { mutableStateOf(OptionRules.preset(groups)) }
    var qty by remember(dish.id) { mutableIntStateOf(1) }
    var note by remember(dish.id) { mutableStateOf("") }
    val options = OptionRules.chosen(groups, chosen)
    val valid = OptionRules.invalid(groups, chosen).isEmpty()
    val line = BasketLine(dish, qty, options, note)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dish.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (group in groups) {
                    Column {
                        Text(
                            "${group.name} · " +
                                stringResource(if (group.selection == Selection.SINGLE) R.string.new_order_single_hint else R.string.new_order_multiple_hint),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        for (option in group.options) {
                            val on = option.id in chosen[group.id].orEmpty()
                            val label = buildString {
                                append(option.name)
                                if (option.price > 0) append(" (+").append(Format.euro(option.price)).append(")")
                                if (!option.available) append(" — ")
                            }
                            val toggle = Modifier.fillMaxWidth().let {
                                if (group.selection == Selection.SINGLE)
                                    it.selectable(selected = on, enabled = option.available, role = Role.RadioButton) { chosen = OptionRules.toggle(group, chosen, option) }
                                else it.toggleable(value = on, enabled = option.available, role = Role.Checkbox) { chosen = OptionRules.toggle(group, chosen, option) }
                            }
                            Row(toggle, verticalAlignment = Alignment.CenterVertically) {
                                if (group.selection == Selection.SINGLE) RadioButton(selected = on, onClick = null, enabled = option.available)
                                else Checkbox(checked = on, onCheckedChange = null, enabled = option.available)
                                Text(label, modifier = Modifier.padding(start = 4.dp))
                                if (!option.available) Text(stringResource(R.string.new_order_sold_out), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.new_order_qty), modifier = Modifier.weight(1f))
                    Stepper(qty, onChange = { qty = it.coerceIn(1, PhoneOrders.MAX_QTY) })
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(PhoneOrders.NOTE_MAX) },
                    label = { Text(stringResource(R.string.new_order_line_note)) },
                    supportingText = { Text(stringResource(R.string.new_order_line_note_count, note.length, PhoneOrders.NOTE_MAX)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(line) }, enabled = valid) { Text(stringResource(R.string.new_order_add, Format.euro(line.unitPrice * qty))) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(value - 1) }) { Text("−") }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onChange(value + 1) }) { Text("+") }
    }
}

/** The order: how it came in, the basket, who and where, when, the price, and saving it. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.orderForm(state: NewOrderState, model: NewOrderViewModel, showMenuButton: Boolean, onMenu: () -> Unit) {
    val draft = state.draft
    item(key = "how") {
        Section {
            Text(stringResource(R.string.new_order_source), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (source in OrderSource.entries) {
                    FilterChip(
                        selected = draft.source == source,
                        onClick = { model.edit { it.copy(source = source) } },
                        label = { Text(stringResource(if (source == OrderSource.PHONE) R.string.source_phone else R.string.source_counter)) },
                    )
                }
            }
            Text(stringResource(R.string.new_order_fulfilment), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (fulfilment in Fulfilment.entries) {
                    FilterChip(
                        selected = draft.fulfilment == fulfilment,
                        onClick = { model.edit { it.copy(fulfilment = fulfilment) } },
                        label = { Text(stringResource(fulfilmentLabel(fulfilment))) },
                    )
                }
            }
        }
    }

    item(key = "basket") {
        Section(stringResource(R.string.new_order_basket)) {
            if (draft.lines.isEmpty()) Text(stringResource(R.string.new_order_basket_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            draft.lines.forEachIndexed { index, line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(line.dish.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        if (line.options.isNotEmpty()) Text(line.options.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodyMedium)
                        line.note.trim().takeIf { it.isNotEmpty() }?.let { Text("„$it“", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        Text(Format.euro(line.unitPrice * line.qty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Stepper(line.qty) { model.setQty(index, it) }
                }
                HorizontalDivider()
            }
            if (showMenuButton) OutlinedButton(onClick = onMenu, modifier = Modifier.fillMaxWidth()) { Text("+ " + stringResource(R.string.new_order_add_dish)) }
        }
    }

    item(key = "customer") {
        Section(stringResource(R.string.new_order_customer)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { v -> model.edit { it.copy(name = v) } },
                label = { Text(stringResource(R.string.new_order_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.phone,
                onValueChange = { v -> model.edit { it.copy(phone = v) } },
                label = { Text(stringResource(R.string.new_order_phone)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!draft.fulfilment.needsContact) {
                Text(stringResource(R.string.new_order_contact_optional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (draft.delivery) {
        item(key = "address") { AddressSection(state, model) }
    }

    item(key = "time") { TimeSection(state, model) }

    item(key = "notes") {
        Section {
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { v -> model.edit { it.copy(notes = v) } },
                label = { Text(stringResource(R.string.new_order_kitchen_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.delivery) {
                Row(
                    Modifier.fillMaxWidth().toggleable(value = draft.waiveFees, role = Role.Checkbox) { on -> model.edit { it.copy(waiveFees = on) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = draft.waiveFees, onCheckedChange = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.new_order_waive))
                        Text(stringResource(R.string.new_order_waive_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    item(key = "price") { PriceSection(state, model) }
}

@Composable
private fun Section(title: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            content()
        }
    }
}

private fun fulfilmentLabel(fulfilment: Fulfilment): Int =
    when (fulfilment) {
        Fulfilment.PICKUP -> R.string.fulfilment_pickup
        Fulfilment.DINE_IN -> R.string.fulfilment_dine_in
        Fulfilment.DELIVERY -> R.string.fulfilment_delivery
    }

@Composable
private fun AddressSection(state: NewOrderState, model: NewOrderViewModel) {
    val draft = state.draft
    Section(stringResource(R.string.new_order_address)) {
        OutlinedTextField(
            value = draft.street,
            onValueChange = { v -> model.edit { it.copy(street = v) } },
            label = { Text(stringResource(R.string.new_order_street)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.postalCode,
                onValueChange = { v -> model.edit { it.copy(postalCode = v.filter(Char::isDigit).take(5)) } },
                label = { Text(stringResource(R.string.new_order_postal_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.city,
                onValueChange = { v -> model.edit { it.copy(city = v) } },
                label = { Text(stringResource(R.string.new_order_city)) },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
        }
        OutlinedTextField(
            value = draft.addressNote,
            onValueChange = { v -> model.edit { it.copy(addressNote = v) } },
            label = { Text(stringResource(R.string.new_order_door_note)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val address = state.address
        when {
            state.checking -> Text(stringResource(R.string.new_order_checking_address), color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.addressError != null -> {
                Text(stringResource(R.string.new_order_address_failed, state.addressError), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = model::checkAgain) { Text(stringResource(R.string.new_order_check_again)) }
            }
            address != null -> AddressLine(address, model)
        }
    }
}

@Composable
private fun AddressLine(address: AddressCheck, model: NewOrderViewModel) {
    val resources = LocalResources.current
    when {
        address.outside -> Text(stringResource(R.string.new_order_address_outside), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        address.status == AddressStatus.VERIFIED ->
            Text(stringResource(R.string.new_order_address_ok, ringName(resources, address.zone)), color = MaterialTheme.colorScheme.primary)
        address.status == AddressStatus.UNVERIFIED -> Text(stringResource(R.string.new_order_address_unverified), color = toneColor(Tone.SOON))
        else -> {
            Text(stringResource(R.string.new_order_address_unchecked), color = toneColor(Tone.SOON))
            OutlinedButton(onClick = model::checkAgain) { Text(stringResource(R.string.new_order_check_again)) }
        }
    }
    address.suggestedPostalCode?.let { code ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.new_order_address_suggest, code), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { model.edit { it.copy(postalCode = code) } }) { Text(stringResource(R.string.new_order_address_use, code)) }
        }
    }
}

/** The ring's name as the report says it, or the ring's own word. */
private fun ringName(resources: Resources, zone: String?): String =
    when (zone) {
        "inner" -> resources.getString(R.string.report_ring_inner)
        "near" -> resources.getString(R.string.report_ring_near)
        "far" -> resources.getString(R.string.report_ring_far)
        "edge" -> resources.getString(R.string.report_ring_edge)
        else -> zone.orEmpty()
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeSection(state: NewOrderState, model: NewOrderViewModel) {
    val context = LocalContext.current
    val draft = state.draft
    Section(stringResource(R.string.new_order_time)) {
        Column {
            Row(
                Modifier.fillMaxWidth().selectable(selected = draft.scheduledFor == null, role = Role.RadioButton) { model.edit { it.copy(scheduledFor = null) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = draft.scheduledFor == null, onClick = null)
                Text(stringResource(R.string.new_order_asap), modifier = Modifier.padding(start = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().selectable(selected = draft.scheduledFor != null, role = Role.RadioButton) {
                    pickDateTime(context, draft.scheduledFor) { at -> model.edit { it.copy(scheduledFor = at) } }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = draft.scheduledFor != null, onClick = null)
                Text(
                    draft.scheduledFor?.let { Format.slot(context, it) } ?: stringResource(R.string.new_order_later),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (draft.scheduledFor != null) {
                TextButton(onClick = { pickDateTime(context, draft.scheduledFor) { at -> model.edit { it.copy(scheduledFor = at) } } }) {
                    Text(stringResource(R.string.new_order_pick_time))
                }
            }
        }
        if (draft.scheduledFor == null) {
            // The minutes, as on an incoming order: the suggestion filled in, the round numbers beside it.
            val suggested = model.suggestedMinutes(state)
            val picked = state.minutes ?: suggested
            Text(stringResource(R.string.new_order_minutes), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (minutes in Eta.options(suggested, model.ladder())) {
                    FilterChip(
                        selected = minutes == picked,
                        onClick = { model.pickMinutes(minutes) },
                        label = { Text(stringResource(R.string.eta_button, minutes)) },
                    )
                }
            }
        }
    }
}

/** A Leipzig date from today on, then a time on it; kept to the next seven days by the form's check. */
private fun pickDateTime(context: Context, current: Instant?, onPicked: (Instant) -> Unit) {
    val zone = AlarmPolicy.LEIPZIG
    val start = (current ?: Instant.now().plus(1, ChronoUnit.HOURS)).atZone(zone)
    val dialog =
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val date = LocalDate.of(year, month + 1, day)
                TimePickerDialog(
                        context,
                        { _, hour, minute -> onPicked(date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()) },
                        start.hour,
                        start.minute,
                        true,
                    )
                    .show()
            },
            start.year,
            start.monthValue - 1,
            start.dayOfMonth,
        )
    dialog.datePicker.minDate = System.currentTimeMillis() - 1000
    dialog.datePicker.maxDate = Instant.now().plus(PhoneOrders.MAX_AHEAD).toEpochMilli()
    dialog.show()
}

@Composable
private fun PriceSection(state: NewOrderState, model: NewOrderViewModel) {
    val resources = LocalResources.current
    val draft = state.draft
    Section(stringResource(R.string.new_order_price)) {
        val problems = PhoneOrders.problems(draft, Instant.now())
        val quote = state.quote
        when {
            problems.isNotEmpty() ->
                Text(
                    stringResource(R.string.new_order_missing, problems.joinToString(", ") { resources.getString(problemLabel(it)) }),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            state.quoting || state.checking ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.width(20.dp))
                    Text(stringResource(R.string.new_order_pricing))
                }
            quote != null && state.priced -> Breakdown(quote)
            state.error == null -> Text(stringResource(R.string.new_order_price_pending), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.padding(top = 4.dp))
        Button(
            onClick = model::save,
            enabled = state.priced && !state.saving && !state.quoting && problems.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    state.saving -> stringResource(R.string.new_order_saving)
                    quote != null && state.priced -> stringResource(R.string.new_order_save, Format.euro(quote.total))
                    else -> stringResource(R.string.new_order_save, "—")
                }
            )
        }
    }
}

@Composable
private fun Breakdown(quote: PhoneOrderQuote) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PriceLine(stringResource(R.string.new_order_subtotal), quote.subtotal)
        if (quote.dealDiscount > 0) PriceLine(stringResource(R.string.new_order_deal), -quote.dealDiscount, note = true)
        if (quote.pickupDiscount > 0) PriceLine(stringResource(R.string.new_order_pickup_discount), -quote.pickupDiscount)
        if (quote.deliveryFee > 0) PriceLine(stringResource(R.string.new_order_delivery_fee), quote.deliveryFee)
        if (quote.smallOrderFee > 0) PriceLine(stringResource(R.string.new_order_small_fee), quote.smallOrderFee)
        if (quote.waived > 0) PriceLine(stringResource(R.string.new_order_waived), -quote.waived, note = true)
        HorizontalDivider()
        PriceLine(stringResource(R.string.new_order_total), quote.total, bold = true)
        if (quote.deposits > 0) {
            Text(stringResource(R.string.new_order_deposits, Format.euro(quote.deposits)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (quote.overCapacity) {
            Text(stringResource(R.string.new_order_over_capacity), color = toneColor(Tone.SOON), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** [note]: already inside the line above (the deal is in the dishes' prices; the waiver is not charged), shown for information. */
@Composable
private fun PriceLine(label: String, amount: Double, bold: Boolean = false, note: Boolean = false) {
    Row {
        Text(label, modifier = Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else null, color = if (note) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Text(Format.euro(amount), fontWeight = if (bold) FontWeight.Bold else null, color = if (note) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

private fun problemLabel(problem: DraftProblem): Int =
    when (problem) {
        DraftProblem.EMPTY -> R.string.new_order_missing_basket
        DraftProblem.NAME -> R.string.new_order_missing_name
        DraftProblem.PHONE -> R.string.new_order_missing_phone
        DraftProblem.STREET -> R.string.new_order_missing_street
        DraftProblem.POSTAL_CODE -> R.string.new_order_missing_postal_code
        DraftProblem.CITY -> R.string.new_order_missing_city
        DraftProblem.TIME -> R.string.new_order_missing_time
    }
