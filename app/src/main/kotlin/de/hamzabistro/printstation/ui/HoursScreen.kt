package de.hamzabistro.printstation.ui

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.AutoDecline
import de.hamzabistro.printstation.core.DayHours
import de.hamzabistro.printstation.core.DeliveryBreak
import de.hamzabistro.printstation.core.DeliveryDay
import de.hamzabistro.printstation.core.Kitchen
import de.hamzabistro.printstation.core.PauseWhat
import de.hamzabistro.printstation.core.ShopClosure
import de.hamzabistro.printstation.core.ShopHours
import de.hamzabistro.printstation.core.ShopSettings
import de.hamzabistro.printstation.core.SpecialCalendar
import de.hamzabistro.printstation.core.SpecialDay
import de.hamzabistro.printstation.core.SpecialForm
import de.hamzabistro.printstation.core.Upcoming
import de.hamzabistro.printstation.core.upcoming
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The hours screen's own state: the week as edited, and a closure being planned. */
data class HoursState(
    /** The week as it is being edited, Monday first; null until the database has answered. */
    val draft: List<DeliveryDay>? = null,
    /** Changed since it was last filled from the database: reads no longer overwrite it. */
    val editing: Boolean = false,
    val weekBusy: Boolean = false,
    val weekMessage: Message? = null,
    /** A pause planned ahead: one day, from–until in minutes; 1440 is midnight at its end. */
    val planDay: LocalDate = LocalDate.of(1970, 1, 1),
    val planFrom: Int = 14 * 60,
    val planUntil: Int = 17 * 60,
    val planBusy: Boolean = false,
    val planMessage: Message? = null,
    /** The shop's settings as last read; null until read, and on a database without them. */
    val settings: ShopSettings? = null,
    val autoDeclineBusy: Boolean = false,
    val autoDeclineMessage: Message? = null,
    /** The kitchen's cap (hamza-bistro-web#73), in [settings]. */
    val dishesBusy: Boolean = false,
    val dishesMessage: Message? = null,
    /** Weekly delivery breaks changed here and not saved yet, by id. */
    val breakEdits: Map<Long, DeliveryBreak> = emptyMap(),
    /** The form for adding one. */
    val newBreak: DeliveryBreak = DeliveryBreak.NEW,
    val breakBusy: Boolean = false,
    val breakMessage: Message? = null,
    /** "Besondere Tage": the month shown, null for today's. */
    val month: YearMonth? = null,
    /** The dates picked in the calendar, sorted. */
    val picked: List<LocalDate> = emptyList(),
    val specialForm: SpecialForm = SpecialForm(),
    val specialBusy: Boolean = false,
    val specialMessage: Message? = null,
)

/**
 * When the shop takes orders — the site's /orders/hours: closures planned
 * ahead, and the week. Both are the database's and apply to every device
 * and the website at once. Whatever a call answers goes into the shared
 * shop line, so the queue shows it at once too.
 */
class HoursViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow(HoursState())
    val state: StateFlow<HoursState> = _state.asStateFlow()

    init {
        resetPlan()
    }

    /** Read again on arriving: who closed the shop is only told to staff, and it may be minutes old. */
    fun load() {
        viewModelScope.launch {
            attempt(app, { error -> _state.update { it.copy(weekMessage = Message(error, error = true)) } }) {
                graph.shop.hours()?.let(::show)
            }
        }
        viewModelScope.launch {
            attempt(app, { error -> _state.update { it.copy(autoDeclineMessage = Message(error, error = true)) } }) {
                val settings = graph.shop.settings()
                _state.update { it.copy(settings = settings) }
            }
        }
    }

    /** Auto-decline after [minutes], or off; saved at once, for every device and the website. */
    fun setAutoDecline(minutes: Int?) {
        if (_state.value.autoDeclineBusy) return
        _state.update { it.copy(autoDeclineBusy = true, autoDeclineMessage = null) }
        viewModelScope.launch {
            val saved =
                attempt(app, { error -> _state.update { it.copy(autoDeclineMessage = Message(error, error = true)) } }) {
                    graph.shop.setAutoDecline(minutes)
                }
            if (saved != null) {
                _state.update { it.copy(settings = saved, autoDeclineMessage = Message(app.getString(R.string.hours_saved), error = false)) }
            }
            _state.update { it.copy(autoDeclineBusy = false) }
        }
    }

    /** At most [dishes] dishes per quarter hour for customers, 0 being off; saved at once. */
    fun setDishesPerSlot(dishes: Int) {
        if (_state.value.dishesBusy) return
        _state.update { it.copy(dishesBusy = true, dishesMessage = null) }
        viewModelScope.launch {
            val saved =
                attempt(app, { error -> _state.update { it.copy(dishesMessage = Message(error, error = true)) } }) {
                    graph.shop.setDishesPerSlot(dishes)
                }
            if (saved != null) {
                _state.update { it.copy(settings = saved, dishesMessage = Message(app.getString(R.string.hours_saved), error = false)) }
            }
            _state.update { it.copy(dishesBusy = false) }
        }
    }

    /** The week follows each read — but never under somebody still editing it. */
    fun follow(hours: ShopHours?) {
        val week = hours?.let { ordered(it.delivery) } ?: return
        _state.update { if (it.editing) it else it.copy(draft = week) }
    }

    fun setDay(day: Int, change: (DeliveryDay) -> DeliveryDay) {
        _state.update { state ->
            state.copy(
                draft = state.draft?.map { if (it.day == day) change(it) else it },
                editing = true,
                weekMessage = null,
            )
        }
    }

    fun saveWeek() {
        val draft = _state.value.draft ?: return
        if (draft.any { !it.valid } || _state.value.weekBusy) return
        _state.update { it.copy(weekBusy = true, weekMessage = null) }
        viewModelScope.launch {
            val saved =
                attempt(app, { error -> _state.update { it.copy(weekMessage = Message(error, error = true)) } }) {
                    graph.shop.saveWeek(draft.sortedBy { it.day })
                }
            if (saved != null) {
                show(saved)
                _state.update {
                    it.copy(editing = false, draft = ordered(saved.delivery) ?: it.draft, weekMessage = Message(app.getString(R.string.hours_saved), error = false))
                }
            }
            _state.update { it.copy(weekBusy = false) }
        }
    }

    /** Back to the week as saved. */
    fun resetWeek() {
        val saved = graph.shopView.value.hours?.let { ordered(it.delivery) }
        _state.update { it.copy(editing = false, draft = saved ?: it.draft, weekMessage = null) }
    }

    fun setPlanDay(day: LocalDate) = _state.update { it.copy(planDay = day, planMessage = null) }

    fun setPlanFrom(minutes: Int) = _state.update { it.copy(planFrom = minutes, planMessage = null) }

    fun setPlanUntil(minutes: Int) = _state.update { it.copy(planUntil = minutes, planMessage = null) }

    /** A pause of a few hours on one day, as "Pausen" on the site: whole days are special days. */
    fun addClosure() {
        val plan = _state.value
        if (plan.planBusy) return
        if (plan.planUntil <= plan.planFrom) {
            _state.update { it.copy(planMessage = Message(app.getString(R.string.hours_plan_invalid), error = true)) }
            return
        }
        val start = plan.planDay.atStartOfDay()
        val from = start.plusMinutes(plan.planFrom.toLong()).atZone(AlarmPolicy.LEIPZIG).toInstant()
        val until = start.plusMinutes(plan.planUntil.toLong()).atZone(AlarmPolicy.LEIPZIG).toInstant()
        _state.update { it.copy(planBusy = true, planMessage = null) }
        viewModelScope.launch {
            val closed =
                attempt(app, { error -> _state.update { it.copy(planMessage = Message(error, error = true)) } }) {
                    graph.shop.close(until, from)
                }
            if (closed != null) {
                show(closed)
                resetPlan()
                // Booked for a time inside it: they stand, and somebody has to answer them.
                val inside = closed.preordersInside
                if (inside > 0) {
                    val text = app.resources.getQuantityString(R.plurals.shop_preorders_inside, inside, inside)
                    _state.update { it.copy(planMessage = Message(text, error = false)) }
                }
            }
            _state.update { it.copy(planBusy = false) }
        }
    }

    fun removeClosure(closure: ShopClosure) {
        viewModelScope.launch {
            attempt(app, { error -> _state.update { it.copy(specialMessage = Message(error, error = true)) } }) {
                show(graph.shop.removeClosure(closure.id))
            }
        }
    }

    /** The calendar a month back or on, between today's month and the last one a day can be set in. */
    fun showMonth(by: Long) {
        val today = SpecialCalendar.today(Instant.now())
        _state.update { it.copy(month = SpecialCalendar.shift(it.month ?: SpecialCalendar.firstMonth(today), by, today)) }
    }

    /** Tap a date: in or out of the selection. The first pick fills the form from it. */
    fun toggleDate(date: LocalDate) {
        if (!SpecialCalendar.selectable(date, SpecialCalendar.today(Instant.now()))) return
        _state.update { state ->
            val form = if (state.picked.isEmpty()) SpecialForm.from(date, graph.shopView.value.hours) else state.specialForm
            state.copy(picked = SpecialCalendar.toggle(state.picked, date), specialForm = form, specialMessage = null)
        }
    }

    fun unselect() = _state.update { it.copy(picked = emptyList(), specialMessage = null) }

    /** From the list: that one date picked, the calendar on its month. */
    fun editSpecial(day: SpecialDay) =
        _state.update {
            it.copy(
                month = YearMonth.from(day.day),
                picked = listOf(day.day),
                specialForm = SpecialForm.from(day.day, graph.shopView.value.hours),
                specialMessage = null,
            )
        }

    fun setSpecialForm(change: (SpecialForm) -> SpecialForm) = _state.update { it.copy(specialForm = change(it.specialForm), specialMessage = null) }

    /** The form's hours on every picked date, replacing what they had. */
    fun saveSpecial() {
        val state = _state.value
        if (state.picked.isEmpty() || state.specialBusy) return
        val hours = state.specialForm.hours
        if (hours == null) {
            _state.update { it.copy(specialMessage = Message(app.getString(R.string.special_invalid_hours), error = true)) }
            return
        }
        specialCall({ graph.shop.setSpecialDays(state.picked, hours, state.specialForm.label) }) { saved ->
            // Booked for a time the new hours do not take: they stand, and somebody has to answer them.
            val inside = saved.preordersInside
            if (inside > 0) app.resources.getQuantityString(R.plurals.special_preorders_inside, inside, inside) else app.getString(R.string.hours_saved)
        }
    }

    /** "Zurück auf normal" for every picked date. */
    fun resetSpecial() {
        val picked = _state.value.picked
        if (picked.isEmpty() || _state.value.specialBusy) return
        specialCall({ graph.shop.clearSpecialDays(picked) }) { app.getString(R.string.hours_saved) }
    }

    fun removeSpecial(day: SpecialDay) {
        if (_state.value.specialBusy) return
        specialCall({ graph.shop.clearSpecialDays(listOf(day.day)) }, keepPicked = { it != day.day }) { app.getString(R.string.hours_saved) }
    }

    private fun specialCall(
        call: suspend () -> ShopHours,
        keepPicked: (LocalDate) -> Boolean = { false },
        done: (ShopHours) -> String,
    ) {
        _state.update { it.copy(specialBusy = true, specialMessage = null) }
        viewModelScope.launch {
            val saved = attempt(app, { error -> _state.update { it.copy(specialMessage = Message(error, error = true)) } }) { call() }
            if (saved != null) {
                show(saved)
                _state.update { it.copy(picked = it.picked.filter(keepPicked), specialMessage = Message(done(saved), error = false)) }
            }
            _state.update { it.copy(specialBusy = false) }
        }
    }

    /** A weekly delivery break as being edited: [id] null is the form for a new one. */
    fun editBreak(id: Long?, change: (DeliveryBreak) -> DeliveryBreak) {
        _state.update { state ->
            if (id == null) {
                state.copy(newBreak = change(state.newBreak), breakMessage = null)
            } else {
                val saved = graph.shopView.value.hours?.deliveryBreaks?.firstOrNull { it.id == id } ?: return@update state
                state.copy(breakEdits = state.breakEdits + (id to change(state.breakEdits[id] ?: saved)), breakMessage = null)
            }
        }
    }

    /** Saves one break — each row on its own, as on the site; [brk] with no id adds it. */
    fun saveBreak(brk: DeliveryBreak) {
        if (_state.value.breakBusy) return
        if (!brk.valid) {
            _state.update { it.copy(breakMessage = Message(app.getString(R.string.break_invalid), error = true)) }
            return
        }
        val id = brk.id
        breakCall(app.getString(R.string.hours_saved), { graph.shop.saveBreak(brk) }) { state ->
            if (id == null) state.copy(newBreak = DeliveryBreak.NEW) else state.copy(breakEdits = state.breakEdits - id)
        }
    }

    fun deleteBreak(id: Long) {
        if (_state.value.breakBusy) return
        breakCall(app.getString(R.string.break_deleted), { graph.shop.deleteBreak(id) }) { it.copy(breakEdits = it.breakEdits - id) }
    }

    /** How long before a break delivery for right now stops; saved at once. */
    fun setBreakLead(minutes: Int) {
        if (_state.value.breakBusy) return
        breakCall(app.getString(R.string.hours_saved), { graph.shop.setBreakLead(minutes) }) { it }
    }

    private fun breakCall(done: String, call: suspend () -> ShopHours, after: (HoursState) -> HoursState) {
        _state.update { it.copy(breakBusy = true, breakMessage = null) }
        viewModelScope.launch {
            val saved = attempt(app, { error -> _state.update { it.copy(breakMessage = Message(error, error = true)) } }) { call() }
            if (saved != null) {
                show(saved)
                _state.update { after(it).copy(breakMessage = Message(done, error = false)) }
            }
            _state.update { it.copy(breakBusy = false) }
        }
    }

    private fun show(hours: ShopHours) {
        graph.shopView.value = ShopView(hours)
    }

    /** The form starts on tomorrow afternoon, as on the site. */
    private fun resetPlan() {
        val tomorrow = SpecialCalendar.today(Instant.now()).plusDays(1)
        _state.update { it.copy(planDay = tomorrow, planFrom = 14 * 60, planUntil = 17 * 60) }
    }

    companion object {
        /** The week Monday first, or null when the database did not send all seven days. */
        fun ordered(week: List<DeliveryDay>): List<DeliveryDay>? =
            DeliveryDay.WEEK_ORDER.map { day -> week.firstOrNull { it.day == day } ?: return null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoursScreen(staff: StaffViewModel, onBack: () -> Unit) {
    val hours: HoursViewModel = viewModel()
    val state by hours.state.collectAsStateWithLifecycle()
    val staffState by staff.state.collectAsStateWithLifecycle()
    val shop = staffState.shop.hours
    val snackbar = remember { SnackbarHostState() }
    StaffEvents(staff, snackbar)
    LaunchedEffect(Unit) { hours.load() }
    LaunchedEffect(shop) { hours.follow(shop) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hours_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(stringResource(R.string.hours_now)) {
                if (shop == null) {
                    Text(stringResource(R.string.hours_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ShopSwitch(staffState.shop, staffState.now, remember(staff) { ShopButtons(staff) })
                    shop.todayAt(staffState.now)?.let { Text(todayLine(LocalContext.current, it)) }
                }
            }
            AutoDeclineSetting(state, hours)
            KitchenCapSetting(state, hours)
            SpecialDays(shop, state, staffState.now, hours)
            Pauses(state, hours)
            Week(shop, state, hours)
            if (shop != null) Breaks(shop, state, hours)
        }
    }
}

/**
 * How long an order may wait unanswered before the database declines it —
 * the picker of the site's /orders/hours. Left out on a database without
 * the setting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoDeclineSetting(state: HoursState, hours: HoursViewModel) {
    val settings = state.settings
    if (settings == null && state.autoDeclineMessage == null) return
    val context = LocalContext.current
    Section(stringResource(R.string.auto_decline_heading)) {
        Text(stringResource(R.string.auto_decline_note), style = MaterialTheme.typography.bodySmall)
        if (settings != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (minutes in AutoDecline.choices(settings.autoDeclineMinutes)) {
                    val label = autoDeclineLabel(context, minutes)
                    val enabled = !state.autoDeclineBusy
                    if (minutes == settings.autoDeclineMinutes) {
                        Button(onClick = {}, enabled = enabled) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { hours.setAutoDecline(minutes) }, enabled = enabled) { Text(label) }
                    }
                }
            }
        }
        state.autoDeclineMessage?.let { Said(it) }
    }
}

/**
 * How many dishes the kitchen sends out per quarter hour — the select of the
 * site's /orders/hours. Left out on a database without the cap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitchenCapSetting(state: HoursState, hours: HoursViewModel) {
    val saved = state.settings?.dishesPerSlot ?: return
    Section(stringResource(R.string.kitchen_cap_heading)) {
        Text(stringResource(R.string.kitchen_cap_note), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (dishes in Kitchen.choices(saved)) {
                val label = if (dishes == 0) stringResource(R.string.kitchen_cap_off) else stringResource(R.string.kitchen_cap_n, dishes)
                val enabled = !state.dishesBusy
                if (dishes == saved) {
                    Button(onClick = {}, enabled = enabled) { Text(label) }
                } else {
                    OutlinedButton(onClick = { hours.setDishesPerSlot(dishes) }, enabled = enabled) { Text(label) }
                }
            }
        }
        state.dishesMessage?.let { Said(it) }
    }
}

/** "Aus — Bestellungen warten", "nach 10 Minuten (empfohlen)". */
private fun autoDeclineLabel(context: Context, minutes: Int?): String {
    if (minutes == null) return context.getString(R.string.auto_decline_off)
    val label = context.getString(R.string.auto_decline_after, minutes)
    return if (minutes == AutoDecline.SUGGESTED) context.getString(R.string.auto_decline_suggested, label) else label
}

/**
 * "Besondere Tage", as on the site's /orders/hours (hamza-bistro-web#119):
 * tap dates in a month calendar, choose Geschlossen or Geöffnet von … bis …,
 * save. Dates with their own hours are marked; below, the coming special
 * days and every pause running or planned, each to edit or remove.
 */
@Composable
private fun SpecialDays(shop: ShopHours?, state: HoursState, now: Instant, hours: HoursViewModel) {
    val context = LocalContext.current
    Section(stringResource(R.string.special_heading)) {
        if (shop == null) {
            Text(stringResource(R.string.hours_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        val specials = shop.specialDays
        if (specials == null) {
            Text(stringResource(R.string.special_needs_update, SPECIAL_DAYS_MIGRATION), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.special_note), style = MaterialTheme.typography.bodySmall)
            val today = SpecialCalendar.today(now)
            Calendar(shop, state, today, hours)
            if (state.picked.isEmpty()) {
                Text(stringResource(R.string.special_none_selected), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SpecialFormFields(shop, state, hours)
            }
        }
        state.specialMessage?.let { Said(it) }

        Text(stringResource(R.string.special_list_heading), style = MaterialTheme.typography.titleSmall)
        val upcoming = shop.upcoming(now)
        if (upcoming.isEmpty()) Text(stringResource(R.string.special_none))
        for (item in upcoming) {
            when (item) {
                is Upcoming.Special -> SpecialRow(context, item.day, state.specialBusy, hours)
                is Upcoming.Pause -> PauseRow(context, item.closure, now, hours)
            }
        }
    }
}

/** The month, Monday first, with arrows; past days and those more than 366 days ahead cannot be picked. */
@Composable
private fun Calendar(shop: ShopHours, state: HoursState, today: LocalDate, hours: HoursViewModel) {
    val context = LocalContext.current
    val month = state.month ?: SpecialCalendar.firstMonth(today)
    val prev = stringResource(R.string.special_prev_month)
    val next = stringResource(R.string.special_next_month)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { hours.showMonth(-1) }, enabled = month.isAfter(SpecialCalendar.firstMonth(today))) {
            Text("‹", modifier = Modifier.semantics { contentDescription = prev })
        }
        Text(
            DateTimeFormatter.ofPattern("LLLL yyyy", locale(context)).format(month),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { hours.showMonth(1) }, enabled = month.isBefore(SpecialCalendar.lastMonth(today))) {
            Text("›", modifier = Modifier.semantics { contentDescription = next })
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        for (day in DayOfWeek.entries) {
            Text(
                day.getDisplayName(TextStyle.SHORT, locale(context)).removeSuffix("."),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
    for (week in SpecialCalendar.grid(month)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (date in week) {
                Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (date != null) DayCell(context, date, shop.specialOn(date), date in state.picked, date == today, SpecialCalendar.selectable(date, today), hours)
                }
            }
        }
    }
}

/** One date: picked filled in, today ringed; a green dot for other hours, a red bar for closed. */
@Composable
private fun DayCell(context: Context, date: LocalDate, special: SpecialDay?, picked: Boolean, today: Boolean, selectable: Boolean, hours: HoursViewModel) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val description = special?.let { "${dateLabel(context, date)} · ${specialHoursText(context, it)}" } ?: dateLabel(context, date)
    Column(
        modifier =
            Modifier.fillMaxSize()
                .clip(shape)
                .background(if (picked) colors.primary else Color.Transparent)
                .then(if (today) Modifier.border(1.dp, colors.primary, shape) else Modifier)
                .clickable(enabled = selectable) { hours.toggleDate(date) }
                .semantics {
                    contentDescription = description
                    selected = picked
                },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            color =
                when {
                    picked -> colors.onPrimary
                    !selectable -> colors.onSurface.copy(alpha = 0.38f)
                    else -> colors.onSurface
                },
            fontWeight = if (special != null) FontWeight.Bold else null,
        )
        when {
            special == null -> Spacer(Modifier.height(4.dp))
            special.hours == null -> Box(Modifier.padding(top = 1.dp).width(14.dp).height(3.dp).background(colors.error, RoundedCornerShape(2.dp)))
            else -> Box(Modifier.padding(top = 1.dp).size(5.dp).background(OTHER_HOURS, CircleShape))
        }
    }
}

/** The picked dates' hours: Geschlossen or Geöffnet von … bis …, a label; Speichern, and Zurück auf normal where it has something to do. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpecialFormFields(shop: ShopHours, state: HoursState, hours: HoursViewModel) {
    val context = LocalContext.current
    val form = state.specialForm
    val picked = state.picked
    val count = pluralStringResource(R.plurals.special_selected, picked.size, picked.size)
    val now =
        picked.singleOrNull()?.let { date ->
            shop.specialOn(date)?.let { stringResource(R.string.special_now_special, specialHoursText(context, it)) }
                ?: shop.hoursOn(date)?.let { day ->
                    val opens = day.opens
                    val closes = day.closes
                    val normal = if (day.delivers && opens != null && closes != null) "${minutesText(opens)}–${minutesText(closes)}" else stringResource(R.string.hours_day_off)
                    stringResource(R.string.special_now_normal, normal)
                }
        }
    Text(if (now != null) "$count · $now" else count, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectableGroup()) {
        RadioButton(selected = form.closed, onClick = { hours.setSpecialForm { it.copy(closed = true) } })
        Text(stringResource(R.string.special_closed), modifier = Modifier.padding(end = 16.dp))
        RadioButton(selected = !form.closed, onClick = { hours.setSpecialForm { it.copy(closed = false) } })
        Text(stringResource(R.string.special_open))
    }
    if (!form.closed) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.special_from))
            OutlinedButton(onClick = { pickTime(context, form.opens, closing = false) { m -> hours.setSpecialForm { it.copy(opens = m) } } }) {
                Text(minutesText(form.opens))
            }
            Text(stringResource(R.string.special_to))
            OutlinedButton(onClick = { pickTime(context, form.closes, closing = true) { m -> hours.setSpecialForm { it.copy(closes = m) } } }) {
                Text(minutesText(form.closes))
            }
        }
    }
    OutlinedTextField(
        value = form.label,
        onValueChange = { label -> hours.setSpecialForm { it.copy(label = label.take(SpecialCalendar.MAX_LABEL)) } },
        label = { Text(stringResource(R.string.special_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = hours::saveSpecial, enabled = !state.specialBusy) {
            Text(stringResource(if (state.specialBusy) R.string.please_wait else R.string.special_save))
        }
        if (picked.any { shop.specialOn(it) != null }) {
            OutlinedButton(onClick = hours::resetSpecial, enabled = !state.specialBusy) { Text(stringResource(R.string.special_reset)) }
        }
        TextButton(onClick = hours::unselect, enabled = !state.specialBusy) { Text(stringResource(R.string.special_unselect)) }
    }
}

/** "Do., 24.12. Heiligabend · geschlossen", who set it, Bearbeiten and Entfernen. */
@Composable
private fun SpecialRow(context: Context, day: SpecialDay, busy: Boolean, hours: HoursViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            val name = listOf(dateLabel(context, day.day), day.labelText).filter { it.isNotEmpty() }.joinToString(" ")
            Text("$name · ${specialHoursText(context, day)}")
            day.by?.let {
                Text(stringResource(R.string.special_set_by, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { hours.editSpecial(day) }, enabled = !busy) { Text(stringResource(R.string.special_edit)) }
        TextButton(onClick = { hours.removeSpecial(day) }, enabled = !busy) { Text(stringResource(R.string.remove)) }
    }
}

/** A pause running or planned, as the closures list had it, with Entfernen. */
@Composable
private fun PauseRow(context: Context, closure: ShopClosure, now: Instant, hours: HoursViewModel) {
    val running = !closure.startsAt.isAfter(now)
    val range =
        if (closure.stopsAll) closureRange(context, closure)
        else stringResource(R.string.hours_plan_scope, closureRange(context, closure), scopeText(context, closure.scope, closure.rings))
    val line = stringResource(R.string.special_pause, range)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (running) "$line · ${stringResource(R.string.hours_plan_running)}" else line,
                fontWeight = if (running) FontWeight.SemiBold else null,
            )
            closure.by?.let {
                Text(stringResource(R.string.shop_closed_by, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { hours.removeClosure(closure) }) { Text(stringResource(R.string.remove)) }
    }
}

/**
 * "Pausen": a few hours planned on one day, as on the site — an afternoon
 * without a driver. Whole days and other hours are special days; the
 * pauses themselves are listed with them.
 */
@Composable
private fun Pauses(state: HoursState, hours: HoursViewModel) {
    val context = LocalContext.current
    Section(stringResource(R.string.hours_planned)) {
        Text(stringResource(R.string.hours_planned_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.hours_plan_day), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { pickDate(context, state.planDay, hours::setPlanDay) }) { Text(dateLabel(context, state.planDay)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.hours_plan_from))
            OutlinedButton(onClick = { pickTime(context, state.planFrom, closing = false, onPicked = hours::setPlanFrom) }) { Text(minutesText(state.planFrom)) }
            Text(stringResource(R.string.hours_plan_until))
            OutlinedButton(onClick = { pickTime(context, state.planUntil, closing = true, onPicked = hours::setPlanUntil) }) { Text(minutesText(state.planUntil)) }
        }
        Button(onClick = hours::addClosure, enabled = !state.planBusy) {
            Text(stringResource(if (state.planBusy) R.string.please_wait else R.string.hours_plan_add))
        }
        state.planMessage?.let { Said(it) }
    }
}

/** The week, saved all at once: two times changed one by one would pass through a day that closes before it opens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Week(shop: ShopHours?, state: HoursState, hours: HoursViewModel) {
    val context = LocalContext.current
    Section(stringResource(R.string.hours_week)) {
        Text(stringResource(R.string.hours_week_note), style = MaterialTheme.typography.bodySmall)
        val draft = state.draft
        if (draft == null) {
            Text(stringResource(R.string.hours_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        for (day in draft) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Switch(checked = day.delivers, onCheckedChange = { on -> hours.setDay(day.day) { it.copy(delivers = on) } })
                Text(dayName(context, day.day), modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (day.delivers) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pickTime(context, day.opens, closing = false) { m -> hours.setDay(day.day) { it.copy(opens = m) } } }) {
                            Text(minutesText(day.opens))
                        }
                        Text("–")
                        OutlinedButton(onClick = { pickTime(context, day.closes, closing = true) { m -> hours.setDay(day.day) { it.copy(closes = m) } } }) {
                            Text(minutesText(day.closes))
                        }
                    }
                } else {
                    Text(stringResource(R.string.hours_day_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        draft.firstOrNull { !it.valid }?.let {
            Text(stringResource(R.string.hours_week_invalid, dayName(context, it.day)), color = MaterialTheme.colorScheme.error)
        }
        val saved = shop?.let { HoursViewModel.ordered(it.delivery) }
        val changed = saved != null && draft != saved
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = hours::saveWeek, enabled = changed && draft.all { it.valid } && !state.weekBusy) {
                Text(stringResource(if (state.weekBusy) R.string.please_wait else R.string.hours_week_save))
            }
            if (changed) TextButton(onClick = hours::resetWeek) { Text(stringResource(R.string.hours_week_reset)) }
        }
        state.weekMessage?.let { Said(it) }
    }
}

/**
 * The weekly delivery breaks — "Lieferpausen (wöchentlich)" on the site's
 * /orders/hours (hamza-bistro-web#112): no delivery at these times every
 * week, collection open. Each row saves on its own; below them, a new one,
 * and how long before a break delivery for right now stops.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breaks(shop: ShopHours, state: HoursState, hours: HoursViewModel) {
    Section(stringResource(R.string.breaks_heading)) {
        Text(stringResource(R.string.breaks_note), style = MaterialTheme.typography.bodySmall)
        val saved = DeliveryBreak.weekOrdered(shop.deliveryBreaks)
        if (saved.isEmpty()) Text(stringResource(R.string.breaks_none))
        for (brk in saved) {
            val id = brk.id ?: continue
            BreakRow(state.breakEdits[id] ?: brk, state.breakBusy, hours)
            HorizontalDivider()
        }
        Text(stringResource(R.string.break_new), style = MaterialTheme.typography.labelLarge)
        BreakRow(state.newBreak, state.breakBusy, hours)
        HorizontalDivider()

        Text(stringResource(R.string.break_lead_label), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (minutes in DeliveryBreak.leadChoices(shop.breakLeadMinutes)) {
                val label = stringResource(R.string.break_lead_option, minutes)
                if (minutes == shop.breakLeadMinutes) {
                    Button(onClick = {}, enabled = !state.breakBusy) { Text(label) }
                } else {
                    OutlinedButton(onClick = { hours.setBreakLead(minutes) }, enabled = !state.breakBusy) { Text(label) }
                }
            }
        }
        state.breakMessage?.let { Said(it) }
    }
}

/** One break: day, from, to on the quarter hour, the reason, on or off; save, and delete one saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreakRow(brk: DeliveryBreak, busy: Boolean, hours: HoursViewModel) {
    val context = LocalContext.current
    val edit: ((DeliveryBreak) -> DeliveryBreak) -> Unit = { change -> hours.editBreak(brk.id, change) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            var choosingDay by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { choosingDay = true }) { Text(dayName(context, brk.day)) }
                DropdownMenu(expanded = choosingDay, onDismissRequest = { choosingDay = false }) {
                    for (day in DeliveryDay.WEEK_ORDER) {
                        DropdownMenuItem(
                            text = { Text(dayName(context, day)) },
                            onClick = {
                                choosingDay = false
                                edit { it.copy(day = day) }
                            },
                        )
                    }
                }
            }
            OutlinedButton(onClick = { pickTime(context, brk.starts, closing = false) { m -> edit { it.copy(starts = m) } } }) {
                Text(minutesText(brk.starts))
            }
            Text("–")
            OutlinedButton(onClick = { pickTime(context, brk.ends, closing = true) { m -> edit { it.copy(ends = m) } } }) {
                Text(minutesText(brk.ends))
            }
        }
        OutlinedTextField(
            value = brk.label,
            onValueChange = { label -> edit { it.copy(label = label.take(DeliveryBreak.MAX_LABEL)) } },
            label = { Text(stringResource(R.string.break_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = brk.active, onCheckedChange = { on -> edit { it.copy(active = on) } })
            Text(stringResource(R.string.break_active), modifier = Modifier.weight(1f))
            Button(onClick = { hours.saveBreak(brk) }, enabled = !busy) {
                Text(stringResource(if (brk.id == null) R.string.break_add else R.string.break_save))
            }
            val id = brk.id
            if (id != null) TextButton(onClick = { hours.deleteBreak(id) }, enabled = !busy) { Text(stringResource(R.string.break_delete)) }
        }
    }
}

/** "Nur Lieferung", "Weite Ringe", "Nur Rand" — or the rings one by one. */
private fun scopeText(context: Context, scope: String, rings: List<String>?): String =
    PauseWhat.of(scope, rings)?.let { context.getString(whatLabel(it)) } ?: rings.orEmpty().joinToString(", ")

@Composable
private fun Said(message: Message) {
    Text(message.text, color = if (message.error) MaterialTheme.colorScheme.error else Color.Unspecified)
}

/** "Thursday", in the device's language; 0 is Sunday, as the database counts. */
internal fun dayName(context: Context, day: Int): String =
    DayOfWeek.of(if (day == 0) 7 else day).getDisplayName(TextStyle.FULL, locale(context)).replaceFirstChar { it.titlecase(locale(context)) }

/** "11:00"; the end of the day is "24:00", which is what it is. */
internal fun minutesText(minutes: Int): String = if (minutes >= DeliveryDay.MIDNIGHT) "24:00" else Format.time(LocalTime.of(minutes / 60, minutes % 60))

/** "Do. 24.12. 14:00", on a Leipzig clock. */
private fun dateTime(context: Context, at: Instant): String =
    DateTimeFormatter.ofPattern("EE d.M. HH:mm", locale(context)).format(at.atZone(AlarmPolicy.LEIPZIG))

/** "Do. 24.12. 14:00 to Fr. 25.12. 00:00", or "from Do. 24.12. 14:00, no end". */
private fun closureRange(context: Context, closure: ShopClosure): String {
    val from = dateTime(context, closure.startsAt)
    val until = closure.endsAt ?: return context.getString(R.string.hours_plan_from_only, from)
    return context.getString(R.string.hours_plan_range, from, dateTime(context, until))
}

private fun locale(context: Context): Locale = context.resources.configuration.locales[0]

/** A Leipzig date, from today on. */
private fun pickDate(context: Context, day: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, dayOfMonth -> onPicked(LocalDate.of(year, month + 1, dayOfMonth)) }, day.year, day.monthValue - 1, day.dayOfMonth)
        .apply { datePicker.minDate = System.currentTimeMillis() - 1000 }
        .show()
}

/** "Do., 24.12." */
private fun dateLabel(context: Context, date: LocalDate): String = DateTimeFormatter.ofPattern("EE, d.M.", locale(context)).format(date)

/** "geschlossen" / "17:00–21:00". */
private fun specialHoursText(context: Context, day: SpecialDay): String =
    day.hours?.let { "${minutesText(it.opens)}–${minutesText(it.closes)}" } ?: context.getString(R.string.special_closed_short)

/** "Heute 17:00–21:00 Uhr (Silvester)", "Heute geschlossen (Heiligabend)", "Heute keine Lieferung". */
internal fun todayLine(context: Context, day: DayHours): String {
    val label = day.labelText.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
    val opens = day.opens
    val closes = day.closes
    return when {
        day.delivers && opens != null && closes != null -> context.getString(R.string.today_hours, minutesText(opens), minutesText(closes), label)
        day.special -> context.getString(R.string.today_closed, label)
        else -> context.getString(R.string.today_day_off, label)
    }
}

/** The green of a date with other hours, as the site marks it. */
private val OTHER_HOURS = Color(0xFF2E7D32)

/** Named in "braucht das Server-Update". */
private const val SPECIAL_DAYS_MIGRATION = "20261003140000_special_days"

/**
 * A time of day for the week, put on the nearest quarter hour, which is all
 * the checkout offers and all the table takes. As a closing time, 00:00 is
 * midnight at the end of the day.
 */
private fun pickTime(context: Context, minutes: Int, closing: Boolean, onPicked: (Int) -> Unit) {
    val shown = minutes % DeliveryDay.MIDNIGHT
    TimePickerDialog(
            context,
            { _, hour, minute ->
                val quarter = DeliveryDay.QUARTER
                val rounded = (hour * 60 + minute + quarter / 2) / quarter * quarter
                onPicked(if (closing && rounded == 0) DeliveryDay.MIDNIGHT else rounded)
            },
            shown / 60,
            shown % 60,
            true,
        )
        .show()
}
