package de.hamzabistro.printstation.ui

import android.app.Application
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.CashUp
import de.hamzabistro.printstation.core.CashUpDriver
import de.hamzabistro.printstation.core.CashUpText
import de.hamzabistro.printstation.core.CashUpWords
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Kassensturz shows. */
data class CashUpState(
    val loading: Boolean = false,
    /** The day shown; null until the first answer says which day today is. */
    val day: LocalDate? = null,
    val cashUp: CashUp? = null,
    /** The shop's database does not have cash_up() yet. */
    val missing: Boolean = false,
    val error: String? = null,
)

/**
 * The Kassensturz: for one Leipzig day, what each driver should hand over in
 * cash and what went through the card terminal, with the order numbers
 * behind each sum. Read when asked for, like the history.
 */
class CashUpViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow(CashUpState())
    val state: StateFlow<CashUpState> = _state.asStateFlow()

    private var reading: Job? = null

    /** Today in Leipzig, by this device's clock: the day picker goes no further. */
    fun today(): LocalDate = CashUp.today(Instant.now())

    /** The day shown again; the first time, today as the database counts it. */
    fun load() = read(_state.value.day)

    fun pick(day: LocalDate) = read(minOf(day, today()))

    fun previous() = _state.value.day?.let { pick(it.minusDays(1)) }

    fun next() = _state.value.day?.let { pick(it.plusDays(1)) }

    private fun read(day: LocalDate?) {
        // The day tapped last wins over one still on its way.
        reading?.cancel()
        _state.update { it.copy(loading = true, error = null, day = day ?: it.day) }
        reading =
            viewModelScope.launch {
                var failed = false
                val read =
                    attempt(app, { error ->
                        failed = true
                        _state.update { it.copy(loading = false, cashUp = null, missing = false, error = error) }
                    }) {
                        graph.history.cashUp(day)
                    }
                if (failed) return@launch
                _state.update {
                    it.copy(loading = false, cashUp = read, missing = read == null, day = read?.date ?: day ?: it.day)
                }
            }
    }
}

/** "Fr., 02.10.2026", in the device's language. */
private fun dayText(context: Context, day: LocalDate): String =
    DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy", context.resources.configuration.locales[0]).format(day)

/** The Kassensturz's words, from the resources, for [day]. */
private fun cashUpWords(context: Context, day: LocalDate): CashUpWords =
    CashUpWords(
        title = context.getString(R.string.cash_up_share_title, dayText(context, day)),
        delivered = context.getString(R.string.cash_up_delivered),
        cash = context.getString(R.string.pay_cash),
        card = context.getString(R.string.pay_card),
        online = context.getString(R.string.pay_online),
        unknown = context.getString(R.string.pay_unknown),
        nobody = context.getString(R.string.cash_up_nobody),
        pickup = context.getString(R.string.cash_up_pickup),
        empty = context.getString(R.string.cash_up_empty),
    )

/** The Android share sheet with the Kassensturz as plain text: to WhatsApp, to email, to the owner. */
private fun share(context: Context, cashUp: CashUp) {
    val words = cashUpWords(context, cashUp.date)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, words.title)
            .putExtra(Intent.EXTRA_TEXT, CashUpText.share(cashUp, words))
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.cash_up_share)))
    } catch (e: ActivityNotFoundException) {
        // Nothing on the device takes text: nothing to share to.
    }
}

/** A day on a calendar, up to today. */
private fun pickDay(context: Context, day: LocalDate, today: LocalDate, onPicked: (LocalDate) -> Unit) {
    val dialog =
        DatePickerDialog(context, { _, year, month, dayOfMonth -> onPicked(LocalDate.of(year, month + 1, dayOfMonth)) }, day.year, day.monthValue - 1, day.dayOfMonth)
    dialog.datePicker.maxDate = today.atTime(23, 59).atZone(AlarmPolicy.LEIPZIG).toInstant().toEpochMilli()
    dialog.show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashUpScreen(onBack: () -> Unit) {
    val model: CashUpViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cash_up_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = {
                    val shown = state.cashUp
                    TextButton(onClick = { shown?.let { share(context, it) } }, enabled = shown != null && !state.loading) {
                        Text(stringResource(R.string.cash_up_share))
                    }
                    TextButton(onClick = model::load, enabled = !state.loading) { Text(stringResource(R.string.reload)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.day?.let { day -> item(key = "day") { DayPicker(day, model) } }
            state.error?.let { error ->
                item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp)) }
            }
            if (state.missing) {
                item(key = "missing") {
                    Text(stringResource(R.string.cash_up_needs_update), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
            }
            val cashUp = state.cashUp
            when {
                state.loading ->
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                cashUp == null -> Unit
                cashUp.totals.count == 0 ->
                    item(key = "empty") {
                        Text(stringResource(R.string.cash_up_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                    }
                else -> {
                    val words = cashUpWords(context, cashUp.date)
                    item(key = "totals") {
                        Column(modifier = Modifier.padding(4.dp)) {
                            Text(CashUpText.totals(cashUp.totals, words), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(CashUpText.split(cashUp.totals, words), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    itemsIndexed(cashUp.drivers, key = { index, _ -> "${cashUp.day}-$index" }) { index, driver ->
                        DriverCard(driver, words, key = "${cashUp.day}-$index")
                    }
                }
            }
        }
    }
}

/** "‹ Fr., 02.10.2026 ›", the date opening a calendar, and back to today. */
@Composable
private fun DayPicker(day: LocalDate, model: CashUpViewModel) {
    val context = LocalContext.current
    val today = model.today()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val previous = stringResource(R.string.cash_up_previous)
        val next = stringResource(R.string.cash_up_next)
        OutlinedButton(onClick = { model.previous() }, modifier = Modifier.semantics { contentDescription = previous }) { Text("‹") }
        TextButton(onClick = { pickDay(context, day, today, model::pick) }, modifier = Modifier.weight(1f)) {
            Text(dayText(context, day), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = { model.next() }, enabled = day < today, modifier = Modifier.semantics { contentDescription = next }) {
            Text("›")
        }
        if (day != today) TextButton(onClick = { model.pick(today) }) { Text(stringResource(R.string.cash_up_today)) }
    }
}

/** One driver's sums; the orders behind them a tap away, so a difference in the drawer can be traced. */
@Composable
private fun DriverCard(driver: CashUpDriver, words: CashUpWords, key: String) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${CashUpText.driver(driver, words)} · ${CashUpText.totals(driver, words)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(CashUpText.split(driver, words), style = MaterialTheme.typography.bodyLarge)
            Text(
                "${stringResource(R.string.cash_up_orders)} ${if (open) "▴" else "▾"}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (open) {
                for (order in driver.orders) {
                    Text(CashUpText.order(order, words), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
