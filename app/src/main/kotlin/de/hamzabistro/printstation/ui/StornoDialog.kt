package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.NeedsServerUpdateException
import de.hamzabistro.printstation.core.PaymentMethod
import de.hamzabistro.printstation.core.PrinterException
import de.hamzabistro.printstation.core.RefundMethod
import de.hamzabistro.printstation.core.Storno
import de.hamzabistro.printstation.core.Stornos
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Storno (hamza-bistro-web 20261127100000_storno): a delivered order taken
// back, all of it, from the history or right after a sale in the Kasse. The
// database books a second Beleg and the TSE signs it; this dialog asks why
// and how the money goes back, then shows that Beleg's receipt.

/** The order to take back. */
data class StornoTarget(val orderId: String, val orderNumber: Long, val total: Double, val paid: PaymentMethod?)

/** The open Storno dialog; null when none is open. */
data class StornoState(
    val target: StornoTarget,
    /** Made when the dialog opens: a tap again after a lost answer is the same Storno. */
    val id: String,
    val reason: String = "",
    val method: RefundMethod,
    val busy: Boolean = false,
    val error: String? = null,
    /** Booked: its receipt is shown. */
    val done: Storno? = null,
    val printing: Boolean = false,
    val printed: Boolean = false,
    val printError: String? = null,
) {
    val methods: List<RefundMethod>
        get() = Stornos.refundMethods(target.paid)
}

class StornoViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow<StornoState?>(null)
    val state: StateFlow<StornoState?> = _state.asStateFlow()

    val canPrint: Boolean
        get() = graph.settings.printer != null

    fun open(target: StornoTarget) {
        _state.value = StornoState(target, UUID.randomUUID().toString(), method = Stornos.refundMethods(target.paid).first())
    }

    fun reason(text: String) = _state.update { it?.copy(reason = text.take(Stornos.REASON_MAX), error = null) }

    fun method(method: RefundMethod) = _state.update { it?.copy(method = method) }

    fun close() {
        if (_state.value?.busy != true) _state.value = null
    }

    /** "Stornieren": once; [onDone] is told when it is booked. */
    fun confirm(onDone: (StornoTarget, Storno) -> Unit) {
        val now = _state.value ?: return
        if (now.busy || now.done != null || now.reason.isBlank()) return
        _state.update { it?.copy(busy = true, error = null) }
        viewModelScope.launch {
            val storno =
                attempt(app, { error -> _state.update { it?.copy(error = error) } }) {
                    graph.stornos.storno(now.id, now.target.orderNumber, now.method, now.reason)
                }
            _state.update { it?.copy(busy = false, done = storno ?: it.done) }
            if (storno != null) onDone(now.target, storno)
        }
    }

    /** The Storno's receipt on this device's printer. */
    fun print() {
        val now = _state.value ?: return
        if (now.printing || now.done == null) return
        _state.update { it?.copy(printing = true, printed = false, printError = null) }
        viewModelScope.launch {
            val failure =
                try {
                    graph.printStornoReceipt(now.target.orderId)
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
}

@Composable
internal fun refundLabel(method: RefundMethod): String =
    stringResource(
        when (method) {
            RefundMethod.CASH -> R.string.pay_cash
            RefundMethod.CARD -> R.string.pay_card
            RefundMethod.ONLINE -> R.string.pay_online
        }
    )

/** The dialog, while [model] has one open. [onDone] is told when the Storno is booked. */
@Composable
fun StornoDialog(model: StornoViewModel, onDone: (StornoTarget, Storno) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val now = state ?: return
    val amount = Format.euro(now.target.total)
    AlertDialog(
        onDismissRequest = model::close,
        title = {
            Text(
                now.done?.let { stringResource(R.string.storno_done, it.number) }
                    ?: stringResource(R.string.storno_title, now.target.orderNumber, amount)
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val done = now.done
                if (done == null) {
                    OutlinedTextField(
                        value = now.reason,
                        onValueChange = model::reason,
                        label = { Text(stringResource(R.string.storno_reason)) },
                        singleLine = true,
                        enabled = !now.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in listOf(R.string.storno_reason_wrong, R.string.storno_reason_complaint, R.string.storno_reason_returned)) {
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
                    now.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else {
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
            }
        },
        confirmButton = {
            val done = now.done
            if (done == null) {
                Button(
                    onClick = { model.confirm(onDone) },
                    enabled = !now.busy && now.reason.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(if (now.busy) stringResource(R.string.please_wait) else stringResource(R.string.storno_confirm, amount))
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
