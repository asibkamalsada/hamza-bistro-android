package de.hamzabistro.printstation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AddressRequest
import de.hamzabistro.printstation.core.AddressRequests
import de.hamzabistro.printstation.core.AddressRequestsState
import de.hamzabistro.printstation.core.AddressStatus
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.Fulfilment
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OptionRules
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.PhoneNumbers
import de.hamzabistro.printstation.core.PhoneOrderError
import de.hamzabistro.printstation.core.PhoneOrderQuote
import de.hamzabistro.printstation.core.PhoneOrders
import de.hamzabistro.printstation.core.Selection
import de.hamzabistro.printstation.core.SmsState
import de.hamzabistro.printstation.core.StreetDelivery
import de.hamzabistro.printstation.core.StreetSuggestion
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How much bigger the call screens are than the rest of the app, on a
 * tablet: the person taking the call may not read well, nor see well, and
 * taps with a finger that has just been holding the phone.
 */
private const val ZOOM = 1.3f

/** Up to this on its shorter side, a screen is a phone's, with no room to spare: drawn as it is. */
private val ZOOM_NONE_UP_TO = 440.dp

/** From this on its shorter side, a screen is a tablet's: drawn [ZOOM] times as big. */
private val ZOOM_FULL_FROM = 640.dp

/** The steps with a number on them: name, address, dishes, check. */
private const val STEPS = 4

/**
 * "Telefon-Bestellung": a phone order taken one screen at a time, for
 * anybody — the name; the address, typed with Leipzig's streets offered, or
 * texted for by SMS for the caller to type themselves; the dishes, as big
 * tiles with their photos; the total; saved. The same order as "Neue
 * Bestellung" ([NewOrderViewModel]), with the rest of its choices left to
 * their defaults: a phone delivery, as soon as possible, at the minutes an
 * incoming order would be offered.
 *
 * With the address coming by SMS, the order is saved to wait for it, and
 * the tablet puts it into the kitchen by itself the moment the caller has
 * typed it — during the call or after it ([de.hamzabistro.printstation.core.AddressRequestPlacer]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallOrderScreen(
    onBack: () -> Unit,
    onFullForm: () -> Unit,
    model: NewOrderViewModel = viewModel(),
    /** Whether "SMS an den Anrufer senden" is offered: only once something sends the texts ([AppGraph.sms]). */
    texting: Boolean = (LocalContext.current.applicationContext as PrintStationApp).graph.textingAvailable,
) {
    val graph = (LocalContext.current.applicationContext as PrintStationApp).graph
    val state by model.state.collectAsStateWithLifecycle()
    val links by graph.liveAddressRequests.collectAsStateWithLifecycle(AddressRequestsState())
    val link = links.byId(state.link?.id)
    LaunchedEffect(link) { model.linkRead(link) }
    // Again after a reset underneath — a new call taken from the banner.
    LaunchedEffect(state.callStarted) { model.startCall() }
    LaunchedEffect(Unit) { model.loadMenu() }
    var discarding by remember { mutableStateOf(false) }
    val finish = {
        model.reset()
        onBack()
    }
    val leave = { if (state.step == CallStep.DONE || (state.draft.lines.isEmpty() && state.link == null)) finish() else discarding = true }
    // Back is a step back; from the first, out.
    BackHandler {
        when (state.step) {
            CallStep.NAME,
            CallStep.DONE -> leave()
            CallStep.ADDRESS -> model.goTo(CallStep.NAME)
            CallStep.DISHES -> model.goTo(CallStep.ADDRESS)
            CallStep.CONFIRM -> model.goTo(CallStep.DISHES)
        }
    }

    Zoomed {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.call_order_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = { TextButton(onClick = leave) { Text("✕", style = MaterialTheme.typography.titleLarge) } },
                    actions = {
                        // For whoever wants the time, the source or the fee waiver: the same order in the full form.
                        if (state.step != CallStep.DONE) TextButton(onClick = onFullForm) { Text(stringResource(R.string.call_order_full_form)) }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (state.step) {
                    CallStep.NAME -> NameStep(state, model)
                    CallStep.ADDRESS -> AddressStep(state, model, link, texting)
                    CallStep.DISHES -> DishesStep(state, model)
                    CallStep.CONFIRM -> ConfirmStep(state, model, link)
                    CallStep.DONE -> DoneStep(state, link, remember(graph) { LinkActions(graph) }, onDone = finish)
                }
            }
        }
    }

    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            text = { Text(stringResource(R.string.new_order_discard), style = MaterialTheme.typography.titleLarge) },
            confirmButton = {
                Button(onClick = {
                    discarding = false
                    finish()
                }) { Text(stringResource(R.string.new_order_discard_yes)) }
            },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(R.string.cancel_back)) } },
        )
    }
}

/**
 * Everything inside drawn bigger, text and touch targets alike, as far as
 * the screen has room for it ([zoomFor]): a phone's is drawn as it is, or
 * its button bar and the dishes no longer fit.
 */
@Composable
internal fun Zoomed(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val zoom = zoomFor(minOf(maxWidth, maxHeight))
        CompositionLocalProvider(LocalDensity provides Density(density.density * zoom, density.fontScale), content = content)
    }
}

/** [ZOOM] from [ZOOM_FULL_FROM] on the screen's shorter side, none up to [ZOOM_NONE_UP_TO], and in between, in between. */
internal fun zoomFor(shorterSide: Dp): Float {
    val share = ((shorterSide - ZOOM_NONE_UP_TO) / (ZOOM_FULL_FROM - ZOOM_NONE_UP_TO)).coerceIn(0f, 1f)
    return 1f + (ZOOM - 1f) * share
}

/** One step: which of how many, what it asks, its fields; the buttons along the bottom, always in reach. */
@Composable
private fun StepPage(number: Int, title: String, bottom: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepHeading(number, title)
            content()
        }
        HorizontalDivider()
        BottomBar(bottom)
    }
}

/** The buttons along the bottom: "Zurück" as wide as it needs, the step's own button the rest. */
@Composable
private fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun StepHeading(number: Int, title: String) {
    Column {
        Text(stringResource(R.string.call_order_step, number, STEPS), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

/** A button big enough to hit without looking twice. */
@Composable
private fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false, tonal: Boolean = false) {
    val label: @Composable RowScope.() -> Unit = {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val big = modifier.heightIn(min = 64.dp)
    val padding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    when {
        outlined -> OutlinedButton(onClick = onClick, modifier = big, enabled = enabled, contentPadding = padding, content = label)
        tonal -> FilledTonalButton(onClick = onClick, modifier = big, enabled = enabled, contentPadding = padding, content = label)
        else -> Button(onClick = onClick, modifier = big, enabled = enabled, contentPadding = padding, content = label)
    }
}

@Composable
private fun BigField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.headlineSmall,
        singleLine = true,
        keyboardOptions = keyboard,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A line that says how something went: green for done, red for wrong. */
@Composable
private fun StatusLine(text: String, good: Boolean = false, bad: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color =
            when {
                bad -> MaterialTheme.colorScheme.error
                good -> toneColor(Tone.OK)
                else -> MaterialTheme.colorScheme.onSurface
            },
    )
}

// ---------------------------------------------------------------------------
// 1. The name
// ---------------------------------------------------------------------------

@Composable
private fun NameStep(state: NewOrderState, model: NewOrderViewModel) {
    val draft = state.draft
    val focus = remember { FocusRequester() }
    // The keyboard up at once: the first thing to do is type.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    StepPage(
        1,
        stringResource(R.string.call_order_name_title),
        bottom = {
            BigButton(
                stringResource(R.string.call_order_next),
                onClick = { model.goTo(CallStep.ADDRESS) },
                enabled = draft.name.isNotBlank() && draft.phone.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        BigField(
            draft.name,
            { v -> model.edit { it.copy(name = v) } },
            stringResource(R.string.call_order_name),
            Modifier.focusRequester(focus),
            KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        // Filled in from the call; typed only when the shop's phone did not say.
        BigField(
            draft.phone,
            { v -> model.edit { it.copy(phone = v) } },
            stringResource(R.string.call_order_phone),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        )
    }
}

// ---------------------------------------------------------------------------
// 2. The address — typed, picked, or texted for
// ---------------------------------------------------------------------------

@Composable
private fun AddressStep(state: NewOrderState, model: NewOrderViewModel, link: AddressRequest?, texting: Boolean) {
    val draft = state.draft
    val outside = state.addressChecked && state.address?.outside == true
    val goOn = !draft.delivery || state.link != null || (PhoneOrders.addressComplete(draft) && !outside)
    StepPage(
        2,
        stringResource(R.string.call_order_address_title),
        bottom = {
            BigButton(stringResource(R.string.call_order_back), onClick = { model.goTo(CallStep.NAME) }, outlined = true)
            BigButton(stringResource(R.string.call_order_next), onClick = { model.goTo(CallStep.DISHES) }, enabled = goOn, modifier = Modifier.weight(1f))
        },
    ) {
        if (!draft.delivery) {
            StatusLine(stringResource(R.string.call_order_pickup_chosen))
            BigButton(stringResource(R.string.call_order_deliver), onClick = { model.edit { it.copy(fulfilment = Fulfilment.DELIVERY) } }, outlined = true)
        } else {
            TypedAddress(state, model, outside)
            HorizontalDivider()
            if (texting || state.link != null) {
                TextedAddress(state, model, link)
                HorizontalDivider()
            }
            OutlinedButton(onClick = { model.edit { it.copy(fulfilment = Fulfilment.PICKUP) } }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.call_order_pickup), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun TypedAddress(state: NewOrderState, model: NewOrderViewModel, outside: Boolean) {
    val draft = state.draft
    // A caller who has ordered before: one tap is the whole address.
    for (recent in state.recentChoices) {
        OutlinedCard(Modifier.fillMaxWidth().clickable { model.pickAddress(recent) }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("🏠 " + recent.line, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (recent.addressNote.isNotBlank()) Text(recent.addressNote, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    BigField(
        draft.street,
        { v -> model.edit { it.copy(street = v) } },
        stringResource(R.string.call_order_street),
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )
    BigStreetChoices(state.streetChoices, model::pickStreet)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.postalCode,
            onValueChange = { v -> model.edit { it.copy(postalCode = v.filter(Char::isDigit).take(5)) } },
            label = { Text(stringResource(R.string.new_order_postal_code)) },
            textStyle = MaterialTheme.typography.headlineSmall,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = draft.city,
            onValueChange = { v -> model.edit { it.copy(city = v) } },
            label = { Text(stringResource(R.string.new_order_city)) },
            textStyle = MaterialTheme.typography.headlineSmall,
            singleLine = true,
            modifier = Modifier.weight(2f),
        )
    }
    BigField(draft.addressNote, { v -> model.edit { it.copy(addressNote = v) } }, stringResource(R.string.new_order_door_note))
    val address = state.address
    when {
        state.checking -> StatusLine(stringResource(R.string.new_order_checking_address))
        state.addressError != null -> {
            StatusLine(stringResource(R.string.new_order_address_failed, state.addressError), bad = true)
            BigButton(stringResource(R.string.new_order_check_again), onClick = model::checkAgain, outlined = true)
        }
        outside -> StatusLine(stringResource(R.string.call_order_outside), bad = true)
        address != null && state.addressChecked && address.status == AddressStatus.VERIFIED ->
            StatusLine("✓ " + stringResource(R.string.new_order_address_ok, ringWord(address.zone)), good = true)
        address != null && state.addressChecked && address.status == AddressStatus.UNVERIFIED ->
            StatusLine(stringResource(R.string.new_order_address_unverified))
    }
    address?.suggestedPostalCode?.takeIf { state.addressChecked }?.let { code ->
        StatusLine(stringResource(R.string.new_order_address_suggest, code))
        BigButton(stringResource(R.string.new_order_address_use, code), onClick = { model.edit { it.copy(postalCode = code) } }, outlined = true)
    }
}

@Composable
private fun TextedAddress(state: NewOrderState, model: NewOrderViewModel, link: AddressRequest?) {
    val phone = state.draft.phone
    if (state.link == null) {
        BigButton(
            stringResource(R.string.call_order_sms),
            onClick = model::sendLink,
            enabled = phone.isNotBlank() && !state.linking,
            tonal = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (phone.isBlank()) R.string.call_order_sms_needs_phone else R.string.call_order_sms_hint),
            style = MaterialTheme.typography.titleMedium,
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

/** Leipzig's streets like the one typed, big: a tap takes the name as the city spells it, its postcode and the town. */
@Composable
private fun BigStreetChoices(streets: List<StreetSuggestion>, onPick: (StreetSuggestion) -> Unit) {
    for (street in streets) {
        OutlinedCard(Modifier.fillMaxWidth().clickable { onPick(street) }) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(street.fullStreet, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(street.entry.postcodes.joinToString(", "), street.entry.district).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (street.entry.delivery) {
                    StreetDelivery.OUTSIDE ->
                        Text("✗ " + stringResource(R.string.new_order_street_outside), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    StreetDelivery.PARTLY -> Text(stringResource(R.string.new_order_street_partly), color = toneColor(Tone.SOON))
                    StreetDelivery.INSIDE -> Unit
                }
            }
        }
    }
}

/** The ring's name as the report says it. */
@Composable
private fun ringWord(zone: String?): String {
    val resources = LocalResources.current
    return when (zone) {
        "inner" -> resources.getString(R.string.report_ring_inner)
        "near" -> resources.getString(R.string.report_ring_near)
        "far" -> resources.getString(R.string.report_ring_far)
        "edge" -> resources.getString(R.string.report_ring_edge)
        else -> zone.orEmpty()
    }
}

// ---------------------------------------------------------------------------
// 3. The dishes
// ---------------------------------------------------------------------------

@Composable
private fun DishesStep(state: NewOrderState, model: NewOrderViewModel) {
    val menu = state.menu
    var picking by remember { mutableStateOf<MenuDish?>(null) }
    val photos by model.photos.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        DishesPage(
            menu = menu,
            menuError = state.menuError,
            lines = state.draft.lines,
            photos = photos,
            onPhoto = model::loadPhoto,
            onRetry = model::loadMenu,
            onBack = { model.goTo(CallStep.ADDRESS) },
            onNext = { model.goTo(CallStep.CONFIRM) },
        ) { dish, groups ->
            // Nothing to choose: in the basket with one tap.
            if (groups.isEmpty()) model.add(BasketLine(dish, 1)) else picking = dish
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

/**
 * Step 3 as it is drawn: the tiles, and under them what is in the basket
 * — on a line of its own, so a narrow screen squeezes neither it nor the
 * buttons beside it.
 */
@Composable
internal fun DishesPage(
    menu: OrderMenu?,
    menuError: String?,
    lines: List<BasketLine>,
    photos: Map<String, ImageBitmap?>,
    onPhoto: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onTap: (MenuDish, List<OptionGroup>) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StepHeading(3, stringResource(R.string.call_order_dishes_title))
            when {
                menu != null -> DishGrid(menu, lines, photos, onPhoto, onTap)
                menuError != null -> {
                    StatusLine(stringResource(R.string.new_order_menu_failed, menuError), bad = true)
                    BigButton(stringResource(R.string.retry), onClick = onRetry, outlined = true)
                }
                else ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.new_order_menu_loading), style = MaterialTheme.typography.titleLarge)
                    }
            }
        }
        HorizontalDivider()
        val count = lines.sumOf { it.qty }
        Text(
            pluralStringResource(R.plurals.call_order_cart, count, count, Format.euro(lines.sumOf { it.unitPrice * it.qty })),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        )
        BottomBar {
            BigButton(stringResource(R.string.call_order_back), onClick = onBack, outlined = true)
            BigButton(stringResource(R.string.call_order_next), onClick = onNext, enabled = lines.isNotEmpty(), modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The menu as big tiles, a category at a time: the photo, the name, the
 * price, and how many are in the basket already. [onTap] is given the
 * dish's choices, empty when there is nothing to choose.
 */
@Composable
internal fun DishGrid(
    menu: OrderMenu,
    lines: List<BasketLine>,
    photos: Map<String, ImageBitmap?>,
    onPhoto: (String) -> Unit,
    onTap: (MenuDish, List<OptionGroup>) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val dishes = menu.dishes.filterNot { it.archived }
    val categories = dishes.map { it.category }.distinct()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CategoryChip(stringResource(R.string.call_order_all_dishes), category == null) { category = null }
            for (name in categories) CategoryChip(name, category == name) { category = name }
        }
        LazyVerticalGrid(
            // Two to a row on a phone, five or six on a tablet.
            columns = GridCells.Adaptive(minSize = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(dishes.filter { category == null || it.category == category }, key = { it.id }) { dish ->
                val groups = remember(dish, menu.groups) { OptionRules.groups(dish, menu.groups) }
                val count = lines.filter { it.dish.id == dish.id }.sumOf { it.qty }
                LaunchedEffect(dish.imageUrl) { onPhoto(dish.imageUrl) }
                DishTile(dish, photos[dish.imageUrl], OptionRules.orderable(dish, groups), count) { onTap(dish, groups) }
            }
        }
    }
}

@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
private fun DishTile(dish: MenuDish, photo: ImageBitmap?, orderable: Boolean, count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = orderable,
        colors =
            if (count > 0) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth().alpha(if (orderable) 1f else 0.5f),
    ) {
        Box {
            Column {
                if (photo != null) {
                    Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                } else {
                    // No photo: the first letter, big, so the tiles still line up.
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Text(dish.name.take(1), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.padding(12.dp)) {
                    Text(dish.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (orderable) Format.euro(dish.price) else stringResource(R.string.new_order_sold_out),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (orderable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (count > 0) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Text("$count×", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/** The dish's choices over the whole screen, as big tiles, with how many: the site's options dialog, for a finger. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsPanel(dish: MenuDish, groups: List<OptionGroup>, onDismiss: () -> Unit, onAdd: (BasketLine) -> Unit) {
    var chosen by remember(dish.id) { mutableStateOf(OptionRules.preset(groups)) }
    var qty by remember(dish.id) { mutableIntStateOf(1) }
    val valid = OptionRules.invalid(groups, chosen).isEmpty()
    val line = BasketLine(dish, qty, OptionRules.chosen(groups, chosen))
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(dish.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                for (group in groups) {
                    Text(
                        "${group.name} · " +
                            stringResource(if (group.selection == Selection.SINGLE) R.string.new_order_single_hint else R.string.new_order_multiple_hint),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (option in group.options) {
                            val on = option.id in chosen[group.id].orEmpty()
                            val label = buildString {
                                if (on) append("✓ ")
                                append(option.name)
                                if (option.price > 0) append(" +").append(Format.euro(option.price))
                            }
                            Surface(
                                onClick = { chosen = OptionRules.toggle(group, chosen, option) },
                                enabled = option.available,
                                shape = MaterialTheme.shapes.medium,
                                color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                modifier = Modifier.alpha(if (option.available) 1f else 0.5f),
                            ) {
                                Column(Modifier.widthIn(min = 120.dp).padding(horizontal = 18.dp, vertical = 14.dp)) {
                                    Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                    if (!option.available) Text(stringResource(R.string.new_order_sold_out), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.new_order_qty), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    BigStepper(qty) { qty = it.coerceIn(1, PhoneOrders.MAX_QTY) }
                }
            }
            HorizontalDivider()
            BottomBar {
                BigButton(stringResource(R.string.cancel_back), onClick = onDismiss, outlined = true)
                BigButton(
                    stringResource(R.string.call_order_add) + " · " + Format.euro(line.unitPrice * qty),
                    onClick = { onAdd(line) },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BigStepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(value - 1) }, modifier = Modifier.size(64.dp), contentPadding = PaddingValues(0.dp)) {
            Text("−", style = MaterialTheme.typography.headlineMedium)
        }
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
        OutlinedButton(onClick = { onChange(value + 1) }, modifier = Modifier.size(64.dp), contentPadding = PaddingValues(0.dp)) {
            Text("+", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// 4. The check: who, where, what, how much
// ---------------------------------------------------------------------------

@Composable
private fun ConfirmStep(state: NewOrderState, model: NewOrderViewModel, link: AddressRequest?) {
    val draft = state.draft
    val waitsForLink = state.awaitingLink
    val quote = state.quote
    val problems = PhoneOrders.problems(draft, Instant.now())
    StepPage(
        4,
        stringResource(R.string.call_order_confirm_title),
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
                BigButton(
                    when {
                        state.saving -> stringResource(R.string.new_order_saving)
                        quote != null && state.priced -> stringResource(R.string.call_order_save, Format.euro(quote.total))
                        else -> stringResource(R.string.call_order_save, "—")
                    },
                    onClick = model::save,
                    enabled = state.priced && !state.saving && !state.quoting && problems.isEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("👤 " + draft.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("📞 " + draft.phone, style = MaterialTheme.typography.titleLarge)
                when {
                    !draft.delivery -> Text(stringResource(R.string.call_order_pickup_chosen), style = MaterialTheme.typography.titleLarge)
                    waitsForLink -> SmsStatus(link, onAgain = model::sendLink)
                    else -> {
                        Text("🏠 ${draft.street}, ${draft.postalCode} ${draft.city}", style = MaterialTheme.typography.titleLarge)
                        if (draft.addressNote.isNotBlank()) Text(draft.addressNote, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (draft.lines.isEmpty()) StatusLine(stringResource(R.string.call_order_empty))
        draft.lines.forEachIndexed { index, line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line.dish.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (line.options.isNotEmpty()) Text(line.options.joinToString(", ") { it.name }, style = MaterialTheme.typography.titleMedium)
                    Text(Format.euro(line.unitPrice * line.qty), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BigStepper(line.qty) { model.setQty(index, it) }
            }
            HorizontalDivider()
        }

        if (waitsForLink) {
            BigPriceLine(stringResource(R.string.new_order_subtotal), draft.lines.sumOf { it.unitPrice * it.qty }, big = true)
            Text(stringResource(R.string.call_order_fee_later), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            when {
                problems.isNotEmpty() -> Unit
                state.quoting || state.checking ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.new_order_pricing), style = MaterialTheme.typography.titleLarge)
                    }
                quote != null && state.priced -> BigBreakdown(quote)
            }
            if (draft.lines.isNotEmpty()) {
                // What to tell the caller.
                StatusLine(stringResource(R.string.call_order_ready_in, model.suggestedMinutes(state)))
            }
        }
        state.error?.let { StatusLine(it, bad = true) }
    }
}

@Composable
private fun BigBreakdown(quote: PhoneOrderQuote) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BigPriceLine(stringResource(R.string.new_order_subtotal), quote.subtotal)
        if (quote.dealDiscount > 0) BigPriceLine(stringResource(R.string.new_order_deal), -quote.dealDiscount)
        if (quote.pickupDiscount > 0) BigPriceLine(stringResource(R.string.new_order_pickup_discount), -quote.pickupDiscount)
        if (quote.deliveryFee > 0) BigPriceLine(stringResource(R.string.new_order_delivery_fee), quote.deliveryFee)
        if (quote.smallOrderFee > 0) BigPriceLine(stringResource(R.string.new_order_small_fee), quote.smallOrderFee)
        HorizontalDivider()
        BigPriceLine(stringResource(R.string.new_order_total), quote.total, big = true)
        if (quote.overCapacity) StatusLine(stringResource(R.string.new_order_over_capacity))
    }
}

@Composable
private fun BigPriceLine(label: String, amount: Double, big: Boolean = false) {
    val style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, fontWeight = if (big) FontWeight.Bold else null, modifier = Modifier.weight(1f))
        Text(Format.euro(amount), style = style, fontWeight = if (big) FontWeight.Bold else null)
    }
}

// ---------------------------------------------------------------------------
// Done: in the kitchen, or waiting for the address
// ---------------------------------------------------------------------------

@Composable
private fun DoneStep(state: NewOrderState, link: AddressRequest?, actions: LinkActions, onDone: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        val saved = state.saved
        val problem = link?.problem
        when {
            saved != null -> DoneLine(stringResource(R.string.call_order_saved, saved.orderNumber ?: 0L))
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
                BigButton(stringResource(R.string.call_order_retry), onClick = { actions.retry(link.id) }, outlined = true)
            }
            else -> {
                CircularProgressIndicator(Modifier.size(64.dp))
                Text(stringResource(R.string.call_order_waiting_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.call_order_waiting_text), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                val arrived = link?.address
                if (arrived != null) StatusLine(stringResource(R.string.call_order_address_arrived, arrived.line), good = true)
                else SmsStatus(link) { link?.let { actions.resend(it.id) } }
            }
        }
        BigButton(stringResource(R.string.call_order_done), onClick = onDone, modifier = Modifier.widthIn(min = 240.dp))
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
