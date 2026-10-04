package de.hamzabistro.printstation.ui

import android.app.Application
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.res.Resources
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.BusyGrid
import de.hamzabistro.printstation.core.CashUp
import de.hamzabistro.printstation.core.DishOrder
import de.hamzabistro.printstation.core.OrderRatings
import de.hamzabistro.printstation.core.OrderSource
import de.hamzabistro.printstation.core.Report
import de.hamzabistro.printstation.core.ReportCsv
import de.hamzabistro.printstation.core.ReportDuration
import de.hamzabistro.printstation.core.ReportLateness
import de.hamzabistro.printstation.core.ReportPreset
import de.hamzabistro.printstation.core.ReportRange
import de.hamzabistro.printstation.core.ReportRatings
import de.hamzabistro.printstation.core.ReportText
import de.hamzabistro.printstation.core.ReportWords
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Auswertung shows. */
data class ReportState(
    val loading: Boolean = false,
    /** The range shown, both days included. */
    val range: ReportRange? = null,
    val report: Report? = null,
    /** The shop's database does not have staff_report() yet. */
    val missing: Boolean = false,
    val error: String? = null,
)

/**
 * The Auswertung: sales, best sellers, busy hours, delivery times and the
 * late rate over a range of Leipzig days, from staff_report(). Read when
 * asked for, like the Kassensturz.
 */
class ReportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow(ReportState())
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private var reading: Job? = null

    /** Today in Leipzig, by this device's clock: no range goes further. */
    fun today(): LocalDate = CashUp.today(Instant.now())

    /** The range shown again; the first time, this week. */
    fun load() = read(_state.value.range ?: ReportPreset.THIS_WEEK.range(today())!!)

    fun pick(preset: ReportPreset) {
        preset.range(today())?.let(::read)
    }

    /** A range from the calendar, in order and at most 366 days. */
    fun pick(from: LocalDate, to: LocalDate) = read(ReportRange.clamp(from, to, today()))

    private fun read(range: ReportRange) {
        // The range tapped last wins over one still on its way.
        reading?.cancel()
        _state.update { it.copy(loading = true, error = null, range = range) }
        reading =
            viewModelScope.launch {
                var failed = false
                val read =
                    attempt(app, { error ->
                        failed = true
                        _state.update { it.copy(loading = false, report = null, missing = false, error = error) }
                    }) {
                        graph.history.report(range)
                    }
                if (failed) return@launch
                _state.update { it.copy(loading = false, report = read, missing = read == null) }
            }
    }
}

private fun dateText(day: LocalDate): String = DATE.format(day)

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** "Mo., 29.03.", in the device's language. */
private fun dayText(context: Context, day: LocalDate): String =
    DateTimeFormatter.ofPattern("EEE, dd.MM.", context.resources.configuration.locales[0]).format(day)

/** "29.03.2025 – 31.03.2025". */
private fun rangeText(range: ReportRange): String =
    if (range.from == range.to) dateText(range.from) else "${dateText(range.from)} – ${dateText(range.to)}"

/** The Auswertung's words, from the resources, for [range]. */
private fun reportWords(context: Context, range: ReportRange): ReportWords =
    ReportWords(
        title = context.getString(R.string.report_share_title, rangeText(range)),
        revenue = context.getString(R.string.report_revenue),
        orders = context.getString(R.string.report_orders),
        basket = context.getString(R.string.report_basket),
        late = context.getString(R.string.report_late),
        day = context.getString(R.string.report_day),
        delivered = context.getString(R.string.report_delivered),
        cancelled = context.getString(R.string.report_cancelled),
        delivery = context.getString(R.string.report_delivery),
        pickup = context.getString(R.string.report_pickup),
        ring = context.getString(R.string.report_ring),
        ringInner = context.getString(R.string.report_ring_inner),
        ringNear = context.getString(R.string.report_ring_near),
        ringFar = context.getString(R.string.report_ring_far),
        ringEdge = context.getString(R.string.report_ring_edge),
        deliveryFees = context.getString(R.string.report_delivery_fees),
        smallOrderFees = context.getString(R.string.report_small_order_fees),
        dish = context.getString(R.string.report_dish),
        qty = context.getString(R.string.report_qty),
        bestSellers = context.getString(R.string.report_best_sellers),
        cash = context.getString(R.string.pay_cash),
        card = context.getString(R.string.pay_card),
        online = context.getString(R.string.pay_online),
        unknown = context.getString(R.string.pay_unknown),
        byCustomer = context.getString(R.string.report_by_customer),
        byStaff = context.getString(R.string.report_by_staff),
        bySystem = context.getString(R.string.report_by_system),
        reasonBusy = context.getString(R.string.reason_busy),
        reasonSoldOut = context.getString(R.string.reason_sold_out),
        reasonUnreachable = context.getString(R.string.reason_unreachable),
        reasonAddress = context.getString(R.string.reason_address),
        reasonTimeout = context.getString(R.string.report_reason_timeout),
        reasonNoShow = context.getString(R.string.reason_no_show),
        reasonNone = context.getString(R.string.reason_none),
        minutes = context.getString(R.string.report_minutes),
        empty = context.getString(R.string.report_empty),
    )

/** The Android share sheet with the summary as plain text. */
private fun share(context: Context, report: Report) {
    val words = reportWords(context, report.range)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, words.title)
            .putExtra(Intent.EXTRA_TEXT, ReportText.share(report, words))
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.report_share)))
    } catch (e: ActivityNotFoundException) {
        // Nothing on the device takes text: nothing to share to.
    }
}

/**
 * The days, best sellers and rings as three CSV files through the share
 * sheet: to email, to Drive, to the Steuerberater. Written to the app's
 * cache, the previous export's files replaced, and handed out read-only.
 */
private suspend fun exportCsv(context: Context, report: Report) {
    val words = reportWords(context, report.range)
    val uris =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, REPORT_DIR)
            dir.deleteRecursively()
            dir.mkdirs()
            ReportCsv.files(report, words).map { csv ->
                val file = File(dir, csv.name)
                file.writeText(csv.text, Charsets.UTF_8)
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
        }
    val send =
        Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_SUBJECT, words.title)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser's preview reads the files too.
    send.clipData = ClipData.newRawUri(words.title, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.report_csv)))
    } catch (e: ActivityNotFoundException) {
        // Nothing on the device takes files: nothing to share to.
    }
}

/** Where exportCsv() writes, under the cache: res/xml/file_paths.xml hands out this folder only. */
private const val REPORT_DIR = "reports"

/** A day on a calendar, between [min] and [max]. */
private fun pickDay(context: Context, day: LocalDate, min: LocalDate?, max: LocalDate, onPicked: (LocalDate) -> Unit) {
    val dialog =
        DatePickerDialog(context, { _, year, month, dayOfMonth -> onPicked(LocalDate.of(year, month + 1, dayOfMonth)) }, day.year, day.monthValue - 1, day.dayOfMonth)
    fun millis(d: LocalDate) = d.atStartOfDay(AlarmPolicy.LEIPZIG).toInstant().toEpochMilli()
    dialog.datePicker.maxDate = millis(max) + DAY_MILLIS - 1
    if (min != null) dialog.datePicker.minDate = millis(min)
    dialog.show()
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * "Zeitraum…": the first day, then the last, from the first up to 366
 * days later or today, whichever comes first.
 */
private fun pickRange(context: Context, shown: ReportRange?, today: LocalDate, onPicked: (LocalDate, LocalDate) -> Unit) {
    pickDay(context, shown?.from ?: today, null, today) { from ->
        val last = minOf(today, from.plusDays(ReportRange.MAX_DAYS - 1))
        pickDay(context, minOf(maxOf(shown?.to ?: today, from), last), from, last) { to -> onPicked(from, to) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val model: ReportViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { model.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = {
                    val shown = state.report?.takeIf { !state.loading }
                    TextButton(onClick = { shown?.let { scope.launch { exportCsv(context, it) } } }, enabled = shown != null) {
                        Text(stringResource(R.string.report_csv))
                    }
                    TextButton(onClick = { shown?.let { share(context, it) } }, enabled = shown != null) {
                        Text(stringResource(R.string.report_share))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "range") { RangePicker(state, model) }
            state.error?.let { error ->
                item(key = "error") {
                    Column {
                        Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp))
                        TextButton(onClick = model::load) { Text(stringResource(R.string.reload)) }
                    }
                }
            }
            if (state.missing) {
                item(key = "missing") {
                    Text(stringResource(R.string.report_needs_update), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
            }
            val report = state.report
            when {
                state.loading ->
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                report != null -> reportItems(report, reportWords(context, report.range))
            }
        }
    }
}

/** The quick ranges, "Zeitraum…" for any other, and the range shown. */
@Composable
private fun RangePicker(state: ReportState, model: ReportViewModel) {
    val context = LocalContext.current
    val today = model.today()
    val selected = state.range?.let { ReportPreset.of(it, today) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((preset, label) in PRESETS) {
                FilterChip(selected = selected == preset, onClick = { model.pick(preset) }, label = { Text(stringResource(label)) })
            }
            FilterChip(
                selected = selected == ReportPreset.CUSTOM,
                onClick = { pickRange(context, state.range, today, model::pick) },
                label = { Text(stringResource(R.string.report_custom)) },
            )
        }
        state.range?.let { range ->
            Text(
                "${rangeText(range)} · ${context.resources.getQuantityString(R.plurals.report_days, range.days.toInt(), range.days.toInt())}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private val PRESETS =
    listOf(
        ReportPreset.THIS_WEEK to R.string.report_this_week,
        ReportPreset.LAST_WEEK to R.string.report_last_week,
        ReportPreset.THIS_MONTH to R.string.report_this_month,
        ReportPreset.LAST_MONTH to R.string.report_last_month,
    )

/** Everything below the range: the big numbers, then each table. */
private fun LazyListScope.reportItems(report: Report, words: ReportWords) {
    item(key = "big") { BigNumbers(report, words) }
    if (report.totals.delivered == 0 && report.cancellations.orders == 0) {
        item(key = "empty") {
            Text(words.empty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
        return
    }
    item(key = "days") { ByDay(report, words) }
    item(key = "fulfilment") { Fulfilment(report, words) }
    item(key = "rings") { Rings(report, words) }
    // Only once something came in by phone or at the counter (#9): otherwise it is all "Website".
    if (report.enteredByStaff) item(key = "sources") { Sources(report, words) }
    item(key = "best") { BestSellers(report) }
    item(key = "grid") { BusyHours(report.grid) }
    item(key = "times") { Times(report, words) }
    item(key = "cancellations") { Cancellations(report, words) }
    item(key = "discounts") { Discounts(report) }
    item(key = "payments") { Payments(report, words) }
    item(key = "ratings") { Ratings(report.ratings) }
}

/** Umsatz, Bestellungen, Ø Warenkorb, Verspätet: two by two, to read at a glance. */
@Composable
private fun BigNumbers(report: Report, words: ReportWords) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Big(words.revenue, ReportText.euro(report.totals.revenue), Modifier.weight(1f))
            Big(words.orders, report.totals.delivered.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Big(words.basket, ReportText.euro(report.totals.averageBasket), Modifier.weight(1f))
            Big(words.late, ReportText.percent(report.lateRate), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Big(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A card with a heading: one table of the report. */
@Composable
private fun ReportSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** One row of a table: a label on the left, the numbers right-aligned in columns. */
@Composable
private fun Line(label: String, vararg values: String, bold: Boolean = false, muted: Boolean = false) {
    val weight = if (bold) FontWeight.SemiBold else null
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = weight, color = color)
        for (value in values) {
            Text(
                value,
                modifier = Modifier.width(VALUE_WIDTH).padding(start = 4.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = weight,
                color = color,
                maxLines = 1,
            )
        }
    }
}

private val VALUE_WIDTH = 84.dp

/** A thin bar under a row, as long as its share of the largest. */
@Composable
private fun Bar(fraction: Double) {
    Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(
            Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).height(4.dp).background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun ByDay(report: Report, words: ReportWords) {
    val context = LocalContext.current
    val most = report.byDay.maxOfOrNull { it.revenue } ?: 0.0
    ReportSection(stringResource(R.string.report_by_day)) {
        Line(words.day, words.delivered, words.revenue, words.cancelled, muted = true)
        for (day in report.byDay) {
            Line(dayText(context, day.date), day.delivered.toString(), ReportText.euro(day.revenue), day.cancelled.toString())
            if (most > 0) Bar(day.revenue / most)
        }
    }
}

@Composable
private fun Fulfilment(report: Report, words: ReportWords) {
    val f = report.fulfilment
    ReportSection("${words.delivery} / ${words.pickup}") {
        Line("", words.orders, words.revenue, words.basket, muted = true)
        Line(words.delivery, f.delivery.orders.toString(), ReportText.euro(f.delivery.revenue), ReportText.euro(f.delivery.averageBasket))
        Line(words.pickup, f.pickup.orders.toString(), ReportText.euro(f.pickup.revenue), ReportText.euro(f.pickup.averageBasket))
    }
}

@Composable
private fun Rings(report: Report, words: ReportWords) {
    val most = report.rings.maxOfOrNull { it.revenue } ?: 0.0
    ReportSection(stringResource(R.string.report_rings)) {
        Line(words.ring, words.orders, words.revenue, stringResource(R.string.report_fees), muted = true)
        for (ring in report.rings) {
            Line(ReportText.ring(ring.ring, words), ring.orders.toString(), ReportText.euro(ring.revenue), ReportText.euro(ring.deliveryFees + ring.smallOrderFees))
            if (most > 0) Bar(ring.revenue / most)
        }
    }
}

/** Website, Telefon, Theke: what each brought in, and how many were eaten here or had the fee waived. */
@Composable
private fun Sources(report: Report, words: ReportWords) {
    val resources = LocalResources.current
    ReportSection(stringResource(R.string.report_by_source)) {
        Line(stringResource(R.string.report_source), words.orders, words.revenue, words.basket, muted = true)
        for (source in report.bySource) {
            Line(sourceName(resources, source.source), source.delivered.toString(), ReportText.euro(source.revenue), ReportText.euro(source.averageBasket))
        }
        for (source in report.bySource) {
            if (source.dineIn == 0 && source.feesWaived == 0) continue
            Text(
                stringResource(R.string.report_source_extra, sourceName(resources, source.source), source.dineIn, source.feesWaived),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun sourceName(resources: Resources, source: String): String =
    when (source) {
        OrderSource.WEB -> resources.getString(R.string.report_source_web)
        OrderSource.PHONE.wire -> resources.getString(R.string.report_source_phone)
        OrderSource.COUNTER.wire -> resources.getString(R.string.report_source_counter)
        else -> source
    }

/** The top ten, by Menge or by Umsatz, and all of them a tap away. */
@Composable
private fun BestSellers(report: Report) {
    var by by rememberSaveable { mutableStateOf(DishOrder.QTY) }
    var all by rememberSaveable { mutableStateOf(false) }
    val sorted = report.bestSellers(by)
    val shown = if (all) sorted else sorted.take(Report.TOP)
    val most = sorted.firstOrNull()?.let { if (by == DishOrder.QTY) it.qty.toDouble() else it.revenue } ?: 0.0
    ReportSection(stringResource(R.string.report_best_sellers)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = by == DishOrder.QTY, onClick = { by = DishOrder.QTY }, label = { Text(stringResource(R.string.report_qty)) })
            FilterChip(selected = by == DishOrder.REVENUE, onClick = { by = DishOrder.REVENUE }, label = { Text(stringResource(R.string.report_revenue)) })
        }
        if (sorted.isEmpty()) {
            Text(stringResource(R.string.report_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@ReportSection
        }
        for ((index, dish) in shown.withIndex()) {
            Line("${index + 1}. ${dish.name}", "${dish.qty}×", ReportText.euro(dish.revenue))
            if (most > 0) Bar((if (by == DishOrder.QTY) dish.qty.toDouble() else dish.revenue) / most)
        }
        if (sorted.size > Report.TOP) {
            TextButton(onClick = { all = !all }) {
                Text(if (all) stringResource(R.string.report_show_top) else stringResource(R.string.report_show_all, sorted.size))
            }
        }
    }
}

/**
 * Orders per weekday × hour: a row a day, a column an hour, shaded by how
 * many. Only the hours from the first with any orders to the last, so it
 * fits a phone more often than not; wider, it scrolls sideways with the
 * weekdays staying put.
 */
@Composable
private fun BusyHours(grid: BusyGrid) {
    val locale = LocalConfiguration.current.locales[0]
    val hours = grid.busyHours
    ReportSection(stringResource(R.string.report_busy_hours)) {
        if (hours.isEmpty()) {
            Text(stringResource(R.string.report_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@ReportSection
        }
        Text(stringResource(R.string.report_busy_hours_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            Column {
                Spacer(Modifier.height(CELL))
                for (weekday in 1..BusyGrid.WEEKDAYS) {
                    Box(Modifier.height(CELL).padding(end = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Text(DayOfWeek.of(weekday).getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (hour in hours) {
                    Column {
                        Box(Modifier.size(CELL), contentAlignment = Alignment.Center) {
                            Text(hour.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        for (weekday in 1..BusyGrid.WEEKDAYS) BusyCell(grid, weekday, hour, locale)
                    }
                }
            }
        }
    }
}

@Composable
private fun BusyCell(grid: BusyGrid, weekday: Int, hour: Int, locale: Locale) {
    val orders = grid.orders(weekday, hour)
    val shade = grid.shade(weekday, hour)
    val described = "${DayOfWeek.of(weekday).getDisplayName(TextStyle.FULL, locale)} $hour:00: $orders"
    Box(
        Modifier.size(CELL)
            .padding(1.dp)
            .background(
                if (orders == 0) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.primary.copy(alpha = (0.15 + 0.85 * shade).toFloat()),
            )
            .semantics { contentDescription = described },
        contentAlignment = Alignment.Center,
    ) {
        if (orders > 0) {
            Text(
                orders.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (shade > 0.5) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val CELL = 30.dp

/** Median and p90 of each duration, and how late against the promise. */
@Composable
private fun Times(report: Report, words: ReportWords) {
    val t = report.times
    ReportSection(stringResource(R.string.report_times)) {
        Line("", stringResource(R.string.report_median), stringResource(R.string.report_p90), muted = true)
        Duration(stringResource(R.string.report_new_to_accepted), t.newToAccepted, words)
        Duration(stringResource(R.string.report_accepted_to_out), t.acceptedToOut, words)
        Duration(stringResource(R.string.report_out_to_delivered), t.outToDelivered, words)
        Duration(stringResource(R.string.report_accepted_to_delivered), t.acceptedToDelivered, words)
        Duration(stringResource(R.string.report_accepted_to_collected), t.acceptedToCollected, words)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.report_late_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Late(words.delivery, report.late.delivery, words)
        Late(words.pickup, report.late.pickup, words)
        Text(stringResource(R.string.report_times_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Duration(label: String, duration: ReportDuration, words: ReportWords) {
    Line(
        "$label (${duration.orders})",
        ReportText.minutes(duration.median, words),
        ReportText.minutes(duration.p90, words),
    )
}

@Composable
private fun Late(label: String, late: ReportLateness, words: ReportWords) {
    Line(
        stringResource(R.string.report_late_line, label, late.late, late.orders, ReportText.percent(late.rate)),
        ReportText.minutes(late.medianMinutesLate, words),
        ReportText.minutes(late.p90MinutesLate, words),
    )
}

@Composable
private fun Cancellations(report: Report, words: ReportWords) {
    val c = report.cancellations
    ReportSection(stringResource(R.string.report_cancellations)) {
        Line(words.cancelled, c.orders.toString(), ReportText.euro(c.value), bold = true)
        for (row in c.by) Line(ReportText.cancellation(row, words), row.orders.toString(), ReportText.euro(row.value))
    }
}

@Composable
private fun Discounts(report: Report) {
    val d = report.discounts
    ReportSection(stringResource(R.string.report_discounts)) {
        Line(stringResource(R.string.report_discounts_total), "", ReportText.euro(d.total), bold = true)
        Line(stringResource(R.string.report_codes), d.codes.orders.toString(), ReportText.euro(d.codes.amount))
        for (code in d.codes.byCode) Line("  ${code.code}", code.orders.toString(), ReportText.euro(code.amount), muted = true)
        if (d.codes.freeDeliveryOrders > 0) {
            Line(stringResource(R.string.report_free_delivery), d.codes.freeDeliveryOrders.toString(), "", muted = true)
        }
        Line(stringResource(R.string.report_stamps, d.stamps.stampsSpent), d.stamps.orders.toString(), ReportText.euro(d.stamps.amount))
        Line(stringResource(R.string.report_deals), d.deals.orders.toString(), ReportText.euro(d.deals.amount))
        if (d.deals.ordersMeasured < report.totals.delivered) {
            Text(
                stringResource(R.string.report_deals_measured, d.deals.ordersMeasured, report.totals.delivered),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Line(stringResource(R.string.report_pickup_discount), d.pickup.orders.toString(), ReportText.euro(d.pickup.amount))
    }
}

@Composable
private fun Payments(report: Report, words: ReportWords) {
    val p = report.payments
    ReportSection(stringResource(R.string.report_payments)) {
        Line(words.cash, p.cash.orders.toString(), ReportText.euro(p.cash.amount))
        Line(words.card, p.card.orders.toString(), ReportText.euro(p.card.amount))
        Line(words.online, p.online.orders.toString(), ReportText.euro(p.online.amount))
        Line(words.unknown, p.unknown.orders.toString(), ReportText.euro(p.unknown.amount))
    }
}


/**
 * "Wie war's?" over the range: the average with stars, how many rated and
 * what share of the orders, a bar per star count, then the newest comments.
 * A server without the section, or nothing rated yet: "Noch keine Bewertungen".
 */
@Composable
private fun Ratings(ratings: ReportRatings?) {
    ReportSection(stringResource(R.string.report_ratings)) {
        val average = ratings?.average
        if (ratings == null || !ratings.any || average == null) {
            Text(stringResource(R.string.report_ratings_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val label = stringResource(R.string.report_ratings_average_label, ReportText.tenth(average))
            Text(
                stringResource(R.string.report_ratings_average, "${ReportText.tenth(average)} ${ReportText.stars(average)}"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = label },
            )
            Text(
                pluralStringResource(R.plurals.report_ratings_count, ratings.count, ratings.count, ReportText.percent(ratings.ratedShare)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val most = (1..5).maxOf(ratings::withStars)
            for (stars in 5 downTo 1) {
                val n = ratings.withStars(stars)
                Line(OrderRatings.starsText(stars), n.toString())
                if (most > 0) Bar(n.toDouble() / most)
            }
        }
        if (ratings == null) return@ReportSection
        val comments = ratings.comments
        // Stars with no comments to go with them (cleared after 30 days) say so; no ratings at all already did.
        if (comments.isEmpty() && !ratings.any) return@ReportSection
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.report_ratings_comments), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (comments.isEmpty()) {
            Text(stringResource(R.string.report_ratings_no_comments), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (comment in comments) {
            val label = stringResource(R.string.rating_label, comment.stars)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    OrderRatings.starsText(comment.stars),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (comment.stars <= OrderRatings.LOW_STARS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = label },
                )
                comment.date?.let { Text(dateText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(stringResource(R.string.rating_comment, comment.text.orEmpty()), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        }
    }
}
