package de.hamzabistro.printstation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AddressRequest
import de.hamzabistro.printstation.core.AddressRequests
import de.hamzabistro.printstation.core.AddressRequestsState
import de.hamzabistro.printstation.core.Fulfilment
import de.hamzabistro.printstation.core.PhoneNumbers
import de.hamzabistro.printstation.core.PhoneOrderError
import de.hamzabistro.printstation.core.PhoneOrders
import de.hamzabistro.printstation.core.SmsState
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The steps with a number on them: name, address, dishes, check. */
private const val STEPS = 4

/**
 * "Telefon-Bestellung": a phone order taken one screen at a time — the name;
 * the address, typed with Leipzig's streets offered, or texted for by SMS for
 * the caller to type themselves; the dishes, tapped as in the Kasse
 * ([DishesPane]); the total; saved. The same order as "Neue Bestellung"
 * ([NewOrderViewModel]), with the rest of its choices left to their defaults:
 * a phone delivery, as soon as possible, at the minutes an incoming order
 * would be offered.
 *
 * With the address coming by SMS, the order is saved to wait for it, and
 * the tablet puts it into the kitchen by itself the moment the caller has
 * typed it — during the call or after it ([de.hamzabistro.printstation.core.AddressRequestPlacer]).
 */
@Composable
fun CallOrderScreen(
    onBack: () -> Unit,
    onFullForm: () -> Unit,
    navigation: @Composable () -> Unit,
    leaving: Leaving = remember { Leaving() },
    model: NewOrderViewModel = viewModel(),
) {
    val graph = (LocalContext.current.applicationContext as PrintStationApp).graph
    val state by model.state.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    val links by graph.liveAddressRequests.collectAsStateWithLifecycle(AddressRequestsState())
    val link = links.byId(state.link?.id)
    LaunchedEffect(link) { model.linkRead(link) }
    // Again after a reset underneath — a new call taken from the banner.
    LaunchedEffect(state.callStarted) { model.startCall() }
    LaunchedEffect(Unit) { model.loadMenu() }
    // Where to, once the order is discarded.
    var discarding by remember { mutableStateOf<(() -> Unit)?>(null) }
    val finish: (() -> Unit) -> Unit = { go ->
        model.reset()
        go()
    }
    val leave: (() -> Unit) -> Unit = { go ->
        if (state.step == CallStep.DONE || (state.draft.lines.isEmpty() && state.link == null)) {
            finish(go)
        } else {
            discarding = go
        }
    }
    leaving.Ask(leave)
    // Back is a step back; from the first, out.
    BackHandler {
        when (state.step) {
            CallStep.NAME,
            CallStep.DONE -> leave(onBack)
            CallStep.ADDRESS -> model.goTo(CallStep.NAME)
            CallStep.DISHES -> model.goTo(CallStep.ADDRESS)
            CallStep.CONFIRM -> model.goTo(CallStep.DISHES)
        }
    }
    val actions = remember(graph) { LinkActions(graph) }
    CallOrderContent(state, photos, link, model, actions::retry, actions::resend, onFullForm, onDone = { finish(onBack) }, navigation)

    discarding?.let { go ->
        AskFirst(stringResource(R.string.new_order_discard), null, stringResource(R.string.new_order_discard_yes), onDismiss = { discarding = null }) {
            discarding = null
            finish(go)
        }
    }
}

/** Telefon-Bestellung as it is drawn: what it shows, and what its taps do. */
@Composable
internal fun CallOrderContent(
    state: NewOrderState,
    photos: Map<String, ImageBitmap?>,
    link: AddressRequest?,
    model: OrderActions,
    onRetry: (String) -> Unit,
    onResend: (String) -> Unit,
    onFullForm: () -> Unit,
    onDone: () -> Unit,
    navigation: @Composable () -> Unit,
) {
    val step =
        when (state.step) {
            CallStep.NAME -> stringResource(R.string.call_order_name_title)
            CallStep.ADDRESS -> stringResource(R.string.call_order_address_title)
            CallStep.DISHES -> stringResource(R.string.call_order_dishes_title)
            CallStep.CONFIRM -> stringResource(R.string.call_order_confirm_title)
            CallStep.DONE -> null
        }
    Scaffold(
        topBar = {
            SlimHeader(
                stringResource(R.string.call_order),
                navigation,
                note = step?.let { stringResource(R.string.call_order_step, state.step.ordinal + 1, STEPS) + " · " + it },
                busy = state.saving || state.savingWaiting,
                // For whoever wants the time, the source or the fee waiver: the same order in the full form.
                action = {
                    if (state.step != CallStep.DONE) {
                        TextButton(onClick = onFullForm, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.call_order_full_form), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.step) {
                CallStep.NAME -> NameStep(state, model)
                CallStep.ADDRESS -> AddressStep(state, model, link)
                CallStep.DISHES -> DishesStep(state, photos, model)
                CallStep.CONFIRM -> ConfirmStep(state, model, link)
                CallStep.DONE -> DoneStep(state, link, onRetry, onResend, onDone)
            }
        }
    }
}

/** One step: what belongs to it, in one column or, where there is room, two; its buttons along the bottom, always in reach. */
@Composable
private fun StepPage(bottom: @Composable RowScope.() -> Unit, content: @Composable (wide: Boolean) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) { content(maxWidth >= WIDE_FROM) }
        HorizontalDivider()
        BottomBar(bottom)
    }
}

@Composable
private fun StepColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 1. The name
// ---------------------------------------------------------------------------

@Composable
private fun NameStep(state: NewOrderState, model: OrderActions) {
    val draft = state.draft
    val focus = remember { FocusRequester() }
    // The keyboard up at once: the first thing to do is type.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    StepPage(
        bottom = {
            BigButton(
                stringResource(R.string.call_order_next),
                onClick = { model.goTo(CallStep.ADDRESS) },
                enabled = draft.name.isNotBlank() && draft.phone.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        StepColumn(Modifier.fillMaxSize()) { ContactFields(state, model, Modifier.focusRequester(focus)) }
    }
}

// ---------------------------------------------------------------------------
// 2. The address — typed, picked, or texted for
// ---------------------------------------------------------------------------

@Composable
private fun AddressStep(state: NewOrderState, model: OrderActions, link: AddressRequest?) {
    val draft = state.draft
    val outside = state.addressChecked && state.address?.outside == true
    val goOn = !draft.delivery || state.link != null || (PhoneOrders.addressComplete(draft) && !outside)
    StepPage(
        bottom = {
            BigButton(stringResource(R.string.call_order_back), onClick = { model.goTo(CallStep.NAME) }, outlined = true)
            BigButton(stringResource(R.string.call_order_next), onClick = { model.goTo(CallStep.DISHES) }, enabled = goOn, modifier = Modifier.weight(1f))
        },
    ) { wide ->
        val where = @Composable { FulfilmentChoice(state, model, listOf(Fulfilment.DELIVERY, Fulfilment.PICKUP)) }
        when {
            !draft.delivery -> StepColumn(Modifier.fillMaxSize()) { where() }
            wide ->
                Row(Modifier.fillMaxSize()) {
                    StepColumn(Modifier.weight(1f).fillMaxHeight()) {
                        where()
                        AddressFields(state, model, help = false)
                    }
                    VerticalDivider()
                    StepColumn(Modifier.weight(1f).fillMaxHeight()) {
                        AddressHelp(state, model)
                        TextedAddress(state, model, link)
                    }
                }
            else ->
                StepColumn(Modifier.fillMaxSize()) {
                    where()
                    AddressFields(state, model, help = true)
                    HorizontalDivider()
                    TextedAddress(state, model, link)
                }
        }
    }
}

@Composable
private fun TextedAddress(state: NewOrderState, model: OrderActions, link: AddressRequest?) {
    val phone = state.draft.phone
    // A landline would have the link read out to it: no text there.
    val textable = PhoneNumbers.textable(phone)
    if (state.link == null) {
        BigButton(
            stringResource(R.string.call_order_sms),
            onClick = model::sendLink,
            enabled = textable && !state.linking,
            tonal = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                when {
                    phone.isBlank() -> R.string.call_order_sms_needs_phone
                    !textable -> R.string.call_order_sms_needs_mobile
                    else -> R.string.call_order_sms_hint
                }
            ),
            style = LINE_SMALL,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        SmsStatus(link, onAgain = model::sendLink)
        val arrived = link?.address
        if (arrived != null) StatusLine(stringResource(R.string.call_order_address_arrived, arrived.line), good = true)
        else StatusLine(stringResource(R.string.call_order_address_pending))
    }
    state.linkError?.let { StatusLine(stringResource(R.string.call_order_link_failed, it), bad = true) }
}

/** How the text with the link fares, and "Nochmal senden" when it did not go out. */
@Composable
private fun SmsStatus(link: AddressRequest?, onAgain: () -> Unit) {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(TICK)
            value = Instant.now()
        }
    }
    val failed = link?.sms == SmsState.FAILED
    val unsent = link != null && AddressRequests.unsent(link, now)
    when {
        link == null -> StatusLine(stringResource(R.string.call_order_sms_sending))
        link.sms == SmsState.SENT -> StatusLine(stringResource(R.string.call_order_sms_sent), good = true)
        failed -> StatusLine(stringResource(R.string.call_order_sms_failed, link.smsError.orEmpty()), bad = true)
        unsent -> StatusLine(stringResource(R.string.call_order_sms_no_sender), bad = true)
        else -> StatusLine(stringResource(R.string.call_order_sms_sending))
    }
    if (failed || unsent) BigButton(stringResource(R.string.call_order_sms_again), onClick = onAgain, outlined = true)
}

// ---------------------------------------------------------------------------
// 3. The dishes, as in the Kasse
// ---------------------------------------------------------------------------

@Composable
private fun DishesStep(state: NewOrderState, photos: Map<String, ImageBitmap?>, model: OrderActions) {
    val lines = state.draft.lines
    val total = lines.sumOf { it.unitPrice * it.qty }
    DishesPane(
        orderShop(state, photos, model),
        Basket(
            lines,
            total,
            stringResource(R.string.call_order_to_basket),
            onAdd = model::add,
            onQty = model::setQty,
            onNote = model::setNote,
        ),
        over = { modifier ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { model.goTo(CallStep.ADDRESS) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.call_order_back), fontSize = 17.sp, maxLines = 1)
                }
                EmptyBasketButton(lines.isNotEmpty(), model::clear)
            }
        },
        go = { GoButton(stringResource(R.string.call_order_next), Format.euro(total), lines.isNotEmpty(), { model.goTo(CallStep.CONFIRM) }, it) },
    )
}

// ---------------------------------------------------------------------------
// 4. The check: who, where, what, how much
// ---------------------------------------------------------------------------

@Composable
private fun ConfirmStep(state: NewOrderState, model: OrderActions, link: AddressRequest?) {
    val draft = state.draft
    val waitsForLink = state.awaitingLink
    val quote = state.quote
    val problems = PhoneOrders.problems(draft, Instant.now())
    val subtotal = draft.lines.sumOf { it.unitPrice * it.qty }
    StepPage(
        bottom = {
            BigButton(stringResource(R.string.call_order_back), onClick = { model.goTo(CallStep.DISHES) }, outlined = true)
            if (waitsForLink) {
                BigButton(
                    if (state.savingWaiting) stringResource(R.string.new_order_saving) else stringResource(R.string.call_order_save_waiting),
                    onClick = model::saveWaiting,
                    enabled = draft.lines.isNotEmpty() && !state.savingWaiting,
                    modifier = Modifier.weight(1f),
                )
            } else {
                GoButton(
                    stringResource(if (state.saving) R.string.new_order_saving else R.string.call_order_save),
                    if (quote != null && state.priced) Format.euro(quote.total) else "—",
                    enabled = state.priced && !state.saving && !state.quoting && problems.isEmpty(),
                    onClick = model::save,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { wide ->
        val dishes: @Composable ColumnScope.() -> Unit = {
            if (draft.lines.isEmpty()) StatusLine(stringResource(R.string.till_empty))
            draft.lines.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                BasketRow(line, { model.setQty(index, it) }, { model.setNote(index, it) })
            }
        }
        val rest: @Composable ColumnScope.() -> Unit = {
            Who(state, model, link)
            if (waitsForLink) {
                PriceLine(stringResource(R.string.new_order_subtotal), subtotal, bold = true)
                Text(stringResource(R.string.call_order_fee_later), style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (problems.isEmpty()) {
                PriceLines(state)
            }
            state.error?.let { StatusLine(it, bad = true) }
            // What to tell the caller.
            if (!waitsForLink && draft.lines.isNotEmpty()) {
                Text(stringResource(R.string.call_order_ready_in, model.suggestedMinutes(state)), fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = dishes)
                VerticalDivider()
                StepColumn(Modifier.weight(1f).fillMaxHeight(), rest)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = rest)
                HorizontalDivider()
                Column(Modifier.padding(vertical = 8.dp), content = dishes)
            }
        }
    }
}

/** Whose order it is, and where it goes. */
@Composable
private fun Who(state: NewOrderState, model: OrderActions, link: AddressRequest?) {
    val draft = state.draft
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("👤 " + draft.name, style = LINE_NAME)
            Text("📞 " + draft.phone, style = LINE_NAME)
            when {
                !draft.delivery -> Text(stringResource(R.string.call_order_pickup_chosen), style = LINE_NAME)
                state.awaitingLink -> SmsStatus(link, onAgain = model::sendLink)
                else -> {
                    Text("🏠 ${draft.street}, ${draft.postalCode} ${draft.city}", style = LINE_NAME)
                    if (draft.addressNote.isNotBlank()) Text(draft.addressNote, style = LINE_SMALL)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Done: in the kitchen, or waiting for the address
// ---------------------------------------------------------------------------

@Composable
private fun DoneStep(state: NewOrderState, link: AddressRequest?, onRetry: (String) -> Unit, onResend: (String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    StepPage(bottom = { BigButton(stringResource(R.string.call_order_done), onClick = onDone, modifier = Modifier.weight(1f)) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            val saved = state.saved
            val problem = link?.problem
            when {
                saved != null -> {
                    DoneLine(stringResource(R.string.call_order_saved, saved.orderNumber ?: 0L))
                    if (saved.overCapacity) StatusLine(stringResource(R.string.new_order_saved_full), soon = true)
                }
                link != null && link.placed -> DoneLine(stringResource(R.string.call_order_saved, link.orderNumber ?: 0L))
                link != null && problem != null -> {
                    Text(
                        stringResource(R.string.call_order_problem, problemText(problem)),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    BigButton(stringResource(R.string.call_order_call, PhoneNumbers.national(link.phone)), onClick = { dial(context, link.phone) })
                    BigButton(stringResource(R.string.call_order_retry), onClick = { onRetry(link.id) }, outlined = true)
                }
                else -> {
                    CircularProgressIndicator(Modifier.size(64.dp))
                    Text(stringResource(R.string.call_order_waiting_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.call_order_waiting_text), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    val arrived = link?.address
                    if (arrived != null) StatusLine(stringResource(R.string.call_order_address_arrived, arrived.line), good = true)
                    else SmsStatus(link) { link?.let { onResend(it.id) } }
                }
            }
        }
    }
}

@Composable
private fun DoneLine(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = toneColor(Tone.OK), textAlign = TextAlign.Center)
}

/** What [problem] — a refusal's code, as the tablet noted it — means, in words. */
@Composable
private fun problemText(problem: String): String =
    when (PhoneOrderError.of(problem)) {
        PhoneOrderError.OUTSIDE -> stringResource(R.string.address_problem_outside)
        PhoneOrderError.MINIMUM -> stringResource(R.string.address_problem_minimum)
        PhoneOrderError.UNCHECKED -> stringResource(R.string.address_problem_unchecked)
        PhoneOrderError.BASKET -> stringResource(R.string.address_problem_basket)
        else -> stringResource(R.string.address_problem_other, problem)
    }

private fun dial(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.filterNot(Char::isWhitespace), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // A tablet without a dialler: the number is on the screen.
    }
}

/** What can be done to a link from any screen: sent again, tried again, deleted. The screen follows from the database. */
internal class LinkActions(private val graph: AppGraph) {
    fun resend(id: String) = change { graph.addressRequests.resend(id) }

    fun retry(id: String) = change { graph.addressRequests.noteProblem(id, null) }

    fun cancel(id: String) = change { graph.addressRequests.cancel(id) }

    private fun change(block: suspend () -> Unit) {
        graph.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.logger.warn("The address link could not be changed", e)
            }
            graph.addressWatch.refresh()
        }
    }
}

// ---------------------------------------------------------------------------
// On the queue: the orders waiting for their address
// ---------------------------------------------------------------------------

/**
 * Above the queue, while any basket waits for its caller's address: who,
 * what, and how far it got — the text, the address, a problem for a
 * person. Shown on every device, so nobody forgets one; placed by the
 * device that took it.
 */
@Composable
fun WaitingForAddress(modifier: Modifier = Modifier) {
    val graph = (LocalContext.current.applicationContext as PrintStationApp).graph
    val read by graph.liveAddressRequests.collectAsStateWithLifecycle(AddressRequestsState())
    val actions = remember(graph) { LinkActions(graph) }
    WaitingCards(AddressRequests.waiting(read.requests), actions::resend, actions::retry, actions::cancel, modifier)
}

@Composable
internal fun WaitingCards(
    waiting: List<AddressRequest>,
    onResend: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (waiting.isEmpty()) return
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<AddressRequest?>(null) }
    val now by produceState(Instant.now()) {
        while (true) {
            delay(TICK)
            value = Instant.now()
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.waiting_heading, waiting.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (request in waiting) {
                val problem = request.problem
                val expired = request.address == null && !request.createdAt.isAfter(now.minus(AddressRequests.OPEN_FOR.toJavaDuration()))
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = if (problem != null || expired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                        ),
                    modifier = Modifier.width(360.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            listOf(request.customerName, PhoneNumbers.national(request.phone)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(request.lines.joinToString(", ") { "${it.qty}× ${it.name}" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        when {
                            problem != null ->
                                Text(stringResource(R.string.call_order_problem, problemText(problem)), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            request.address != null -> Text(stringResource(R.string.waiting_address, request.address?.line.orEmpty()))
                            expired -> Text(stringResource(R.string.waiting_expired), color = MaterialTheme.colorScheme.error)
                            request.sms == SmsState.SENT -> Text(stringResource(R.string.call_order_sms_sent))
                            request.sms == SmsState.FAILED ->
                                Text(stringResource(R.string.call_order_sms_failed, request.smsError.orEmpty()), color = MaterialTheme.colorScheme.error)
                            AddressRequests.unsent(request, now) -> Text(stringResource(R.string.call_order_sms_no_sender), color = MaterialTheme.colorScheme.error)
                            else -> Text(stringResource(R.string.call_order_sms_sending))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { dial(context, request.phone) }) { Text("📞") }
                            if (problem != null) TextButton(onClick = { onRetry(request.id) }) { Text(stringResource(R.string.call_order_retry)) }
                            if (problem == null && request.address == null && !expired && (request.sms == SmsState.FAILED || AddressRequests.unsent(request, now))) {
                                TextButton(onClick = { onResend(request.id) }) { Text(stringResource(R.string.call_order_sms_again)) }
                            }
                            TextButton(onClick = { deleting = request }) { Text(stringResource(R.string.waiting_delete)) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { request ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.waiting_delete_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleting = null
                        onDelete(request.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.waiting_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel_back)) } },
        )
    }
}

/** How often a line that depends on the time — "no phone has sent it" — is looked at again. */
private val TICK = 5.seconds
