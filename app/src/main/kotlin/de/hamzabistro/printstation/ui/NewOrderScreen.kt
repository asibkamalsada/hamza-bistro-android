package de.hamzabistro.printstation.ui

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AddressCheck
import de.hamzabistro.printstation.core.AddressRequest
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.Baskets
import de.hamzabistro.printstation.core.DraftProblem
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.Fulfilment
import de.hamzabistro.printstation.core.MenuDish
import de.hamzabistro.printstation.core.OptionGroup
import de.hamzabistro.printstation.core.OptionRules
import de.hamzabistro.printstation.core.OrderMenu
import de.hamzabistro.printstation.core.OrderSource
import de.hamzabistro.printstation.core.PhoneNumbers
import de.hamzabistro.printstation.core.PhoneOrderDraft
import de.hamzabistro.printstation.core.PhoneOrderError
import de.hamzabistro.printstation.core.PhoneOrderException
import de.hamzabistro.printstation.core.PhoneOrderQuote
import de.hamzabistro.printstation.core.PhoneOrders
import de.hamzabistro.printstation.core.RecentAddress
import de.hamzabistro.printstation.core.RequestedLink
import de.hamzabistro.printstation.core.StreetDirectory
import de.hamzabistro.printstation.core.StreetMatcher
import de.hamzabistro.printstation.core.StreetSuggestion
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What "Neue Bestellung" shows. */
data class NewOrderState(
    val menu: OrderMenu? = null,
    val menuError: String? = null,
    /** The dishes to show first, the best seller of the day first: ids, as the Kasse has them. */
    val bestsellers: List<Long> = emptyList(),
    val draft: PhoneOrderDraft = PhoneOrderDraft(),
    /** Leipzig's streets like the one typed; [streetChoices] is what of them is offered. */
    val streets: List<StreetSuggestion> = emptyList(),
    /** Nothing like what is typed is on the list: a street outside Leipzig, or misheard beyond guessing. */
    val streetUnlisted: Boolean = false,
    /** Where the number in the phone field had deliveries before, the latest first; [recentChoices] is what of them is offered. */
    val recentAddresses: List<RecentAddress> = emptyList(),
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
    /** Where "Telefon-Bestellung" is, step by step. */
    val step: CallStep = CallStep.NAME,
    /** Telefon-Bestellung has set this order up as a phone delivery, once: what is chosen after stays chosen. */
    val callStarted: Boolean = false,
    /** The link texted to the caller, when the address goes by SMS. */
    val link: RequestedLink? = null,
    val linking: Boolean = false,
    val linkError: String? = null,
    /** When the caller typed the address now in the fields, through the link. */
    val linkAddressAt: Instant? = null,
    /** The basket is saved on the link and waits there for the address: placed by the tablet once it comes. */
    val waiting: Boolean = false,
    val savingWaiting: Boolean = false,
) {
    /**
     * The address goes by SMS and has not come yet — nor been typed after
     * all: the order is saved to wait for it.
     */
    val awaitingLink: Boolean
        get() = link != null && linkAddressAt == null && draft.delivery && !PhoneOrders.addressComplete(draft)

    /** Saved, or waiting on its link: the next call starts a new order. */
    val finished: Boolean
        get() = step == CallStep.DONE || saved != null || waiting

    /** The price shown is the one for what is on the screen now. */
    val priced: Boolean
        get() = quote != null && quotedDraft == draft

    /**
     * The streets to pick from. None once the field is the first of them
     * and the postcode one of its own: nothing is left to pick then.
     */
    val streetChoices: List<StreetSuggestion>
        get() = if (streets.firstOrNull()?.let { it.fullStreet == draft.street.trim() && draft.postalCode in it.entry.postcodes } == true) emptyList() else streets

    /** The earlier addresses to pick from: not the one that is in the fields already. */
    val recentChoices: List<RecentAddress>
        get() = recentAddresses.filterNot { it.street == draft.street.trim() && it.postalCode == draft.postalCode && it.city == draft.city.trim() }

    /** [address] is the answer for the address on the screen now, not for one typed over since. */
    val addressChecked: Boolean
        get() = checkedAddress == draft.addressKey()
}

/** What check-address is asked once for: street, postcode, town. */
private fun PhoneOrderDraft.addressKey(): List<String> = listOf(street.trim(), postalCode.trim(), city.trim())

/** The steps of "Telefon-Bestellung", in order. */
enum class CallStep {
    NAME,
    ADDRESS,
    DISHES,
    CONFIRM,

    /** Saved, or waiting for the address. */
    DONE,
}

/** A dish photo, decoded at a tile's size rather than the camera's. */
internal fun tile(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= TILE_PX && bounds.outHeight / (sample * 2) >= TILE_PX) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private const val TILE_PX = 320

/**
 * "Neue Bestellung" (#9): a phone or walk-in order typed in on the tablet,
 * priced by the database's own staff_place_order with a dry run, and saved
 * already accepted — straight into "In der Küche", where it prints like any
 * accepted order. A delivery's address goes through the site's own
 * check-address first, as the checkout's does — while the caller is still
 * on the line, and with Leipzig's streets offered as the street is typed.
 */
class NewOrderViewModel(application: Application) : AndroidViewModel(application), OrderTaking {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow(NewOrderState())
    val state: StateFlow<NewOrderState> = _state.asStateFlow()

    private var pricing: Job? = null
    private var suggesting: Job? = null
    private var remembering: Job? = null
    private var addressCheck: Job? = null

    /** The price and the check while typing both want the address checked: one asks, the other takes the answer. */
    private val oneCheck = Mutex()

    init {
        // Reading and indexing Leipzig's streets takes a moment: done by the time a street is typed.
        viewModelScope.launch(Dispatchers.Default) { StreetDirectory.matcher }
    }

    /** On every visit, so what sold out since the last order shows; the menu last read stays up meanwhile. */
    override fun loadMenu() {
        _state.update { it.copy(menuError = null) }
        // Outlives the screen: what is read late is there for the next visit.
        val ranking = graph.scope.async { graph.bestsellers.today() }
        viewModelScope.launch {
            val menu = attempt(app, { error -> _state.update { it.copy(menuError = error) } }) { graph.phoneOrders.menu() } ?: return@launch
            // Waited for, so no tile moves once there are some; not for long: the dishes come first.
            val best = withTimeoutOrNull(BESTSELLERS_WAIT_MS) { ranking.await() } ?: graph.bestsellers.known
            // Never under a finger that is tapping dishes.
            _state.update { it.copy(menu = menu, bestsellers = if (it.menu == null || it.draft.lines.isEmpty()) best else it.bestsellers) }
        }
    }

    /** Any change to the order: the price shown is no longer it, and is worked out again after a pause in the typing. */
    override fun edit(change: (PhoneOrderDraft) -> PhoneOrderDraft) {
        val before = _state.value.draft
        _state.update { it.copy(draft = change(it.draft), error = null) }
        val draft = _state.value.draft
        if (draft.street != before.street) suggestStreets()
        if (draft.phone != before.phone) recallAddresses()
        if (draft.delivery != before.delivery || draft.addressKey() != before.addressKey()) checkWhileTyping()
        reprice()
    }

    /** Leipzig's streets like the one typed, searched off the main thread. */
    private fun suggestStreets() {
        suggesting?.cancel()
        suggesting =
            viewModelScope.launch(Dispatchers.Default) {
                val street = _state.value.draft.street
                val found = StreetDirectory.matcher.match(street)
                val unlisted = found.isEmpty() && street.count(Char::isLetter) >= UNLISTED_FROM
                // Typed on meanwhile: the next search answers.
                _state.update { if (it.draft.street == street) it.copy(streets = found, streetUnlisted = unlisted) else it }
            }
    }

    /**
     * The caller's number, from the banner: into the phone field while it is
     * still empty — never over a number typed — and with it a phone order.
     */
    fun takeNumber(phone: String) {
        if (_state.value.draft.phone.isNotBlank()) return
        edit { it.copy(phone = PhoneNumbers.national(phone), source = OrderSource.PHONE) }
    }

    /**
     * Where the number in the phone field had deliveries in the last 30
     * days, asked once the typing pauses on a whole number. Offered, never
     * filled in: the caller may have moved, or be ordering for somebody else.
     */
    private fun recallAddresses() {
        remembering?.cancel()
        _state.update { it.copy(recentAddresses = emptyList()) }
        val phone = _state.value.draft.phone
        if (!PhoneNumbers.complete(phone)) return
        remembering =
            viewModelScope.launch {
                delay(PRICE_PAUSE_MS)
                val found =
                    try {
                        graph.calls.recentAddresses(phone)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Offline, or not this account's to ask: the address is typed, as it always was.
                        emptyList()
                    }
                _state.update { if (it.draft.phone == phone) it.copy(recentAddresses = found) else it }
            }
    }

    /** An earlier address picked: street, postcode, town and the door note of the last delivery there. */
    override fun pickAddress(address: RecentAddress) =
        edit { it.copy(street = address.street, postalCode = address.postalCode, city = address.city, addressNote = address.addressNote) }

    /** A street picked from the list: its name as the city spells it with the number typed, its postcode, and Leipzig. */
    override fun pickStreet(street: StreetSuggestion) =
        edit { it.copy(street = street.fullStreet, postalCode = street.primaryPostcode ?: it.postalCode, city = PhoneOrders.DEFAULT_CITY) }

    override fun add(line: BasketLine) = edit { it.copy(lines = Baskets.add(it.lines, line)) }

    override fun setQty(index: Int, qty: Int) = edit { it.copy(lines = Baskets.setQty(it.lines, index, qty)) }

    override fun setNote(index: Int, note: String) = edit { it.copy(lines = Baskets.setNote(it.lines, index, note)) }

    override fun emptyBasket() = edit { it.copy(lines = emptyList()) }

    /** Priced again too: whether the kitchen is full depends on the slot the minutes land in. */
    fun pickMinutes(minutes: Int) {
        _state.update { it.copy(minutes = minutes) }
        reprice(atOnce = true)
    }

    /** The minutes pre-selected for an order for now: the incoming order's rule, the ring from the price or the address check. */
    override fun suggestedMinutes(state: NewOrderState): Int {
        val zone = state.quote?.deliveryZone ?: state.address?.zone
        val busy = graph.shopView.value.hours?.busyMinutes(Instant.now()) ?: 0
        return PhoneOrders.suggestedEta(state.draft, zone, graph.queue.state.value.prep, busy)
    }

    fun ladder(): List<Int> = graph.settings.device.value.etaLadder

    /** "Erneut prüfen": asked again at once, basket or none, and priced again with the answer. */
    override fun checkAgain() {
        _state.update { it.copy(checkedAddress = null, address = null) }
        addressCheck?.cancel()
        addressCheck = viewModelScope.launch { if (checkAddress(_state.value.draft)) reprice(atOnce = true) }
    }

    /**
     * check-address while the caller is still on the line: as soon as there
     * is a door to check — a house number, five digits, a town — and the
     * typing pauses, not only once the basket can be priced. So "dorthin
     * liefern wir nicht" is said before the order is taken, not after.
     */
    private fun checkWhileTyping() {
        addressCheck?.cancel()
        val draft = _state.value.draft
        if (!draft.delivery || !PhoneOrders.addressComplete(draft) || !StreetMatcher.hasHouseNumber(draft.street)) return
        addressCheck =
            viewModelScope.launch {
                delay(PRICE_PAUSE_MS)
                checkAddress(draft)
            }
    }

    private fun reprice(atOnce: Boolean = false) {
        pricing?.cancel()
        pricing =
            viewModelScope.launch {
                if (!atOnce) delay(PRICE_PAUSE_MS)
                val draft = _state.value.draft
                if (PhoneOrders.problems(draft, Instant.now()).isNotEmpty()) {
                    _state.update { it.copy(quoting = false) }
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
        val key = draft.addressKey()
        return oneCheck.withLock {
            if (_state.value.checkedAddress == key) return@withLock true
            _state.update { it.copy(checking = true, addressError = null) }
            try {
                val check = graph.phoneOrders.checkAddress(draft.street, draft.postalCode, draft.city)
                _state.update { it.copy(address = check, checkedAddress = key) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(address = null, checkedAddress = null, addressError = failureText(app, e)) }
                false
            } finally {
                // Also when typed over mid-check: whoever asks next says "checking" again.
                _state.update { it.copy(checking = false) }
            }
        }
    }

    /** Saved for real with the price shown, so a price that changed meanwhile is refused (HB409), not charged. */
    override fun save() {
        val now = _state.value
        val quote = now.quote ?: return
        if (!now.priced || now.saving) return
        val draft = now.draft
        val minutes = if (draft.scheduledFor == null) now.minutes ?: suggestedMinutes(now) else null
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = graph.phoneOrders.place(draft, minutes, quote.total, dryRun = false)
                _state.update { it.copy(saving = false, saved = saved, step = CallStep.DONE) }
                // The address came through the link while the caller was on the line: the link is done.
                val link = now.link
                val order = saved.id
                if (link != null && order != null) graph.scope.launch { runCatching { graph.addressRequests.close(link.id, order) } }
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

    /** "Noch eine Bestellung": an empty form, the menu kept. A link asked for and not used is cancelled, so it takes no address. */
    fun reset() {
        pricing?.cancel()
        suggesting?.cancel()
        remembering?.cancel()
        addressCheck?.cancel()
        val now = _state.value
        val unused = now.link?.takeIf { !now.waiting && now.saved == null }
        if (unused != null) graph.scope.launch { runCatching { graph.addressRequests.cancel(unused.id) } }
        _state.update { NewOrderState(menu = it.menu, bestsellers = it.bestsellers) }
    }

    // -------------------------------------------------------------------
    // Telefon-Bestellung: step by step, and the address by SMS
    // -------------------------------------------------------------------

    /** Opened from a call or the queue: a phone delivery, unless it was begun as something else. */
    fun startCall() {
        val now = _state.value
        if (now.callStarted) return
        _state.update { it.copy(callStarted = true) }
        if (now.draft.lines.isNotEmpty() || now.link != null || now.saved != null || now.waiting) return
        if (now.draft.source != OrderSource.PHONE || now.draft.fulfilment != Fulfilment.DELIVERY) {
            edit { it.copy(source = OrderSource.PHONE, fulfilment = Fulfilment.DELIVERY) }
        }
    }

    override fun goTo(step: CallStep) = _state.update { it.copy(step = step) }

    /**
     * "SMS an den Anrufer senden": a link for the number in the form, sent
     * from the phone in the shop; once more when there is one already.
     */
    override fun sendLink() {
        val now = _state.value
        if (now.linking || now.draft.phone.isBlank()) return
        _state.update { it.copy(linking = true, linkError = null) }
        viewModelScope.launch {
            try {
                val link = now.link
                if (link != null) graph.addressRequests.resend(link.id)
                else {
                    val made = graph.addressRequests.request(now.draft.phone, now.draft.name)
                    _state.update { it.copy(link = made) }
                }
                graph.addressWatch.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(linkError = failureText(app, e)) }
            } finally {
                _state.update { it.copy(linking = false) }
            }
        }
    }

    /**
     * The link as the database has it. The caller's address goes into the
     * fields the moment it arrives — checked and priced from there like one
     * typed — unless the basket already waits on the link, where the tablet
     * places it by itself.
     */
    fun linkRead(request: AddressRequest?) {
        val now = _state.value
        if (request == null || request.id != now.link?.id || now.waiting || now.saved != null) return
        val address = request.address ?: return
        if (request.addressAt == now.linkAddressAt) return
        _state.update { it.copy(linkAddressAt = request.addressAt) }
        edit {
            it.copy(
                fulfilment = Fulfilment.DELIVERY,
                street = address.street,
                postalCode = address.postalCode,
                city = address.city.ifBlank { PhoneOrders.DEFAULT_CITY },
                addressNote = address.addressNote,
            )
        }
    }

    /** "Speichern und auf die Adresse warten": the basket goes onto the link, for the tablet to place once the address is there. */
    override fun saveWaiting() {
        val now = _state.value
        val link = now.link ?: return
        if (now.savingWaiting || now.waiting || now.draft.lines.isEmpty()) return
        _state.update { it.copy(savingWaiting = true, error = null) }
        viewModelScope.launch {
            try {
                graph.addressRequests.save(link.id, now.draft)
                _state.update { it.copy(waiting = true, step = CallStep.DONE) }
                graph.addressWatch.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = failureText(app, e)) }
            } finally {
                _state.update { it.copy(savingWaiting = false) }
            }
        }
    }

    private val dishPhotos = DishPhotos(graph, viewModelScope)

    /** The dishes' photos, by URL, for the big tiles: a Döner is found faster by its picture than by its name. */
    val photos: StateFlow<Map<String, ImageBitmap?>> = dishPhotos.photos

    /** Fetches [url]'s photo once, small enough for a tile; null when there is none to show. */
    override fun loadPhoto(url: String) = dishPhotos.load(url)

    private companion object {
        const val PRICE_PAUSE_MS = 600L
        const val BESTSELLERS_WAIT_MS = 1_500L

        /** From this many letters on, a street the list has nothing like is said not to be on it. */
        const val UNLISTED_FROM = 4
    }
}

@Composable
fun NewOrderScreen(
    onBack: () -> Unit,
    navigation: @Composable () -> Unit,
    leaving: Leaving = remember { Leaving() },
    model: NewOrderViewModel = viewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val photos by model.photos.collectAsStateWithLifecycle()
    // Where to, once the order is discarded.
    var discarding by remember { mutableStateOf<(() -> Unit)?>(null) }
    val leave: (() -> Unit) -> Unit = { go ->
        if (state.draft.lines.isEmpty() || state.saved != null) {
            model.reset()
            go()
        } else {
            discarding = go
        }
    }
    BackHandler { leave(onBack) }
    leaving.Ask(leave)
    LaunchedEffect(Unit) { model.loadMenu() }
    NewOrderContent(state, photos, model, model::pickMinutes, model.ladder(), navigation)

    discarding?.let { go ->
        AskFirst(stringResource(R.string.new_order_discard), null, stringResource(R.string.new_order_discard_yes), onDismiss = { discarding = null }) {
            discarding = null
            model.reset()
            go()
        }
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

/**
 * "Neue Bestellung" as it is drawn: the dishes as in the Kasse ([DishMenu]),
 * on a tablet beside the form, on a phone over it.
 */
@Composable
internal fun NewOrderContent(
    state: NewOrderState,
    photos: Map<String, ImageBitmap?>,
    model: OrderTaking,
    onMinutes: (Int) -> Unit,
    ladder: List<Int>,
    navigation: @Composable () -> Unit,
) {
    val shop = orderShop(state, photos, model)
    var picking by remember { mutableStateOf<MenuDish?>(null) }
    val tap: (MenuDish, List<OptionGroup>) -> Unit = { dish, groups ->
        // Nothing to choose: in the basket with one tap.
        if (groups.isEmpty()) model.add(BasketLine(dish, 1)) else picking = dish
    }
    Scaffold(topBar = { SlimHeader(stringResource(R.string.new_order_title), navigation, busy = state.saving) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val form: LazyListScope.(Boolean, () -> Unit) -> Unit = { showMenuButton, onMenu ->
                orderForm(state, model, onMinutes, ladder, showMenuButton, onMenu)
            }
            val room = maxWidth
            if (room >= WIDE_FROM) {
                // The tablet: the menu on the left, the order on the right, as on a till.
                Row(Modifier.fillMaxSize()) {
                    DishesOrWhyNot(shop, state.draft.lines, rail = true, tap, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    LazyColumn(
                        modifier = Modifier.width(if (room >= 1000.dp) 432.dp else room * 0.42f).fillMaxHeight(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        form(false) {}
                    }
                }
            } else {
                var menuOpen by rememberSaveable { mutableStateOf(false) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    form(true) { menuOpen = true }
                }
                if (menuOpen) {
                    BackHandler { menuOpen = false }
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            DishesOrWhyNot(shop, state.draft.lines, rail = room >= 600.dp, tap, Modifier.weight(1f).fillMaxWidth())
                            HorizontalDivider()
                            BottomBar { BigButton(stringResource(R.string.call_order_to_basket), onClick = { menuOpen = false }, modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
            picking?.let { dish ->
                val groups = remember(dish, state.menu) { OptionRules.groups(dish, state.menu?.groups.orEmpty()) }
                DishOptions(dish, groups, notes = true, onDismiss = { picking = null }) {
                    model.add(it)
                    picking = null
                }
            }
        }
    }
}

/** The order: how it came in, the basket, who and where, when, the price, and saving it. */
private fun LazyListScope.orderForm(
    state: NewOrderState,
    model: OrderTaking,
    onMinutes: (Int) -> Unit,
    ladder: List<Int>,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
) {
    val draft = state.draft
    item(key = "how") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sources = OrderSource.entries
            Choice(
                sources.map { stringResource(if (it == OrderSource.PHONE) R.string.source_phone else R.string.source_counter) },
                sources.indexOf(draft.source),
                Modifier.fillMaxWidth(),
            ) { index ->
                model.edit { it.copy(source = sources[index]) }
            }
            FulfilmentChoice(state, model, Fulfilment.entries)
        }
    }

    item(key = "basket") {
        Section(stringResource(R.string.new_order_basket)) {
            if (draft.lines.isEmpty()) Text(stringResource(R.string.till_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            draft.lines.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider()
                BasketRow(line, { model.setQty(index, it) }, { model.setNote(index, it) }, Modifier)
            }
            if (showMenuButton) BigButton("+ " + stringResource(R.string.new_order_add_dish), onClick = onMenu, modifier = Modifier.fillMaxWidth(), tonal = true)
        }
    }

    item(key = "customer") {
        Section(stringResource(R.string.new_order_customer)) {
            ContactFields(state, model)
            if (!draft.fulfilment.needsContact) {
                Text(stringResource(R.string.new_order_contact_optional), style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (draft.delivery) {
        item(key = "address") { Section(stringResource(R.string.new_order_address)) { AddressFields(state, model, help = true) } }
    }

    item(key = "time") { TimeSection(state, model, onMinutes, ladder) }

    item(key = "notes") {
        Section {
            FormField(draft.notes, { v -> model.edit { it.copy(notes = v) } }, stringResource(R.string.new_order_kitchen_note), singleLine = false)
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        title?.let { Text(it, style = LINE_NAME) }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeSection(state: NewOrderState, model: OrderTaking, onMinutes: (Int) -> Unit, ladder: List<Int>) {
    val context = LocalContext.current
    val draft = state.draft
    Section(stringResource(R.string.new_order_time)) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = draft.scheduledFor == null, role = Role.RadioButton) { model.edit { it.copy(scheduledFor = null) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = draft.scheduledFor == null, onClick = null)
                Text(stringResource(R.string.new_order_asap), modifier = Modifier.padding(start = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = draft.scheduledFor != null, role = Role.RadioButton) {
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
                for (minutes in Eta.options(suggested, ladder)) {
                    FilterChip(
                        selected = minutes == picked,
                        onClick = { onMinutes(minutes) },
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
private fun PriceSection(state: NewOrderState, model: OrderTaking) {
    val resources = LocalResources.current
    val problems = PhoneOrders.problems(state.draft, Instant.now())
    val quote = state.quote
    Section(stringResource(R.string.new_order_price)) {
        if (problems.isNotEmpty()) {
            Text(
                stringResource(R.string.new_order_missing, problems.joinToString(", ") { resources.getString(problemLabel(it)) }),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PriceLines(state)
        }
        state.error?.let { StatusLine(it, bad = true) }
        GoButton(
            stringResource(if (state.saving) R.string.new_order_saving else R.string.call_order_save),
            if (quote != null && state.priced) Format.euro(quote.total) else "—",
            enabled = state.priced && !state.saving && !state.quoting && problems.isEmpty(),
            onClick = model::save,
            modifier = Modifier.fillMaxWidth(),
        )
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
