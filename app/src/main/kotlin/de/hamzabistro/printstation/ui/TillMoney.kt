package de.hamzabistro.printstation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.CardChoice
import de.hamzabistro.printstation.core.CardTerminal
import de.hamzabistro.printstation.core.CounterPayment
import de.hamzabistro.printstation.core.CounterReceipt
import de.hamzabistro.printstation.core.CounterSale
import de.hamzabistro.printstation.core.CounterSales
import de.hamzabistro.printstation.core.PaymentMethod
import de.hamzabistro.printstation.core.Qr
import de.hamzabistro.printstation.core.TillState

// From "Kassieren" to the receipt. Every page is the same: what went wrong on
// top, the amount and what belongs to it, and the buttons that end the step
// along the bottom, always in reach.

@Composable
private fun TillPage(
    state: TillState,
    model: TillActions,
    bottom: @Composable RowScope.() -> Unit,
    content: @Composable BoxWithConstraintsScope.(wide: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        state.problem?.let { TillProblemCard(it, model::dismissProblem, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) { content(maxWidth >= WIDE_FROM) }
        HorizontalDivider()
        BottomBar(bottom)
    }
}

/** What belongs to a step, in the middle of its room; scrolled only where the room is too small for it. */
@Composable
private fun TillColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val room = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = room).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            content = content,
        )
    }
}

private enum class TillTone { MAIN, TONAL, QUIET, NO }

@Composable
private fun TillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: TillTone = TillTone.MAIN,
    height: Dp = 64.dp,
    size: TextUnit = 20.sp,
    maxLines: Int = 2,
) {
    val big = modifier.heightIn(min = height)
    val padding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    val shape = RoundedCornerShape(32.dp)
    val label: @Composable RowScope.() -> Unit = {
        Text(
            text,
            fontSize = size,
            lineHeight = size * 1.2f,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
    when (tone) {
        TillTone.MAIN -> Button(onClick, big, enabled, shape, contentPadding = padding, content = label)
        TillTone.TONAL -> FilledTonalButton(onClick, big, enabled, shape, contentPadding = padding, content = label)
        TillTone.QUIET -> OutlinedButton(onClick, big, enabled, shape, contentPadding = padding, content = label)
        TillTone.NO ->
            OutlinedButton(
                onClick,
                big,
                enabled,
                shape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                contentPadding = padding,
                content = label,
            )
    }
}

/** The amount, readable from the other side of the counter. */
@Composable
private fun TillAmount(caption: String, amount: Double, size: TextUnit = 64.sp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(caption, fontSize = 20.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)
        Text(Format.euro(amount), fontSize = size, lineHeight = size * 1.15f, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A line that says how something went. */
@Composable
private fun TillNote(text: String, bad: Boolean = false, good: Boolean = false) {
    Text(
        text,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color =
            when {
                bad -> MaterialTheme.colorScheme.error
                good -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        modifier = Modifier.widthIn(max = 560.dp),
    )
}

// ---------------------------------------------------------------------------
// Bar or Karte
// ---------------------------------------------------------------------------

@Composable
internal fun TillPayPane(state: TillState, sale: CounterSale, model: TillActions, onAbort: () -> Unit) {
    TillPage(
        state,
        model,
        bottom = {
            TillButton(stringResource(R.string.till_abort), onAbort, Modifier.weight(1f), !state.busy, TillTone.NO, size = 18.sp)
            TillButton(stringResource(R.string.till_park), model::park, Modifier.weight(1f), !state.busy, TillTone.QUIET, size = 18.sp)
        },
    ) { wide ->
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                val scroll = rememberScrollState()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 16.dp)) { TillSaleLines(sale) }
                    // Its row stays where it is once everything fits by scrolling: the lines must not jump.
                    if (scroll.maxValue > 0) TillMoreBelow(scroll.canScrollForward)
                }
                VerticalDivider()
                TillColumn(Modifier.weight(1f).fillMaxHeight()) { TillHow(state, sale, model) }
            }
        } else {
            TillColumn(Modifier.fillMaxSize()) {
                TillHow(state, sale, model)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    HorizontalDivider(Modifier.padding(bottom = 8.dp))
                    TillSaleLines(sale)
                }
            }
        }
    }
}

@Composable
private fun TillHow(state: TillState, sale: CounterSale, model: TillActions) {
    TillAmount(stringResource(R.string.till_to_pay), sale.total)
    if (state.declined) TillNote(stringResource(R.string.till_declined), bad = true)
    Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TillButton(stringResource(R.string.till_cash), model::chooseCash, Modifier.weight(1f), !state.busy, height = 144.dp, size = 32.sp)
        TillButton(stringResource(R.string.till_card), model::chooseCard, Modifier.weight(1f), !state.busy, height = 144.dp, size = 32.sp)
    }
}

/** Under lines that go on, so none of them is thought to be missing. */
@Composable
private fun TillMoreBelow(more: Boolean) {
    HorizontalDivider()
    Text(
        if (more) stringResource(R.string.till_more) else "",
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

/** What was rung up, as the database priced it. */
@Composable
private fun TillSaleLines(sale: CounterSale) {
    Text(
        stringResource(if (sale.dineIn) R.string.till_dine_in else R.string.till_takeaway),
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
    )
    for (line in sale.lines) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("${line.qty}×", fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 40.dp))
            Column(Modifier.weight(1f)) {
                Text(line.name, style = LINE_NAME)
                if (line.options.isNotEmpty()) {
                    Text(line.options.joinToString(", "), style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(Format.euro(line.price * line.qty), fontSize = 18.sp, lineHeight = 22.sp, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------
// Bar
// ---------------------------------------------------------------------------

@Composable
internal fun TillCashPane(state: TillState, sale: CounterSale, model: TillActions) {
    TillPage(
        state,
        model,
        bottom = {
            TillButton(stringResource(R.string.cancel_back), model::back, Modifier.widthIn(min = 112.dp), !state.busy, TillTone.QUIET, height = 72.dp)
            TillButton(stringResource(R.string.till_cash_paid), model::cashPaid, Modifier.weight(1f), state.cashReady && !state.busy, height = 72.dp, size = 24.sp)
        },
    ) { wide ->
        // The keys are as big as the room allows, so there is nothing to scroll to.
        val roomy = maxWidth >= 600.dp
        val sums = if (wide) 0.dp else with(LocalDensity.current) { (if (roomy) 220.sp else 110.sp).toDp() + 24.dp }
        val key = ((maxHeight - sums - 84.dp) / 6).coerceIn(48.dp, 76.dp)
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                TillColumn(Modifier.weight(1f).fillMaxHeight()) { TillChange(state, sale, big = true) }
                VerticalDivider()
                TillColumn(Modifier.weight(1f).fillMaxHeight()) { TillGiven(state, sale, model, key) }
            }
        } else {
            TillColumn(Modifier.fillMaxSize()) {
                TillChange(state, sale, big = roomy)
                TillGiven(state, sale, model, key)
            }
        }
    }
}

/** What is to pay, what was given, and — biggest of all — what to give back. */
@Composable
private fun TillChange(state: TillState, sale: CounterSale, big: Boolean) {
    val total = CounterSales.cents(sale.total)
    val given = CounterSales.parse(state.given)
    val change = state.changeCents
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(if (big) 12.dp else 4.dp)) {
        TillSum(stringResource(R.string.till_to_pay), Format.euro(sale.total), if (big) 32.sp else 24.sp)
        TillSum(
            stringResource(R.string.till_given),
            if (state.given.isBlank()) stringResource(R.string.till_given_exact) else state.given + " €",
            if (big) 32.sp else 24.sp,
        )
        HorizontalDivider()
        if (given != null && change == null) {
            TillSum(stringResource(R.string.till_missing), Format.euro(CounterSales.euros(total - given)), if (big) 40.sp else 30.sp, MaterialTheme.colorScheme.error)
        } else {
            TillSum(stringResource(R.string.till_change), Format.euro(CounterSales.euros(change ?: 0)), if (big) 48.sp else 34.sp, MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TillSum(label: String, amount: String, size: TextUnit, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 22.sp, lineHeight = 26.sp, color = color, modifier = Modifier.weight(1f))
        Text(amount, fontSize = size, lineHeight = size * 1.2f, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** The notes somebody is likely to hand over, and keys for anything else: the tablet's keyboard would cover half of it all. */
@Composable
private fun TillGiven(state: TillState, sale: CounterSale, model: TillActions, height: Dp) {
    // A digit after a note starts over; it does not go on from the note's.
    var note by remember(sale.id) { mutableStateOf(false) }
    fun give(text: String) {
        note = true
        model.setGiven(text)
    }
    fun type(key: Char) {
        val before = if (note) "" else state.given
        note = false
        model.setGiven(tillTyped(before, key))
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fun tone(text: String) = if (state.given == text) TillTone.MAIN else TillTone.TONAL
        TillButton(stringResource(R.string.till_exact), { give("") }, Modifier.fillMaxWidth(), tone = tone(""), height = height)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (cents in CounterSales.notes(CounterSales.cents(sale.total))) {
                val text = tillPlain(cents)
                TillButton("$text €", { give(text) }, Modifier.weight(1f), tone = tone(text), height = height, size = 18.sp, maxLines = 1)
            }
        }
        val delete = stringResource(R.string.till_key_delete)
        for (row in listOf("789", "456", "123", ",0⌫")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in row) {
                    Surface(
                        onClick = { type(key) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f).heightIn(min = height).semantics { if (key == TILL_DELETE) contentDescription = delete },
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(key.toString(), fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

private const val TILL_DELETE = '⌫'

/** [given] after one more [key] of the till's own: a digit, the comma, or one back. Never anything that is no amount. */
internal fun tillTyped(given: String, key: Char): String {
    val next =
        when {
            key == TILL_DELETE -> given.dropLast(1)
            key == ',' -> given.ifEmpty { "0" } + ","
            given == "0" -> key.toString()
            else -> given + key
        }
    return if (TILL_TYPED.matches(next)) next else given
}

private val TILL_TYPED = Regex("(\\d{1,4}(,\\d{0,2})?)?")

/** "20" or "12,50": an amount as it would be typed. */
private fun tillPlain(cents: Long): String = if (cents % 100 == 0L) "${cents / 100}" else "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"

// ---------------------------------------------------------------------------
// Karte
// ---------------------------------------------------------------------------

@Composable
internal fun TillCardPane(state: TillState, sale: CounterSale, model: TillActions) {
    // The S-POS phone the card goes through (hamza-bistro-web 20261128100000):
    // this device's usual one picked already, another one a tap away. With
    // phones to pick from, nothing is booked before one is picked.
    val choice = model.cardChoice
    val offered = offeredTerminals(choice)
    var picked by remember(sale.id, choice) { mutableStateOf<CardTerminal?>((choice as? CardChoice.Usual)?.terminal) }
    TillPage(
        state,
        model,
        bottom = {
            TillButton(stringResource(R.string.till_card_declined), model::cardDeclined, Modifier.weight(1f), !state.busy, TillTone.NO, height = 88.dp, size = 20.sp)
            // The phone is the highlighted one above: a phone's narrow button has no room for its name too.
            TillButton(
                stringResource(R.string.till_card_paid),
                { model.cardPaidOn(picked) },
                Modifier.weight(1f),
                !state.busy && (offered.isEmpty() || picked != null),
                height = 88.dp,
                size = 20.sp,
            )
        },
    ) {
        TillColumn(Modifier.fillMaxSize()) {
            TillAmount(stringResource(R.string.till_card_title), sale.total, size = 72.sp)
            TillNote(stringResource(R.string.till_card_text))
            if (offered.isNotEmpty()) {
                TillNote(stringResource(R.string.card_terminal_pick))
                // As many in a row as fit a name like "S-POS 1" whole: all five on the
                // tablet, three and two on a phone.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val perRow = (maxWidth / 100.dp).toInt().coerceIn(1, offered.size)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in offered.chunked(perRow)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (terminal in row) {
                                    TillButton(
                                        terminal.name,
                                        { picked = terminal },
                                        Modifier.weight(1f),
                                        !state.busy,
                                        if (terminal == picked) TillTone.MAIN else TillTone.QUIET,
                                        height = 56.dp,
                                        size = 16.sp,
                                    )
                                }
                                // A short last row keeps the buttons' width.
                                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The receipt
// ---------------------------------------------------------------------------

@Composable
internal fun TillDonePane(
    state: TillState,
    sale: CounterSale,
    view: TillView,
    model: TillActions,
    onStorno: ((StornoTarget) -> Unit)? = null,
) {
    val receipt = sale.receipt
    // Its order is there once paid: the sale can be taken back from here.
    val order = sale.orderId
    TillPage(
        state,
        model,
        bottom = {
            // Unsigned, there is no receipt: on paper it would look like one. Nor without a printer.
            if (receipt != null && model.canPrint) {
                TillButton(
                    stringResource(if (view.printing) R.string.till_printing else R.string.till_print),
                    model::printReceipt,
                    Modifier.weight(1f),
                    enabled = !view.printing && sale.orderId != null,
                    tone = TillTone.QUIET,
                    height = 72.dp,
                )
            }
            if (onStorno != null && order != null) {
                TillButton(
                    stringResource(R.string.storno_button),
                    {
                        val paid = if (sale.payment == CounterPayment.CARD) PaymentMethod.CARD else PaymentMethod.CASH
                        onStorno(StornoTarget(order, sale.number, sale.total, paid))
                    },
                    Modifier.weight(1f),
                    enabled = !state.busy,
                    tone = TillTone.QUIET,
                    height = 72.dp,
                )
            }
            TillButton(stringResource(R.string.till_next), model::next, Modifier.weight(1f), !state.busy, height = 72.dp)
        },
    ) { wide ->
        if (wide && receipt != null) {
            Row(Modifier.fillMaxSize()) {
                TillColumn(Modifier.weight(1f).fillMaxHeight()) {
                    TillPaid(state, sale, view, 64.sp)
                    TillReceiptNotes(state, sale, model)
                }
                VerticalDivider()
                TillColumn(Modifier.weight(1f).fillMaxHeight()) { TillCode(receipt, 300.dp) }
            }
        } else {
            val roomy = maxWidth >= 600.dp
            TillColumn(Modifier.fillMaxSize()) {
                TillPaid(state, sale, view, if (roomy || receipt == null) 64.sp else 44.sp)
                // Held out to the customer: before everything that is only for the till.
                if (receipt != null) TillCode(receipt, if (roomy) 320.dp else 196.dp)
                TillReceiptNotes(state, sale, model)
            }
        }
    }
}

@Composable
private fun TillPaid(state: TillState, sale: CounterSale, view: TillView, size: TextUnit) {
    val method = stringResource(if (sale.payment == CounterPayment.CARD) R.string.pay_card else R.string.pay_cash)
    val change = state.changeCents?.takeIf { it > 0 && sale.payment == CounterPayment.CASH }
    if (change == null) {
        TillAmount(stringResource(R.string.till_paid, method), sale.total, size)
    } else {
        // The money is in the till; what is still to do is the change.
        Text(
            stringResource(R.string.till_paid, method) + " · " + Format.euro(sale.total),
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.till_change), fontSize = 20.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                Format.euro(CounterSales.euros(change)),
                fontSize = size,
                lineHeight = size * 1.15f,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
    view.printError?.let { TillNote(it, bad = true) }
    if (view.printed) TillNote(stringResource(R.string.till_printed), good = true)
}

/** The signature, and why nothing can be printed. */
@Composable
private fun TillReceiptNotes(state: TillState, sale: CounterSale, model: TillActions) {
    val receipt = sale.receipt
    if (receipt == null) TillNote(stringResource(R.string.till_no_receipt)) else TillReceiptState(receipt, state.stoppedWaiting)
    if (!model.canPrint) Text(stringResource(R.string.till_no_printer), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What the customer scans. */
@Composable
private fun TillCode(receipt: CounterReceipt, side: Dp) {
    QrImage(receipt.link, stringResource(R.string.till_qr), side)
    // In training it is said below, or beside: no receipt.
    if (!receipt.training) Text(stringResource(R.string.till_scan), fontSize = 18.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
}

/** What the receipt says about its signature, in short. */
@Composable
private fun TillReceiptState(receipt: CounterReceipt, stoppedWaiting: Boolean) {
    when {
        receipt.training -> TillNote(stringResource(R.string.till_receipt_training))
        receipt.signed && receipt.outage -> TillNote(stringResource(R.string.till_receipt_late))
        receipt.signed -> TillNote(stringResource(R.string.till_receipt_signed), good = true)
        receipt.pending && !receipt.outage && !stoppedWaiting ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                TillNote(stringResource(R.string.till_receipt_pending))
            }
        // The sale stands: the receipt says the TSE did not answer, and the signature follows.
        else -> TillNote(stringResource(R.string.till_receipt_outage), bad = true)
    }
    if (receipt.env == "TEST") TillNote(stringResource(R.string.till_receipt_test))
}

/** A QR code, black on white whatever the theme, with the border it needs. */
@Composable
internal fun QrImage(text: String, description: String, side: Dp = 240.dp) {
    val qr = remember(text) { Qr.of(text) }
    Canvas(
        Modifier.size(side).background(Color.White).padding(16.dp).semantics { contentDescription = description }
    ) {
        val dot = size.minDimension / qr.size
        for (row in 0 until qr.size) {
            for (column in 0 until qr.size) {
                if (qr.dark(row, column)) drawRect(Color.Black, Offset(column * dot, row * dot), Size(dot, dot))
            }
        }
    }
}
