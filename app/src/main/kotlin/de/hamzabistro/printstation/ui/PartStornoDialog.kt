package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.NeedsServerUpdateException
import de.hamzabistro.printstation.core.PartStorno
import de.hamzabistro.printstation.core.PartStornoFee
import de.hamzabistro.printstation.core.PartStornoQuote
import de.hamzabistro.printstation.core.PartStornos
import de.hamzabistro.printstation.core.PrinterException
import de.hamzabistro.printstation.core.RefundMethod
import de.hamzabistro.printstation.core.Stornos
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Teilstorno (hamza-bistro-web 20261128200000_teilstorno): part of a delivered
// order taken back from the history — some of its lines, a fee whole, or an
// amount as goodwill. The database says what that gives back before the tap
// (order_part_storno_quote) and books a second Beleg on it; this dialog asks
// what, why and how the money goes back, then shows that Beleg's receipt.

/** The open Teilstorno dialog; null when none is open. */
data class PartStornoState(
    val target: StornoTarget,
    /** Made when the dialog opens: a tap again after a lost answer is the same Teilstorno. */
    val id: String,
    /** The order as it stands: what is left of each line and fee. Null until read. */
    val start: PartStornoQuote? = null,
    /** What the choice gives back, for [quotedFor]. */
    val quote: PartStornoQuote? = null,
    val quotedFor: Choice? = null,
    val asked: Map<Int, Int> = emptyMap(),
    val fees: Set<PartStornoFee> = emptySet(),
    val goodwillText: String = "",
    val reason: String = "",
    val method: RefundMethod,
    val quoting: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** Booked: its receipt is shown. */
    val done: PartStorno? = null,
    val printing: Boolean = false,
    val printed: Boolean = false,
    val printError: String? = null,
) {
    /** One choice, as one value: a quote is for exactly this. */
    data class Choice(val lines: Map<Int, Int>, val fees: Set<PartStornoFee>, val goodwill: Double)

    val methods: List<RefundMethod>
        get() = Stornos.refundMethods(target.paid)

    val goodwill: Double?
        get() = PartStornos.goodwill(goodwillText)

    val choice: Choice?
        get() = goodwill?.let { Choice(asked.filterValues { it > 0 }, fees, it) }

    /** "Zurückgeben": a reason, and the till's amount for exactly this choice, above 0. */
    val canConfirm: Boolean
        get() {
            val q = quote ?: return false
            return !busy && !quoting && done == null && reason.isNotBlank() && choice != null && quotedFor == choice && q.amount > 0.0
        }
}

class PartStornoViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow<PartStornoState?>(null)
    val state: StateFlow<PartStornoState?> = _state.asStateFlow()
    private var quoting: Job? = null

    val canPrint: Boolean
        get() = graph.settings.printer != null

    fun open(target: StornoTarget) {
        val state = PartStornoState(target, UUID.randomUUID().toString(), method = Stornos.refundMethods(target.paid).first())
        _state.value = state
        viewModelScope.launch {
            val start =
                attempt(app, { error -> _state.update { it?.copy(error = error) } }) {
                    graph.partStornos.quote(target.orderNumber, emptyList(), emptyList(), 0.0)
                }
            _state.update { now ->
                if (now == null || now.id != state.id || start == null) now
                else now.copy(start = start, quote = start, quotedFor = PartStornoState.Choice(emptyMap(), emptySet(), 0.0))
            }
        }
    }

    /** One more, or one less, of [line]; never below 0 or above what is left of it. */
    fun step(line: PartStornoQuote.Line, by: Int) {
        _state.update { now ->
            now ?: return@update null
            val n = ((now.asked[line.line] ?: 0) + by).coerceIn(0, line.left)
            now.copy(asked = if (n == 0) now.asked - line.line else now.asked + (line.line to n), error = null)
        }
        requote()
    }

    fun toggleFee(fee: PartStornoFee) {
        _state.update { s -> s?.let { it.copy(fees = if (fee in it.fees) it.fees - fee else it.fees + fee, error = null) } }
        requote()
    }

    fun goodwill(text: String) {
        _state.update { it?.copy(goodwillText = text.take(8), error = null) }
        requote()
    }

    fun reason(text: String) = _state.update { it?.copy(reason = text.take(Stornos.REASON_MAX), error = null) }

    fun method(method: RefundMethod) = _state.update { it?.copy(method = method) }

    fun close() {
        if (_state.value?.busy != true) {
            quoting?.cancel()
            _state.value = null
        }
    }

    /** What the choice gives back, a moment after the last change. */
    private fun requote() {
        quoting?.cancel()
        val now = _state.value ?: return
        val choice = now.choice ?: return
        if (now.start == null) return
        _state.update { it?.copy(quoting = true) }
        quoting =
            viewModelScope.launch {
                delay(QUOTE_DELAY_MS)
                val quote =
                    attempt(app, { error -> _state.update { s -> if (s != null && s.choice == choice) s.copy(error = error, quoting = false) else s } }) {
                        graph.partStornos.quote(now.target.orderNumber, PartStornos.lines(choice.lines), choice.fees, choice.goodwill)
                    }
                if (quote != null) {
                    _state.update { s -> if (s != null && s.choice == choice) s.copy(quote = quote, quotedFor = choice, quoting = false) else s }
                }
            }
    }

    /** "Zurückgeben": once; [onDone] is told when it is booked. */
    fun confirm(onDone: (StornoTarget, PartStorno) -> Unit) {
        val now = _state.value ?: return
        if (!now.canConfirm) return
        val choice = now.choice ?: return
        _state.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            val part =
                attempt(app, { error -> _state.update { it?.copy(error = error) } }) {
                    graph.partStornos.partStorno(
                        now.id,
                        now.target.orderNumber,
                        PartStornos.lines(choice.lines),
                        choice.fees,
                        choice.goodwill,
                        now.method,
                        now.reason,
                    )
                }
            _state.update { it?.copy(busy = false, done = part ?: it.done) }
            if (part != null) onDone(now.target, part)
        }
    }

    /** Its receipt on this device's printer. */
    fun print() {
        val now = _state.value ?: return
        val done = now.done ?: return
        if (now.printing) return
        _state.update { it?.copy(printing = true, printed = false, printError = null) }
        viewModelScope.launch {
            val failure =
                try {
                    graph.printPartStornoReceipt(now.target.orderId, done.id)
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
            _state.update { it?.copy(printing = false, printed = failure == null, printError = failure) }
        }
    }

    private companion object {
        const val QUOTE_DELAY_MS = 250L
    }
}

/** The dialog, while [model] has one open. [onDone] is told when the Teilstorno is booked. */
@Composable
fun PartStornoDialog(model: PartStornoViewModel, onDone: (StornoTarget, PartStorno) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val now = state ?: return
    val amount = Format.euro(now.quote?.amount ?: 0.0)
    AlertDialog(
        onDismissRequest = model::close,
        title = {
            Text(
                now.done?.let { stringResource(R.string.part_storno_done, it.number, Format.euro(it.amount)) }
                    ?: stringResource(R.string.part_storno_title, now.target.orderNumber)
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val done = now.done
                val start = now.start
                if (done != null) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val receipt = done.receipt
                        if (receipt == null) {
                            Text(stringResource(R.string.storno_unsigned))
                        } else {
                            QrImage(receipt.link, stringResource(R.string.till_qr), 220.dp)
                            Text(stringResource(R.string.till_scan))
                        }
                        now.printError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (now.printed) Text(stringResource(R.string.till_printed), color = MaterialTheme.colorScheme.primary)
                    }
                } else if (start == null) {
                    Text(stringResource(R.string.please_wait))
                } else if (start.nothingLeft) {
                    Text(stringResource(R.string.part_storno_nothing_left))
                } else {
                    Text(stringResource(R.string.part_storno_lines), style = MaterialTheme.typography.titleSmall)
                    for (line in start.lines) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${line.qty}× ${line.label}")
                                if (line.taken > 0) {
                                    Text(
                                        stringResource(R.string.part_storno_taken, line.taken, line.qty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (line.left > 0) {
                                val asked = now.asked[line.line] ?: 0
                                TextButton(onClick = { model.step(line, -1) }, enabled = asked > 0 && !now.busy) { Text("−") }
                                Text("$asked")
                                TextButton(onClick = { model.step(line, 1) }, enabled = asked < line.left && !now.busy) { Text("+") }
                            }
                        }
                    }
                    val fees = start.fees.filter { !it.taken }
                    if (fees.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (fee in fees) {
                                val label =
                                    stringResource(
                                        if (fee.fee == PartStornoFee.DELIVERY) R.string.part_storno_fee_delivery else R.string.part_storno_fee_small
                                    )
                                FilterChip(
                                    selected = fee.fee in now.fees,
                                    onClick = { model.toggleFee(fee.fee) },
                                    label = { Text("$label · ${Format.euro(fee.amount)}") },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = now.goodwillText,
                        onValueChange = model::goodwill,
                        label = { Text(stringResource(R.string.part_storno_goodwill)) },
                        supportingText = {
                            Text(
                                if (now.goodwill == null) stringResource(R.string.part_storno_goodwill_invalid)
                                else stringResource(R.string.part_storno_goodwill_max, Format.euro(now.quote?.goodwillMax ?: 0.0))
                            )
                        },
                        isError = now.goodwill == null,
                        singleLine = true,
                        enabled = !now.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = now.reason,
                        onValueChange = model::reason,
                        label = { Text(stringResource(R.string.storno_reason)) },
                        singleLine = true,
                        enabled = !now.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in
                            listOf(
                                R.string.part_storno_reason_missing,
                                R.string.part_storno_reason_wrong_item,
                                R.string.part_storno_reason_typed,
                                R.string.part_storno_reason_goodwill,
                            )) {
                            val text = stringResource(preset)
                            FilterChip(selected = now.reason == text, onClick = { model.reason(text) }, label = { Text(text) })
                        }
                    }
                    Text(stringResource(R.string.storno_refund), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (method in now.methods) {
                            FilterChip(selected = now.method == method, onClick = { model.method(method) }, label = { Text(refundLabel(method)) })
                        }
                    }
                    Text(
                        stringResource(
                            when (now.method) {
                                RefundMethod.CASH -> R.string.storno_hint_cash
                                RefundMethod.CARD -> R.string.storno_hint_card
                                RefundMethod.ONLINE -> R.string.storno_hint_online
                            }
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (done == null) now.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val done = now.done
            if (done == null) {
                if (now.start?.nothingLeft != true) {
                    Button(
                        onClick = { model.confirm(onDone) },
                        enabled = now.canConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(
                            if (now.busy || now.quoting) stringResource(R.string.please_wait)
                            else stringResource(R.string.part_storno_confirm, amount)
                        )
                    }
                }
            } else if (done.receipt != null && model.canPrint) {
                Button(onClick = model::print, enabled = !now.printing) {
                    Text(stringResource(if (now.printing) R.string.till_printing else R.string.till_print))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = model::close, enabled = !now.busy) {
                Text(stringResource(if (now.done == null) R.string.cancel else R.string.ok))
            }
        },
    )
}
