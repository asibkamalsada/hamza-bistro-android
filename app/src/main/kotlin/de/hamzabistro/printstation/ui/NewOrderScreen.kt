package de.hamzabistro.printstation.ui

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AddressCheck
import de.hamzabistro.printstation.core.AddressRequest
import de.hamzabistro.printstation.core.AddressRequests
import de.hamzabistro.printstation.core.AddressRequestsState
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.BasketLine
import de.hamzabistro.printstation.core.Baskets
import de.hamzabistro.printstation.core.DraftProblem
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.Fulfilment
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
import de.hamzabistro.printstation.core.SmsState
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
    val step: TakingStep = TakingStep.CUSTOMER,
    /** Set up as a phone delivery, once: what is chosen after stays chosen. */
    val started: Boolean = false,
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
        get() = step == TakingStep.DONE || saved != null || waiting

    /** What it comes to: the database's price, else the menu's. */
    val total: Double
        get() = if (priced) quote?.total ?: draft.subtotal else draft.subtotal

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

/** The steps of "Neue Bestellung": one leads to the other and back. */
enum class TakingStep {
    /** Who it is for, where it goes, and when. */
    CUSTOMER,

    /** What, for how much; saved from here. */
    DISHES,

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
    override fun pickMinutes(minutes: Int) {
        _state.update { it.copy(minutes = minutes) }
        reprice(atOnce = true)
    }

    /** The minutes pre-selected for an order for now: the incoming order's rule, the ring from the price or the address check. */
    override fun suggestedMinutes(state: NewOrderState): Int {
        val zone = state.quote?.deliveryZone ?: state.address?.zone
        val busy = graph.shopView.value.hours?.busyMinutes(Instant.now()) ?: 0
        return PhoneOrders.suggestedEta(state.draft, zone, graph.queue.state.value.prep, busy)
    }

    override fun ladder(): List<Int> = graph.settings.device.value.etaLadder

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
                _state.update { it.copy(saving = false, saved = saved, step = TakingStep.DONE) }
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
    // The steps, and the address by SMS
    // -------------------------------------------------------------------

    /** Opened from a call or the queue: a phone delivery, unless it was begun as something else. */
    fun start() {
        val now = _state.value
        if (now.started) return
        _state.update { it.copy(started = true) }
        if (now.draft.lines.isNotEmpty() || now.link != null || now.saved != null || now.waiting) return
        if (now.draft.source != OrderSource.PHONE || now.draft.fulfilment != Fulfilment.DELIVERY) {
            edit { it.copy(source = OrderSource.PHONE, fulfilment = Fulfilment.DELIVERY) }
        }
    }

    override fun goTo(step: TakingStep) = _state.update { it.copy(step = step) }

    /** Priced again where there is no price: what is refused still says so again, rather than leaving a button that does nothing. */
    override fun dismissError() {
        _state.update { it.copy(error = null) }
        if (!_state.value.priced) reprice(atOnce = true)
    }

    override fun another() = reset()

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
                _state.update { it.copy(waiting = true, step = TakingStep.DONE) }
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

/**
 * "Neue Bestellung": an order called in, or somebody at the counter, in two
 * steps that can be taken in any order. Who it is for and where it goes — the
 * address typed with Leipzig's streets offered, or texted for by SMS for the
 * caller to type themselves — and the dishes, tapped as in the Kasse
 * ([DishesPane]), with the price and "Speichern" under the basket. What is
 * still missing is said there, and is a tap away.
 *
 * With the address coming by SMS, the order is saved to wait for it, and
 * the tablet puts it into the kitchen by itself the moment the caller has
 * typed it — during the call or after it ([de.hamzabistro.printstation.core.AddressRequestPlacer]).
 */
@Composable
fun NewOrderScreen(
    onBack: () -> Unit,
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
    LaunchedEffect(state.started) { model.start() }
    LaunchedEffect(Unit) { model.loadMenu() }
    // Where to, once the order is discarded.
    var discarding by remember { mutableStateOf<(() -> Unit)?>(null) }
    val finish: (() -> Unit) -> Unit = { go ->
        model.reset()
        go()
    }
    val leave: (() -> Unit) -> Unit = { go ->
        if (state.step == TakingStep.DONE || (state.draft.lines.isEmpty() && state.link == null)) finish(go) else discarding = go
    }
    leaving.Ask(leave)
    // Back is a step back; from the first, out.
    BackHandler { if (state.step == TakingStep.DISHES) model.goTo(TakingStep.CUSTOMER) else leave(onBack) }
    val actions = remember(graph) { LinkActions(graph) }
    NewOrderContent(state, photos, link, model, actions::retry, actions::resend, onDone = { finish(onBack) }, navigation)

    discarding?.let { go ->
        AskFirst(stringResource(R.string.new_order_discard), null, stringResource(R.string.new_order_discard_yes), onDismiss = { discarding = null }) {
            discarding = null
            finish(go)
        }
    }
}

/** "Neue Bestellung" as it is drawn: what it shows, and what its taps do. */
@Composable
internal fun NewOrderContent(
    state: NewOrderState,
    photos: Map<String, ImageBitmap?>,
    link: AddressRequest?,
    model: OrderTaking,
    onRetry: (String) -> Unit,
    onResend: (String) -> Unit,
    onDone: () -> Unit,
    navigation: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            SlimHeader(
                stringResource(R.string.new_order_title),
                navigation,
                note =
                    when (state.step) {
                        TakingStep.CUSTOMER -> stringResource(R.string.new_order_step_customer)
                        TakingStep.DISHES -> stringResource(R.string.new_order_step_dishes)
                        TakingStep.DONE -> null
                    },
                busy = state.saving || state.savingWaiting,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.step) {
                TakingStep.CUSTOMER -> CustomerStep(state, model, link)
                TakingStep.DISHES -> DishesStep(state, photos, model, link)
                TakingStep.DONE -> DoneStep(state, link, onRetry, onResend, onAnother = model::another, onDone = onDone)
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
// Who it is for, and where it goes
// ---------------------------------------------------------------------------

@Composable
private fun CustomerStep(state: NewOrderState, model: OrderTaking, link: AddressRequest?) {
    val draft = state.draft
    val focus = remember { FocusRequester() }
    // The keyboard up at once: the first thing to do is type.
    LaunchedEffect(Unit) { if (draft.name.isBlank()) runCatching { focus.requestFocus() } }
    StepPage(
        bottom = {
            val toDishes = { model.goTo(TakingStep.DISHES) }
            val words = stringResource(R.string.new_order_to_dishes)
            if (draft.lines.isEmpty()) BigButton(words, onClick = toDishes, modifier = Modifier.weight(1f).testTag(BASKET_GO_TAG))
            else GoButton(words, Format.euro(state.total), enabled = true, onClick = toDishes, modifier = Modifier.weight(1f))
        },
    ) { wide ->
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                StepColumn(Modifier.weight(1f).fillMaxHeight()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SourceChoice(state, model, Modifier.weight(2f))
                        FulfilmentChoice(state, model, Modifier.weight(3f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ContactFields(state, model, Modifier.weight(1f).focusRequester(focus), Modifier.weight(1f))
                    }
                    if (draft.delivery) AddressFields(state, model, help = false)
                }
                VerticalDivider()
                StepColumn(Modifier.weight(1f).fillMaxHeight()) {
                    if (draft.delivery) {
                        AddressHelp(state, model)
                        TextedAddress(state, model, link)
                        HorizontalDivider()
                    }
                    MoreFields(state, model)
                }
            }
        } else {
            StepColumn(Modifier.fillMaxSize()) {
                SourceChoice(state, model, Modifier.fillMaxWidth())
                FulfilmentChoice(state, model, Modifier.fillMaxWidth())
                ContactFields(state, model, Modifier.fillMaxWidth().focusRequester(focus), Modifier.fillMaxWidth())
                if (draft.delivery) {
                    AddressFields(state, model, help = true)
                    TextedAddress(state, model, link)
                }
                HorizontalDivider()
                MoreFields(state, model)
            }
        }
    }
}

/** The way out for an address that does not survive the phone. */
@Composable
private fun TextedAddress(state: NewOrderState, model: OrderTaking, link: AddressRequest?) {
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
        else if (link?.sms != SmsState.FAILED) StatusLine(stringResource(R.string.call_order_address_pending))
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

/** When, and what the kitchen should know: right as it is, mostly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreFields(state: NewOrderState, model: OrderTaking) {
    val context = LocalContext.current
    val draft = state.draft
    val pick = { pickDateTime(context, draft.scheduledFor) { at -> model.edit { it.copy(scheduledFor = at) } } }
    Choice(
        listOf(stringResource(R.string.new_order_asap), draft.scheduledFor?.let { Format.slot(context, it) } ?: stringResource(R.string.new_order_later)),
        selected = if (draft.scheduledFor == null) 0 else 1,
        Modifier.fillMaxWidth(),
    ) { index ->
        if (index == 0) model.edit { it.copy(scheduledFor = null) } else pick()
    }
    // Waiting for its address, it is promised its minutes when it goes in.
    if (draft.scheduledFor == null && !state.awaitingLink) {
        // The minutes, as on an incoming order: the suggestion filled in, the round numbers beside it.
        val suggested = model.suggestedMinutes(state)
        val picked = state.minutes ?: suggested
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (minutes in Eta.options(suggested, model.ladder())) {
                FilterChip(
                    selected = minutes == picked,
                    onClick = { model.pickMinutes(minutes) },
                    label = { Text(stringResource(R.string.eta_button, minutes), fontSize = 17.sp) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
    FormField(draft.notes, { v -> model.edit { it.copy(notes = v) } }, stringResource(R.string.new_order_kitchen_note), singleLine = false)
    if (draft.delivery) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = draft.waiveFees, role = Role.Checkbox) { on -> model.edit { it.copy(waiveFees = on) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = draft.waiveFees, onCheckedChange = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.new_order_waive), style = LINE_NAME)
                Text(stringResource(R.string.new_order_waive_hint), style = LINE_SMALL, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The dishes, the price, saved
// ---------------------------------------------------------------------------

@Composable
private fun DishesStep(state: NewOrderState, photos: Map<String, ImageBitmap?>, model: OrderTaking, link: AddressRequest?) {
    val draft = state.draft
    DishesPane(
        Shop(state.menu, state.menuError, state.bestsellers, photos, model::loadPhoto, model::loadMenu),
        Basket(
            draft.lines,
            state.total,
            stringResource(R.string.call_order_to_basket),
            onAdd = model::add,
            onQty = model::setQty,
            onNote = model::setNote,
        ),
        over = { modifier ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Who(state, link, Modifier.weight(1f)) { model.goTo(TakingStep.CUSTOMER) }
                EmptyBasketButton(draft.lines.isNotEmpty() && !state.saving && !state.savingWaiting, model::emptyBasket)
            }
        },
        go = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PriceAndTime(state, model)
                Save(state, model)
            }
        },
        problem = state.error?.let { error -> @Composable { modifier: Modifier -> ProblemCard(error, model::dismissError, modifier) } },
    )
}

/** Whose order it is and where it goes; a tap is the step before. */
@Composable
private fun Who(state: NewOrderState, link: AddressRequest?, modifier: Modifier, onClick: () -> Unit) {
    val draft = state.draft
    val where =
        when {
            !draft.delivery -> stringResource(fulfilmentLabel(draft.fulfilment))
            state.awaitingLink -> smsWords(link)
            draft.street.isBlank() -> stringResource(R.string.new_order_no_address)
            else -> draft.street.trim() + ", " + draft.postalCode.trim()
        }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val name = listOf(draft.name.trim(), draft.phone.trim()).firstOrNull { it.isNotEmpty() } ?: stringResource(R.string.new_order_no_name)
                Text(name, style = LINE_NAME, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(where, style = LINE_SMALL, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.new_order_to_customer), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun smsWords(link: AddressRequest?): String =
    when (link?.sms) {
        SmsState.SENT -> stringResource(R.string.new_order_address_by_sms)
        SmsState.FAILED -> stringResource(R.string.call_order_sms_failed, link.smsError.orEmpty())
        else -> stringResource(R.string.call_order_sms_sending)
    }

/** What the price is made of, and when it is ready: what to tell the caller. */
@Composable
private fun PriceAndTime(state: NewOrderState, model: OrderTaking) {
    val draft = state.draft
    if (draft.lines.isEmpty()) return
    val quote = state.quote?.takeIf { state.priced }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val words =
        when {
            state.awaitingLink -> stringResource(R.string.call_order_fee_later)
            state.quoting || state.checking -> stringResource(R.string.new_order_pricing)
            quote != null -> {
                val amounts =
                    listOf(
                        R.string.new_order_deal to -quote.dealDiscount,
                        R.string.new_order_pickup_discount to -quote.pickupDiscount,
                        R.string.new_order_delivery_fee to quote.deliveryFee,
                        R.string.new_order_small_fee to quote.smallOrderFee,
                        R.string.new_order_waived to -quote.waived,
                    )
                amounts.filter { (_, amount) -> amount != 0.0 }.map { (label, amount) -> stringResource(label) + " " + Format.euro(amount) }.joinToString(" · ")
            }
            else -> ""
        }
    if (words.isNotEmpty()) Text(words, style = LINE_SMALL, color = muted)
    val at = draft.scheduledFor
    if (at != null) Text(Format.slot(LocalContext.current, at), style = LINE_NAME)
    else if (!state.awaitingLink) Text(stringResource(R.string.call_order_ready_in, state.minutes ?: model.suggestedMinutes(state)), style = LINE_NAME)
    if (quote?.overCapacity == true) Text(stringResource(R.string.new_order_over_capacity), style = LINE_SMALL, color = toneColor(Tone.SOON))
    if (draft.delivery && !state.awaitingLink && state.addressError != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.new_order_address_failed, state.addressError),
                style = LINE_SMALL,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = model::checkAgain, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.new_order_check_again)) }
        }
    }
}

/** Saves it; while something is missing, says what, and leads there. */
@Composable
private fun Save(state: NewOrderState, model: OrderTaking) {
    val resources = LocalResources.current
    val draft = state.draft
    val problems = PhoneOrders.problems(draft, Instant.now())
    val missing = problems.filterNot { it == DraftProblem.EMPTY || (state.awaitingLink && it in BY_SMS) }
    val toCustomer = { model.goTo(TakingStep.CUSTOMER) }
    val wide = Modifier.fillMaxWidth()
    when {
        draft.lines.isEmpty() -> GoButton(stringResource(R.string.call_order_save), Format.euro(0.0), enabled = false, onClick = {}, modifier = wide)
        missing.isNotEmpty() ->
            GoButton(
                stringResource(R.string.new_order_missing, missing.joinToString(", ") { resources.getString(problemLabel(it)) }) + " ➜",
                "",
                enabled = true,
                onClick = toCustomer,
                modifier = wide,
            )
        draft.delivery && state.addressChecked && state.address?.outside == true ->
            GoButton(stringResource(R.string.call_order_outside), "", enabled = true, onClick = toCustomer, modifier = wide)
        // The link takes no time along: an order for later is saved once its address is there.
        state.awaitingLink && draft.scheduledFor != null ->
            GoButton(stringResource(R.string.new_order_time_needs_address), "", enabled = true, onClick = toCustomer, modifier = wide)
        state.awaitingLink ->
            GoButton(
                stringResource(if (state.savingWaiting) R.string.new_order_saving else R.string.call_order_save_waiting),
                "",
                enabled = !state.savingWaiting,
                onClick = model::saveWaiting,
                modifier = wide,
            )
        else ->
            GoButton(
                stringResource(if (state.saving) R.string.new_order_saving else R.string.call_order_save),
                if (state.priced) Format.euro(state.total) else "…",
                enabled = state.priced && !state.saving && !state.quoting,
                onClick = model::save,
                modifier = wide,
            )
    }
}

/** What comes with the address. */
private val BY_SMS = setOf(DraftProblem.STREET, DraftProblem.POSTAL_CODE, DraftProblem.CITY)

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

// ---------------------------------------------------------------------------
// Done: in the kitchen, or waiting for the address
// ---------------------------------------------------------------------------

@Composable
private fun DoneStep(state: NewOrderState, link: AddressRequest?, onRetry: (String) -> Unit, onResend: (String) -> Unit, onAnother: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    StepPage(
        bottom = {
            BigButton(stringResource(R.string.new_order_another), onClick = onAnother, outlined = true, modifier = Modifier.weight(1f))
            BigButton(stringResource(R.string.call_order_done), onClick = onDone, modifier = Modifier.weight(1f))
        },
    ) {
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
