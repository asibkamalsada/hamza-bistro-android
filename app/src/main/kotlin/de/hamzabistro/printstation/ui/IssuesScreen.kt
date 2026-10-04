package de.hamzabistro.printstation.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.IssueKind
import de.hamzabistro.printstation.core.OrderIssue
import de.hamzabistro.printstation.core.OrderIssues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What "Reklamationen" shows. */
data class IssuesScreenState(
    val loading: Boolean = false,
    /** The open reports, oldest first. */
    val issues: List<OrderIssue> = emptyList(),
    /** The shop's database has no reports yet. */
    val missing: Boolean = false,
    /** The report an answer is on its way for. */
    val busy: String? = null,
    val error: String? = null,
    /** "Gutschein SORRY-7K2QXA gesendet.", after a voucher went out. */
    val sent: String? = null,
)

/**
 * "Reklamationen" (hamza-bistro-web#88): the customers' open problem
 * reports, read again whenever the count changes — a new one, or one
 * answered on another device — and after each answer here.
 */
class IssuesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow(IssuesScreenState())
    val state: StateFlow<IssuesScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch { graph.liveIssues.map { it.ids }.distinctUntilChanged().collect { load() } }
    }

    fun load() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            var failed = false
            val read = attempt(app, { error -> failed = true; _state.update { it.copy(loading = false, error = error) } }) { graph.issues.open() }
            if (failed) return@launch
            _state.update { it.copy(loading = false, issues = read.orEmpty(), missing = read == null, error = null) }
        }
    }

    fun sendVoucher(issue: OrderIssue, cents: Long) =
        answer(issue) {
            val done = graph.issues.sendVoucher(issue.id, cents)
            _state.update { it.copy(sent = app.getString(R.string.issue_voucher_sent, done.voucherCode.orEmpty())) }
        }

    fun resolve(issue: OrderIssue, note: String?) = answer(issue) { graph.issues.resolve(issue.id, OrderIssues.note(note)) }

    fun dismissSent() = _state.update { it.copy(sent = null) }

    private fun answer(issue: OrderIssue, send: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = issue.id, error = null, sent = null) }
        viewModelScope.launch {
            attempt(app, { error -> _state.update { it.copy(error = error) } }) { send() }
            _state.update { it.copy(busy = null) }
            // Answered here or refused because it was answered elsewhere:
            // either way the list and the badge are read again.
            graph.issueWatch.refresh()
            load()
        }
    }
}

/** "Etwas fehlt", "Falsches Gericht", "Kalt / zu spät", "Anderes". */
fun issueKindText(context: Context, kind: IssueKind): String =
    context.getString(
        when (kind) {
            IssueKind.MISSING -> R.string.issue_kind_missing
            IssueKind.WRONG -> R.string.issue_kind_wrong
            IssueKind.COLD_LATE -> R.string.issue_kind_cold_late
            IssueKind.OTHER -> R.string.issue_kind_other
        }
    )

/** The amount in cents as "5,00 €". */
private fun cents(amount: Long): String = Format.euro(amount / 100.0)

/** What is being asked before it is sent. */
private sealed interface Asking {
    val issue: OrderIssue

    /** Which voucher: the buttons and another amount. */
    data class Voucher(override val issue: OrderIssue) : Asking

    /** "Gutschein über 5,00 € zu Bestellung #57 mailen?" */
    data class Confirm(override val issue: OrderIssue, val cents: Long) : Asking

    /** "Erledigt", with a note for the customer or none. */
    data class Done(override val issue: OrderIssue) : Asking
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesScreen(onBack: () -> Unit) {
    val model: IssuesViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var asking by remember { mutableStateOf<Asking?>(null) }
    LaunchedEffect(Unit) { model.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.issues_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = { TextButton(onClick = model::load, enabled = !state.loading) { Text(stringResource(R.string.reload)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { error ->
                item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp)) }
            }
            if (state.missing) {
                item(key = "missing") {
                    Text(stringResource(R.string.issues_needs_update), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
            }
            when {
                state.loading && state.issues.isEmpty() ->
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                state.issues.isEmpty() && state.error == null && !state.missing ->
                    item(key = "empty") {
                        Text(stringResource(R.string.issues_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                    }
            }
            items(state.issues, key = { it.id }) { issue ->
                IssueCard(
                    issue = issue,
                    busy = state.busy != null,
                    onCall = { phone -> call(context, phone) },
                    onVoucher = { asking = Asking.Voucher(issue) },
                    onDone = { asking = Asking.Done(issue) },
                )
            }
        }
    }

    when (val now = asking) {
        is Asking.Voucher -> VoucherDialog(now.issue, onDismiss = { asking = null }) { amount -> asking = Asking.Confirm(now.issue, amount) }
        is Asking.Confirm ->
            AlertDialog(
                onDismissRequest = { asking = null },
                title = { Text(stringResource(R.string.issue_voucher)) },
                text = { Text(stringResource(R.string.issue_voucher_confirm, cents(now.cents), now.issue.orders?.orderNumber ?: 0L)) },
                confirmButton = {
                    Button(onClick = {
                        model.sendVoucher(now.issue, now.cents)
                        asking = null
                    }) { Text(stringResource(R.string.issue_voucher_send)) }
                },
                dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
            )
        is Asking.Done -> DoneDialog(onDismiss = { asking = null }) { note ->
            model.resolve(now.issue, note)
            asking = null
        }
        null -> Unit
    }

    state.sent?.let { sent ->
        AlertDialog(
            onDismissRequest = model::dismissSent,
            title = { Text(stringResource(R.string.issue_voucher)) },
            text = { Text(sent, style = MaterialTheme.typography.titleMedium) },
            confirmButton = { Button(onClick = model::dismissSent) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** One report: the order, what was reported, the comment, where and what it came to, and the three answers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssueCard(issue: OrderIssue, busy: Boolean, onCall: (String) -> Unit, onVoucher: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val order = issue.orders
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${order?.orderNumber ?: "?"}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                order?.customerName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically))
                }
                Badge(issueKindText(context, issue.kind), MaterialTheme.colorScheme.errorContainer)
                issue.createdAt?.let {
                    Text(
                        stringResource(R.string.issue_reported_at, Format.clock(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
            if (issue.kind == IssueKind.MISSING) {
                Text(stringResource(R.string.issue_missing_lines, OrderIssues.linesLabel(issue)), style = MaterialTheme.typography.bodyLarge)
            }
            if (issue.comment.isNotBlank()) {
                Text("„${issue.comment}“", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
            }
            if (order != null) {
                val where = if (order.pickup) stringResource(R.string.pickup_badge) else order.address
                Text(
                    "$where · ${Format.euro(order.total)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                order?.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    OutlinedButton(onClick = { onCall(phone) }) { Text(stringResource(R.string.issue_call)) }
                }
                OutlinedButton(onClick = onVoucher, enabled = !busy) { Text(stringResource(R.string.issue_voucher)) }
                Button(onClick = onDone, enabled = !busy) { Text(stringResource(R.string.issue_done)) }
            }
        }
    }
}

/** 3 €, 5 €, the missing lines' price, or another amount (0,01–100 €). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoucherDialog(issue: OrderIssue, onDismiss: () -> Unit, onChosen: (Long) -> Unit) {
    var other by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val missing = OrderIssues.missingCents(issue)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.issue_voucher)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (amount in OrderIssues.voucherChoices(issue)) {
                        OutlinedButton(onClick = { onChosen(amount) }) {
                            Text(
                                if (amount == missing) stringResource(R.string.issue_voucher_missing, cents(amount))
                                else cents(amount)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = other,
                    onValueChange = {
                        other = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.issue_voucher_other)) },
                    suffix = { Text("€") },
                    singleLine = true,
                    isError = invalid,
                    supportingText = { if (invalid) Text(stringResource(R.string.issue_voucher_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { OrderIssues.parseAmount(other)?.let(onChosen) ?: run { invalid = true } },
                enabled = other.isNotBlank(),
            ) { Text(stringResource(R.string.issue_voucher_other_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Erledigt" without a voucher, with an optional note for the customer. */
@Composable
private fun DoneDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    val fits = OrderIssues.noteFits(note)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.issue_done)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.issue_done_note)) },
                isError = !fits,
                supportingText = { Text("${note.trim().length} / ${OrderIssues.NOTE_MAX}") },
                minLines = 2,
            )
        },
        confirmButton = { Button(onClick = { onDone(note) }, enabled = fits) { Text(stringResource(R.string.issue_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The dialler with the customer's number, as the order card's "Anrufen". */
private fun call(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.filterNot(Char::isWhitespace), null)))
    } catch (e: ActivityNotFoundException) {
        // A tablet without a phone app: the number is on the card to dial by hand.
    }
}
