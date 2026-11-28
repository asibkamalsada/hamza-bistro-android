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
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.CashBookEntry
import de.hamzabistro.printstation.core.CashBookKind
import de.hamzabistro.printstation.core.CashBookPlace
import de.hamzabistro.printstation.core.CashDrawer
import de.hamzabistro.printstation.core.CashUpText
import de.hamzabistro.printstation.core.Kassenbuch
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The Kassenbuch (hamza-bistro-web 20261128130000_kassenbuch): what the till
// expects in the drawer, and the next entry — the float, cash to the safe or
// the bank and back, private money, the count. The site's /orders has the
// same under the Kassensturz. Booked for today only, as the database books
// "now"; each entry's id is made when its kind is picked, so a tap again
// after a lost answer is the same entry, never a second one.

/** What the Kassenbuch screen shows. */
data class KassenbuchState(
    val loading: Boolean = false,
    val drawer: CashDrawer? = null,
    val error: String? = null,
    /** The kind being booked, or none. */
    val kind: CashBookKind? = null,
    /** Made when [kind] was picked. */
    val id: String = "",
    val amount: String = "",
    val note: String = "",
    val place: CashBookPlace = CashBookPlace.SAFE,
    val busy: Boolean = false,
    /** The last entry went through. */
    val booked: Boolean = false,
) {
    val parsed: Double?
        get() = Kassenbuch.parseEuro(amount)

    val ready: Boolean
        get() = kind != null && !busy && Kassenbuch.canBook(drawer, kind, parsed, note)
}

class KassenbuchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val backend = (application as PrintStationApp).graph.kassenbuch

    private val _state = MutableStateFlow(KassenbuchState())
    val state: StateFlow<KassenbuchState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var failed = false
            val drawer = attempt(app, { error -> failed = true; _state.update { it.copy(loading = false, error = error) } }) {
                backend.drawer(null)
            }
            if (failed) return@launch
            _state.update {
                it.copy(
                    loading = false,
                    drawer = drawer,
                    error = if (drawer == null) app.getString(R.string.special_needs_update, Kassenbuch.MIGRATION) else null,
                )
            }
        }
    }

    fun pick(kind: CashBookKind) {
        if (_state.value.kind == kind) return
        _state.update {
            it.copy(
                kind = kind,
                id = UUID.randomUUID().toString(),
                amount = if (kind == CashBookKind.OPENING) "85" else "",
                note = "",
                place = CashBookPlace.SAFE,
                booked = false,
                error = null,
            )
        }
    }

    fun amount(text: String) = _state.update { it.copy(amount = text.take(12)) }

    fun note(text: String) = _state.update { it.copy(note = text.take(Kassenbuch.NOTE_MAX)) }

    fun place(place: CashBookPlace) = _state.update { it.copy(place = place) }

    fun cancel() = _state.update { it.copy(kind = null, amount = "", note = "") }

    fun book() {
        val s = _state.value
        val kind = s.kind ?: return
        val amount = s.parsed ?: return
        if (!s.ready) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var failed = false
            attempt(app, { error -> failed = true; _state.update { it.copy(busy = false, error = error) } }) {
                backend.add(s.id, kind, amount, s.note, s.place.takeIf { kind.needsPlace })
            }
            if (failed) return@launch
            _state.update { it.copy(busy = false, kind = null, amount = "", note = "", booked = true) }
            load()
        }
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "+20,00 €", "−50,00 €", "0,00 €". */
private fun signed(amount: Double): String = if (amount > 0) "+" + CashUpText.euro(amount) else CashUpText.euro(amount)

@Composable
private fun kindLabel(kind: CashBookKind): String =
    stringResource(
        when (kind) {
            CashBookKind.OPENING -> R.string.kb_kind_opening
            CashBookKind.COUNT -> R.string.kb_kind_count
            CashBookKind.TRANSIT_OUT -> R.string.kb_kind_transit_out
            CashBookKind.TRANSIT_IN -> R.string.kb_kind_transit_in
            CashBookKind.WITHDRAWAL -> R.string.kb_kind_withdrawal
            CashBookKind.DEPOSIT -> R.string.kb_kind_deposit
        }
    )

@Composable
private fun entryLabel(entry: CashBookEntry): String =
    when (entry.kind) {
        CashBookKind.OPENING -> stringResource(R.string.kb_opening)
        CashBookKind.COUNT -> stringResource(R.string.kb_entry_count, CashUpText.euro(entry.counted ?: 0.0))
        CashBookKind.TRANSIT_OUT ->
            stringResource(if (entry.place == CashBookPlace.BANK) R.string.kb_entry_to_bank else R.string.kb_entry_to_safe)
        CashBookKind.TRANSIT_IN ->
            stringResource(if (entry.place == CashBookPlace.BANK) R.string.kb_entry_from_bank else R.string.kb_entry_from_safe)
        CashBookKind.WITHDRAWAL -> stringResource(R.string.kb_kind_withdrawal)
        CashBookKind.DEPOSIT -> stringResource(R.string.kb_kind_deposit)
    }

@Composable
private fun entryState(entry: CashBookEntry): String {
    // A local: the property is the core module's, so no smart cast.
    val receipt = entry.receipt
    return when {
        !entry.signing || receipt == null -> if (entry.amount == 0.0) "" else stringResource(R.string.kb_entry_unsigned)
        receipt.state == "signed" -> stringResource(R.string.kb_entry_signed)
        receipt.pending -> stringResource(R.string.kb_entry_pending)
        else -> stringResource(R.string.kb_entry_unsigned)
    }
}

@Composable
private fun SumRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = if (strong) FontWeight.Bold else null)
        Text(value, fontWeight = if (strong) FontWeight.Bold else null)
    }
}

@Composable
private fun DrawerCard(drawer: CashDrawer) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SumRow(
                stringResource(R.string.kb_opening),
                drawer.opening?.let { CashUpText.euro(it) } ?: stringResource(R.string.kb_opening_missing),
            )
            SumRow(stringResource(R.string.kb_cash), CashUpText.euro(drawer.cash))
            if (drawer.transitIn != 0.0) SumRow(stringResource(R.string.kb_transit_in), signed(drawer.transitIn))
            if (drawer.transitOut != 0.0) SumRow(stringResource(R.string.kb_transit_out), signed(drawer.transitOut))
            if (drawer.deposits != 0.0) SumRow(stringResource(R.string.kb_deposits), signed(drawer.deposits))
            if (drawer.withdrawals != 0.0) SumRow(stringResource(R.string.kb_withdrawals), signed(drawer.withdrawals))
            if (drawer.differences != 0.0) SumRow(stringResource(R.string.kb_differences), signed(drawer.differences))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SumRow(stringResource(R.string.kb_expected), CashUpText.euro(drawer.expected), strong = true)
            drawer.lastCount?.let { c ->
                val at = c.at?.let { CLOCK.format(it.atZone(AlarmPolicy.LEIPZIG)) } ?: "–"
                Text(
                    stringResource(R.string.kb_last_count, at, c.by, CashUpText.euro(c.counted), signed(c.difference)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryForm(state: KassenbuchState, model: KassenbuchViewModel) {
    val drawer = state.drawer ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in Kassenbuch.kinds(drawer)) {
                if (kind == state.kind) Button(onClick = {}) { Text(kindLabel(kind)) }
                else OutlinedButton(onClick = { model.pick(kind) }) { Text(kindLabel(kind)) }
            }
        }
        val kind = state.kind ?: return@Column
        OutlinedTextField(
            value = state.amount,
            onValueChange = model::amount,
            label = { Text(stringResource(if (kind.counted) R.string.kb_counted else R.string.kb_amount)) },
            singleLine = true,
            isError = state.amount.isNotBlank() && !Kassenbuch.amountAllowed(kind, state.parsed),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = {
                val amount = state.parsed
                val text =
                    when {
                        kind == CashBookKind.COUNT && amount != null ->
                            stringResource(R.string.kb_difference_is, signed(Kassenbuch.countDifference(drawer, amount)))
                        kind == CashBookKind.COUNT -> stringResource(R.string.kb_count_hint, CashUpText.euro(drawer.expected))
                        amount != null && state.ready ->
                            stringResource(R.string.kb_after, CashUpText.euro(Kassenbuch.drawerAfter(drawer, kind, amount)))
                        kind.out && amount != null && amount > drawer.expected -> stringResource(R.string.kb_error_too_much)
                        else -> ""
                    }
                Text(text)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (kind.needsPlace) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((place, label) in listOf(CashBookPlace.SAFE to R.string.kb_place_safe, CashBookPlace.BANK to R.string.kb_place_bank)) {
                    if (place == state.place) Button(onClick = {}) { Text(stringResource(label)) }
                    else OutlinedButton(onClick = { model.place(place) }) { Text(stringResource(label)) }
                }
            }
        }
        OutlinedTextField(
            value = state.note,
            onValueChange = model::note,
            label = { Text(stringResource(if (kind.needsNote) R.string.kb_note else R.string.kb_note_optional)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = model::book, enabled = state.ready) { Text(stringResource(R.string.kb_book)) }
            TextButton(onClick = model::cancel, enabled = !state.busy) { Text(stringResource(R.string.cancel_back)) }
            if (state.busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun EntryRow(entry: CashBookEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.createdAt?.let { CLOCK.format(it.atZone(AlarmPolicy.LEIPZIG)) } ?: "–", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entryLabel(entry), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(signed(entry.amount))
        }
        val by = listOfNotNull(entry.createdOn ?: entry.createdBy.takeIf { it.isNotBlank() }, entry.note, entryState(entry).takeIf { it.isNotEmpty() })
        if (by.isNotEmpty()) {
            Text(by.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KassenbuchScreen(navigation: @Composable () -> Unit) {
    val model: KassenbuchViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kb_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                Text(stringResource(R.string.kb_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error) } }
            val drawer = state.drawer
            if (state.loading && drawer == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            if (drawer != null) {
                item(key = "drawer") { DrawerCard(drawer) }
                item(key = "form") { EntryForm(state, model) }
                if (state.booked) item(key = "booked") { Text(stringResource(R.string.kb_booked)) }
                if (drawer.entries.isNotEmpty()) {
                    item(key = "entries") { Text(stringResource(R.string.kb_entries), style = MaterialTheme.typography.titleMedium) }
                    items(drawer.entries, key = { it.id }) { entry -> EntryRow(entry) }
                }
            }
        }
    }
}
