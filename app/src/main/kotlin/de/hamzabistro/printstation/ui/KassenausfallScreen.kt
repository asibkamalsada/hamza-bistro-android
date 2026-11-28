package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.CardTerminal
import de.hamzabistro.printstation.core.CashUpText
import de.hamzabistro.printstation.core.CounterPayment
import de.hamzabistro.printstation.core.CounterSales
import de.hamzabistro.printstation.core.Kassenausfall
import de.hamzabistro.printstation.core.Kassenbuch
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.MenuOption
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.OutageDraft
import de.hamzabistro.printstation.core.OptionRules
import de.hamzabistro.printstation.core.Selection
import de.hamzabistro.printstation.core.TillOutage
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The Kassenausfall (hamza-bistro-web 20261128140000_kassenausfall): when the
// till could not be reached and staff wrote numbered paper Notbelege. Once it
// is back — the same day, before the Kassensturz — the outage note is
// reported here, then each Notbeleg is entered with the time on the paper,
// its dishes, the amount on the paper and how it was paid. The till signs it
// now and marks it as entered late. The site's /orders has the same.
//
// The app does NOT sell without a connection: offline, a device would be a
// till of its own. This screen is for afterwards.

/** The outage form. */
data class OutageForm(
    /** Made when the form opened; a correction keeps the note's own. */
    val id: String,
    val correcting: Boolean,
    val draft: OutageDraft,
)

/** One Notbeleg being entered. */
data class SlipForm(
    /** Made when the form opened: a tap again after a lost answer is the same sale. */
    val id: String,
    val outage: TillOutage,
    val slip: Int?,
    val time: String = "",
    val dineIn: Boolean = false,
    val search: String = "",
    val dish: MenuDish? = null,
    val options: List<MenuOption> = emptyList(),
    val lines: List<BasketLine> = emptyList(),
    /** What the till charges for [pricedLines]. */
    val priced: Double? = null,
    val pricedLines: List<BasketLine>? = null,
    val paper: String = "",
    val method: CounterPayment? = null,
    val terminal: Int? = null,
)

data class KassenausfallState(
    val loading: Boolean = false,
    /** Null until read, or on a database without the Kassenausfall. */
    val outages: List<TillOutage>? = null,
    val error: String? = null,
    val done: String? = null,
    val busy: Boolean = false,
    val outageForm: OutageForm? = null,
    val slipForm: SlipForm? = null,
    val menu: OrderMenu? = null,
    val terminals: List<CardTerminal> = emptyList(),
) {
    val missing: List<Int>
        get() = Kassenausfall.missing(outages)
}

class KassenausfallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph
    private val backend = graph.kassenausfall

    private val _state = MutableStateFlow(KassenausfallState())
    val state: StateFlow<KassenausfallState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var failed = false
            val outages = attempt(app, { error -> failed = true; _state.update { it.copy(loading = false, error = error) } }) {
                backend.outages(null)
            }
            if (failed) return@launch
            _state.update {
                it.copy(
                    loading = false,
                    outages = outages,
                    error = if (outages == null) app.getString(R.string.special_needs_update, Kassenausfall.MIGRATION) else null,
                )
            }
        }
    }

    fun startReport() =
        _state.update {
            it.copy(
                outageForm = OutageForm(UUID.randomUUID().toString(), false, OutageDraft(to = Kassenausfall.clock(Instant.now()))),
                slipForm = null,
                done = null,
                error = null,
            )
        }

    fun startCorrection(o: TillOutage) =
        _state.update { it.copy(outageForm = OutageForm(o.id, true, Kassenausfall.draftOf(o)), slipForm = null, done = null, error = null) }

    fun draft(change: (OutageDraft) -> OutageDraft) =
        _state.update { s -> s.copy(outageForm = s.outageForm?.let { it.copy(draft = change(it.draft)) }) }

    fun cancel() = _state.update { it.copy(outageForm = null, slipForm = null, error = null) }

    fun report() {
        val form = _state.value.outageForm ?: return
        val req = Kassenausfall.request(form.draft, Kassenausfall.today()) ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var failed = false
            attempt(app, { error -> failed = true; _state.update { it.copy(busy = false, error = error) } }) { backend.report(form.id, req) }
            if (failed) return@launch
            _state.update { it.copy(busy = false, outageForm = null, done = app.getString(R.string.ka_reported)) }
            load()
        }
    }

    fun startSlip(o: TillOutage) {
        _state.update {
            it.copy(
                slipForm = SlipForm(UUID.randomUUID().toString(), o, o.missing.firstOrNull()),
                outageForm = null,
                done = null,
                error = null,
                terminals = graph.cardTerminals.value.filter { t -> t.active },
            )
        }
        if (_state.value.menu != null) return
        viewModelScope.launch {
            val menu = attempt(app, { error -> _state.update { it.copy(error = error) } }) { graph.phoneOrders.menu() } ?: return@launch
            _state.update { it.copy(menu = CounterSales.boardMenu(menu)) }
        }
    }

    fun slip(change: (SlipForm) -> SlipForm) = _state.update { s -> s.copy(slipForm = s.slipForm?.let(change)) }

    /** The dish picked from the search; one without choices goes straight in. */
    fun choose(dish: MenuDish) {
        slip { it.copy(dish = dish, options = emptyList(), search = "") }
        if (groups(dish).isEmpty()) addChosen()
    }

    fun groups(dish: MenuDish): List<OptionGroup> = OptionRules.groups(dish, _state.value.menu?.groups.orEmpty())

    fun toggle(group: OptionGroup, option: MenuOption) =
        slip { f ->
            val picked = f.options.any { it.id == option.id }
            val rest = if (group.selection == Selection.SINGLE) f.options.filter { o -> group.options.none { it.id == o.id } } else f.options
            f.copy(options = if (picked) f.options.filter { it.id != option.id } else rest + option)
        }

    fun addChosen() {
        val f = _state.value.slipForm ?: return
        val dish = f.dish ?: return
        val order = groups(dish).flatMap { g -> g.options.map { it.id } }
        val options = f.options.sortedBy { o -> order.indexOf(o.id) }
        slip { it.copy(dish = null, options = emptyList(), lines = it.lines + BasketLine(dish, 1, options)) }
        price()
    }

    fun bump(index: Int, by: Int) {
        slip { f ->
            f.copy(lines = f.lines.mapIndexedNotNull { i, l -> if (i == index) l.copy(qty = l.qty + by).takeIf { it.qty > 0 } else l })
        }
        price()
    }

    private fun price() {
        val lines = _state.value.slipForm?.lines ?: return
        if (lines.isEmpty()) {
            slip { it.copy(priced = null, pricedLines = null) }
            return
        }
        viewModelScope.launch {
            val price = attempt(app, { error -> _state.update { it.copy(error = error) } }) { graph.counter.price(lines) } ?: return@launch
            // A slow answer for lines since changed is not theirs.
            slip { if (it.lines == lines) it.copy(priced = price.total, pricedLines = lines) else it }
        }
    }

    fun enter() {
        val f = _state.value.slipForm ?: return
        val slip = f.slip ?: return
        val at = Kassenausfall.slipTime(f.outage, Kassenausfall.today(), f.time) ?: return
        val paper = Kassenbuch.parseEuro(f.paper) ?: return
        val method = f.method ?: return
        if (f.lines.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var failed = false
            attempt(app, { error -> failed = true; _state.update { it.copy(busy = false, error = error) } }) {
                backend.late(f.id, f.outage.id, slip, at, f.dineIn, f.lines, method, paper, f.terminal)
            }
            if (failed) return@launch
            _state.update { it.copy(busy = false, slipForm = null, done = app.getString(R.string.ka_slip_booked, slip)) }
            load()
        }
    }
}

@Composable
private fun Picked(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun OutageCard(o: TillOutage, today: Boolean, model: KassenausfallViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val from = o.startedAt?.let(Kassenausfall::clock) ?: "–"
            val to = o.endedAt?.let(Kassenausfall::clock) ?: "–"
            Text("$from–$to · ${o.cause}", fontWeight = FontWeight.Bold)
            val facts =
                listOfNotNull(
                    o.range.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ka_slips, it) },
                    o.void.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ka_void, it.joinToString(", ")) },
                    stringResource(R.string.ka_reported_by, o.createdOn ?: o.createdBy),
                )
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            o.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            for (e in o.entered) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ka_slip_no, e.slip))
                    Text(e.at?.let(Kassenausfall::clock) ?: "–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("#${e.number}", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(CashUpText.euro(e.total))
                }
            }
            if (o.missing.isNotEmpty()) Text(stringResource(R.string.ka_missing_here, o.missing.joinToString(", ")), color = MaterialTheme.colorScheme.error)
            if (today) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (o.missing.isNotEmpty()) Button(onClick = { model.startSlip(o) }) { Text(stringResource(R.string.ka_enter_slip)) }
                    OutlinedButton(onClick = { model.startCorrection(o) }) { Text(stringResource(R.string.ka_correct)) }
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, label: Int, onChange: (String) -> Unit, numeric: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = modifier,
    )
}

@Composable
private fun OutageFormCard(form: OutageForm, busy: Boolean, model: KassenausfallViewModel) {
    val d = form.draft
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (form.correcting) R.string.ka_correct else R.string.ka_report), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(d.from, R.string.ka_from, { v -> model.draft { it.copy(from = v.take(5)) } }, modifier = Modifier.weight(1f))
            Field(d.to, R.string.ka_to, { v -> model.draft { it.copy(to = v.take(5)) } }, modifier = Modifier.weight(1f))
        }
        Field(d.cause, R.string.ka_cause, { v -> model.draft { it.copy(cause = v.take(Kassenausfall.CAUSE_MAX)) } })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(d.slipsFirst, R.string.ka_slips_first, { v -> model.draft { it.copy(slipsFirst = v.take(6)) } }, true, Modifier.weight(1f))
            Field(d.slipsLast, R.string.ka_slips_last, { v -> model.draft { it.copy(slipsLast = v.take(6)) } }, true, Modifier.weight(1f))
        }
        Field(d.void, R.string.ka_void_label, { v -> model.draft { it.copy(void = v.take(60)) } })
        Field(d.note, R.string.ka_note, { v -> model.draft { it.copy(note = v.take(Kassenausfall.NOTE_MAX)) } })
        Text(stringResource(R.string.ka_report_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = model::report, enabled = !busy && Kassenausfall.request(d, Kassenausfall.today()) != null) {
                Text(stringResource(R.string.ka_save))
            }
            TextButton(onClick = model::cancel, enabled = !busy) { Text(stringResource(R.string.cancel_back)) }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlipFormCard(state: KassenausfallState, form: SlipForm, model: KassenausfallViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ka_enter_slip), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in form.outage.missing) Picked(n == form.slip, "$n") { model.slip { it.copy(slip = n) } }
        }
        Field(form.time, R.string.ka_slip_time, { v -> model.slip { it.copy(time = v.take(5)) } })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picked(!form.dineIn, stringResource(R.string.ka_takeaway)) { model.slip { it.copy(dineIn = false) } }
            Picked(form.dineIn, stringResource(R.string.ka_dine_in)) { model.slip { it.copy(dineIn = true) } }
        }
        Field(form.search, R.string.ka_dish, { v -> model.slip { it.copy(search = v.take(40)) } })
        val q = form.search.trim().lowercase()
        if (q.length >= 2) {
            val hits = state.menu?.dishes.orEmpty().filter { it.available && it.name.lowercase().contains(q) }.take(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (dish in hits) OutlinedButton(onClick = { model.choose(dish) }) { Text(dish.name) }
            }
        }
        form.dish?.let { dish ->
            Text(dish.name, fontWeight = FontWeight.Bold)
            for (group in model.groups(dish)) {
                Text(group.name, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in group.options) Picked(form.options.any { it.id == option.id }, option.name) { model.toggle(group, option) }
                }
            }
            Button(onClick = model::addChosen) { Text(stringResource(R.string.ka_add)) }
        }
        form.lines.forEachIndexed { index, line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val choices = line.options.joinToString(", ") { it.name }
                Text(
                    if (choices.isEmpty()) line.dish.name else "${line.dish.name} · $choices",
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { model.bump(index, -1) }) { Text("−") }
                Text("${line.qty}")
                TextButton(onClick = { model.bump(index, 1) }) { Text("+") }
            }
        }
        val priced = form.priced?.takeIf { form.pricedLines == form.lines }
        priced?.let { Text(stringResource(R.string.ka_till_price, CashUpText.euro(it)), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Field(form.paper, R.string.ka_paper, { v -> model.slip { it.copy(paper = v.take(12)) } })
        val paper = Kassenbuch.parseEuro(form.paper)
        if (paper != null && priced != null && Math.round(paper * 100) != Math.round(priced * 100)) {
            Text(stringResource(R.string.ka_paper_off), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picked(form.method == CounterPayment.CASH, stringResource(R.string.ka_cash)) { model.slip { it.copy(method = CounterPayment.CASH) } }
            Picked(form.method == CounterPayment.CARD, stringResource(R.string.ka_card)) { model.slip { it.copy(method = CounterPayment.CARD) } }
        }
        if (form.method == CounterPayment.CARD && state.terminals.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in state.terminals) {
                    Picked(t.id == form.terminal, t.name) { model.slip { it.copy(terminal = if (it.terminal == t.id) null else t.id) } }
                }
            }
        }
        val ready =
            !state.busy && form.slip != null && form.lines.isNotEmpty() && paper != null && form.method != null &&
                Kassenausfall.slipTime(form.outage, Kassenausfall.today(), form.time) != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = model::enter, enabled = ready) { Text(stringResource(R.string.ka_book_slip)) }
            TextButton(onClick = model::cancel, enabled = !state.busy) { Text(stringResource(R.string.cancel_back)) }
            if (state.busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KassenausfallScreen(navigation: @Composable () -> Unit) {
    val model: KassenausfallViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.load() }
    val today = Kassenausfall.today().toString()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ka_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = navigation,
                actions = { TextButton(onClick = model::load, enabled = !state.loading) { Text(stringResource(R.string.reload)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Text(stringResource(R.string.ka_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (state.missing.isNotEmpty()) {
                item(key = "missing") {
                    Text(stringResource(R.string.ka_missing, state.missing.joinToString(", ")), fontWeight = FontWeight.Bold)
                }
            }
            val outages = state.outages
            if (state.loading && outages == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            if (outages != null) {
                items(outages, key = { it.id }) { o -> OutageCard(o, o.day == today, model) }
                val outageForm = state.outageForm
                val slipForm = state.slipForm
                when {
                    outageForm != null -> item(key = "outage-form") { OutageFormCard(outageForm, state.busy, model) }
                    slipForm != null -> item(key = "slip-form") { SlipFormCard(state, slipForm, model) }
                    else -> item(key = "report") { OutlinedButton(onClick = model::startReport) { Text(stringResource(R.string.ka_report)) } }
                }
                state.done?.let { done -> item(key = "done") { Text(done) } }
            }
        }
    }
}
