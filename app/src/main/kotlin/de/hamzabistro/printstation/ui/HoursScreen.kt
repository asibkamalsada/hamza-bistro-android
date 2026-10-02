package de.hamzabistro.printstation.ui

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.AutoDecline
import de.hamzabistro.printstation.core.DeliveryDay
import de.hamzabistro.printstation.core.ShopClosure
import de.hamzabistro.printstation.core.ShopHours
import de.hamzabistro.printstation.core.ShopSettings
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
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
    val planFrom: Instant = Instant.EPOCH,
    val planUntil: Instant = Instant.EPOCH,
    /** Until somebody opens again. */
    val planOpenEnded: Boolean = false,
    val planBusy: Boolean = false,
    val planMessage: Message? = null,
    /** The shop's settings as last read; null until read, and on a database without them. */
    val settings: ShopSettings? = null,
    val autoDeclineBusy: Boolean = false,
    val autoDeclineMessage: Message? = null,
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

    fun setPlanFrom(at: Instant) = _state.update { it.copy(planFrom = at, planMessage = null) }

    fun setPlanUntil(at: Instant) = _state.update { it.copy(planUntil = at, planMessage = null) }

    fun setPlanOpenEnded(on: Boolean) = _state.update { it.copy(planOpenEnded = on, planMessage = null) }

    fun addClosure() {
        val plan = _state.value
        if (plan.planBusy) return
        val until = if (plan.planOpenEnded) null else plan.planUntil
        if (until != null && !until.isAfter(plan.planFrom)) {
            _state.update { it.copy(planMessage = Message(app.getString(R.string.hours_plan_invalid), error = true)) }
            return
        }
        _state.update { it.copy(planBusy = true, planMessage = null) }
        viewModelScope.launch {
            val closed =
                attempt(app, { error -> _state.update { it.copy(planMessage = Message(error, error = true)) } }) {
                    graph.shop.close(until, plan.planFrom)
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
            attempt(app, { error -> _state.update { it.copy(planMessage = Message(error, error = true)) } }) {
                show(graph.shop.removeClosure(closure.id))
            }
        }
    }

    private fun show(hours: ShopHours) {
        graph.shopView.value = ShopView(hours)
    }

    /** The form starts on tomorrow, all day — the commonest thing to plan. */
    private fun resetPlan() {
        val tomorrow = Instant.now().atZone(AlarmPolicy.LEIPZIG).toLocalDate().plusDays(1)
        _state.update {
            it.copy(
                planFrom = tomorrow.atStartOfDay(AlarmPolicy.LEIPZIG).toInstant(),
                planUntil = tomorrow.plusDays(1).atStartOfDay(AlarmPolicy.LEIPZIG).toInstant(),
                planOpenEnded = false,
            )
        }
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
                }
            }
            AutoDeclineSetting(state, hours)
            Closures(shop, state, staffState.now, hours)
            Week(shop, state, hours)
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

/** "Aus — Bestellungen warten", "nach 10 Minuten (empfohlen)". */
private fun autoDeclineLabel(context: Context, minutes: Int?): String {
    if (minutes == null) return context.getString(R.string.auto_decline_off)
    val label = context.getString(R.string.auto_decline_after, minutes)
    return if (minutes == AutoDecline.SUGGESTED) context.getString(R.string.auto_decline_suggested, label) else label
}

/** Closures running now and planned: a holiday, a day off. One ended early by "Open again" drops off. */
@Composable
private fun Closures(shop: ShopHours?, state: HoursState, now: Instant, hours: HoursViewModel) {
    val context = LocalContext.current
    Section(stringResource(R.string.hours_planned)) {
        Text(stringResource(R.string.hours_planned_note), style = MaterialTheme.typography.bodySmall)
        val closures = shop?.closures.orEmpty()
        if (closures.isEmpty()) Text(stringResource(R.string.hours_planned_none))
        for (closure in closures) {
            val running = !closure.startsAt.isAfter(now)
            val range = closureRange(context, closure)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (running) "$range · ${stringResource(R.string.hours_plan_running)}" else range,
                        fontWeight = if (running) FontWeight.SemiBold else null,
                    )
                    closure.by?.let {
                        Text(stringResource(R.string.shop_closed_by, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { hours.removeClosure(closure) }) { Text(stringResource(R.string.remove)) }
            }
        }

        Text(stringResource(R.string.hours_plan_from), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { pickDateTime(context, state.planFrom, hours::setPlanFrom) }) { Text(dateTime(context, state.planFrom)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hours_plan_open_ended), modifier = Modifier.weight(1f))
            Switch(checked = state.planOpenEnded, onCheckedChange = hours::setPlanOpenEnded)
        }
        if (!state.planOpenEnded) {
            Text(stringResource(R.string.hours_plan_until), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { pickDateTime(context, state.planUntil, hours::setPlanUntil) }) { Text(dateTime(context, state.planUntil)) }
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

@Composable
private fun Said(message: Message) {
    Text(message.text, color = if (message.error) MaterialTheme.colorScheme.error else Color.Unspecified)
}

/** "Thursday", in the device's language; 0 is Sunday, as the database counts. */
private fun dayName(context: Context, day: Int): String =
    DayOfWeek.of(if (day == 0) 7 else day).getDisplayName(TextStyle.FULL, locale(context)).replaceFirstChar { it.titlecase(locale(context)) }

/** "11:00"; the end of the day is "24:00", which is what it is. */
private fun minutesText(minutes: Int): String = if (minutes >= DeliveryDay.MIDNIGHT) "24:00" else Format.time(LocalTime.of(minutes / 60, minutes % 60))

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

/** A day, then a time, on a Leipzig clock whatever the device is set to. */
private fun pickDateTime(context: Context, at: Instant, onPicked: (Instant) -> Unit) {
    val local = at.atZone(AlarmPolicy.LEIPZIG)
    DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                        context,
                        { _, hour, minute -> onPicked(LocalDateTime.of(year, month + 1, day, hour, minute).atZone(AlarmPolicy.LEIPZIG).toInstant()) },
                        local.hour,
                        local.minute,
                        true,
                    )
                    .show()
            },
            local.year,
            local.monthValue - 1,
            local.dayOfMonth,
        )
        .show()
}

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
