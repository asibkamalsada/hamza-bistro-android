package de.hamzabistro.printstation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.CounterSale
import de.hamzabistro.printstation.core.CounterSales
import de.hamzabistro.printstation.core.TillState

// The Kasse's dishes: the pane every order has, with where it is eaten over
// the basket, "Kassieren" below it, and the sales still open.

@Composable
internal fun TillDishesPane(state: TillState, view: TillView, photos: Map<String, ImageBitmap?>, model: TillActions) {
    var openUp by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.open.isEmpty()) { if (state.open.isEmpty()) openUp = false }
    val total = CounterSales.euros(state.totalCents)
    Box(Modifier.fillMaxSize()) {
        DishesPane(
            Shop(view.menu, view.menuError, view.bestsellers, photos, model::loadPhoto, model::loadMenu),
            Basket(
                state.lines,
                total,
                stringResource(R.string.till_to_basket),
                onAdd = model::add,
                onQty = model::setQty,
                deal = state.price?.dealDiscount?.takeIf { state.priced && it > 0 },
            ),
            over = { TillWhere(state, model, it) },
            go = {
                GoButton(stringResource(R.string.till_ring_up), Format.euro(total), state.lines.isNotEmpty() && !state.busy, model::ringUp, it)
            },
            above = { TillOpenBar(state, model) { openUp = true } },
            problem = state.problem?.let { problem -> @Composable { modifier: Modifier -> TillProblemCard(problem, model::dismissProblem, modifier) } },
        )
        if (openUp && state.open.isNotEmpty()) TillOpenSales(state, onResume = model::resume, onDismiss = { openUp = false })
    }
}

/** Mitnehmen or hier essen, and everything out again. */
@Composable
private fun TillWhere(state: TillState, model: TillActions, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Choice(
            listOf(stringResource(R.string.till_takeaway), stringResource(R.string.till_dine_in)),
            selected = if (state.dineIn) 1 else 0,
            Modifier.weight(1f),
        ) {
            model.setDineIn(it == 1)
        }
        EmptyBasketButton(state.lines.isNotEmpty() && !state.busy, model::clear)
    }
}

// ---------------------------------------------------------------------------
// The sales still open
// ---------------------------------------------------------------------------

/** One line for the sales somebody has to see to: red when one is from an earlier day, or its card was asked. */
@Composable
private fun TillOpenBar(state: TillState, model: TillActions, onShow: () -> Unit) {
    if (state.open.isEmpty()) return
    val one = state.open.singleOrNull()
    val stale = state.open.count { it.stale }
    val card = state.open.count { it.cardPending }
    Surface(
        onClick = { if (one != null) model.resume(one) else onShow() },
        enabled = !state.busy,
        shape = MaterialTheme.shapes.medium,
        color = if (stale + card > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (stale + card > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val what =
                when {
                    one == null -> pluralStringResource(R.plurals.till_open_count, state.open.size, state.open.size)
                    one.training -> stringResource(R.string.till_open_training, one.number, Format.euro(one.total))
                    else -> stringResource(R.string.till_open_sale, one.number, Format.euro(one.total))
                }
            val why =
                when {
                    stale > 0 -> stringResource(R.string.till_open_stale_short).let { if (one != null) it else "$stale $it" }
                    card > 0 -> stringResource(R.string.till_open_card_short).let { if (one != null) it else "$card× $it" }
                    else -> null
                }
            Text(
                listOfNotNull(what, why).joinToString(" · "),
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (one != null) R.string.till_resume else R.string.till_open_show), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** All of them, over the whole screen: which, since when, where, and what is the matter with it. */
@Composable
private fun TillOpenSales(state: TillState, onResume: (CounterSale) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.weight(1f).widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.till_open_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                for (sale in state.open) OpenSaleCard(sale, enabled = !state.busy) { onResume(sale) }
            }
            HorizontalDivider()
            BottomBar { BigButton(stringResource(R.string.cancel_back), onClick = onDismiss, outlined = true) }
        }
    }
}

/** A sale rung up and not finished, here or on another device. */
@Composable
internal fun OpenSaleCard(sale: CounterSale, enabled: Boolean, onResume: () -> Unit) {
    Card(
        onClick = onResume,
        enabled = enabled,
        colors =
            if (sale.stale || sale.cardPending) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val goOn: @Composable (Modifier) -> Unit = { modifier ->
                Text(
                    stringResource(R.string.till_resume),
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = modifier,
                )
            }
            if (maxWidth >= 480.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OpenSaleWords(sale, Modifier.weight(1f))
                    goOn(Modifier.padding(start = 12.dp))
                }
            } else {
                Column {
                    OpenSaleWords(sale, Modifier.fillMaxWidth())
                    goOn(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun OpenSaleWords(sale: CounterSale, modifier: Modifier) {
    Column(modifier) {
        Text(
            stringResource(if (sale.training) R.string.till_open_training else R.string.till_open_sale, sale.number, Format.euro(sale.total)),
            fontSize = 19.sp,
            lineHeight = 25.sp,
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
}
