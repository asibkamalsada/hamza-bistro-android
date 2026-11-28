package de.hamzabistro.printstation.ui

import android.app.Application
import android.app.DatePickerDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.CashUp
import de.hamzabistro.printstation.core.Nachschau
import de.hamzabistro.printstation.core.NachschauExport
import de.hamzabistro.printstation.core.NachschauFile
import de.hamzabistro.printstation.core.NachschauRangeException
import de.hamzabistro.printstation.core.NachschauTar
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Kassen-Nachschau screen shows. */
data class NachschauState(
    val from: LocalDate,
    val to: LocalDate,
    val loading: Boolean = false,
    val data: Nachschau? = null,
    val error: String? = null,
    /** About the export just asked for: requested, or why not. */
    val exportMessage: String? = null,
    val exportOk: Boolean = true,
)

/**
 * A Kassen-Nachschau (hamza-bistro-web K7): pick the days, ask for the
 * DSFinV-K export, and fetch it, the daily closings' ZIPs and the TSE's TAR
 * files into Downloads/Kassen-Nachschau — each by a link Storage signs for a
 * minute. The site's /orders/nachschau does the same.
 */
class NachschauViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val backend = (application as PrintStationApp).graph.nachschau

    private val _state: MutableStateFlow<NachschauState>
    val state: StateFlow<NachschauState>

    private var reading: Job? = null
    private var polling: Job? = null

    init {
        val (from, to) = Nachschau.previousMonth(today())
        _state = MutableStateFlow(NachschauState(from, to))
        state = _state.asStateFlow()
    }

    /** Today in Leipzig, by this device's clock. */
    fun today(): LocalDate = CashUp.today(Instant.now())

    fun pick(from: LocalDate, to: LocalDate) {
        _state.update { it.copy(from = minOf(from, to), to = maxOf(from, to), exportMessage = null) }
        load()
    }

    fun load() {
        reading?.cancel()
        val (from, to) = _state.value.let { it.from to it.to }
        _state.update { it.copy(loading = true, error = null) }
        reading =
            viewModelScope.launch {
                var failed = false
                val read = attempt(app, { error -> failed = true; _state.update { it.copy(loading = false, error = error) } }) {
                    backend.load(from, to)
                }
                if (failed || read == null) return@launch
                _state.update { it.copy(loading = false, data = read) }
                pollIfMaking()
            }
    }

    fun requestExport() {
        val (from, to) = _state.value.let { it.from to it.to }
        if (!to.isBefore(today())) {
            _state.update { it.copy(exportMessage = app.getString(R.string.nachschau_export_closed_only), exportOk = false) }
            return
        }
        viewModelScope.launch {
            try {
                backend.requestExport(from, to)
                _state.update { it.copy(exportMessage = app.getString(R.string.nachschau_export_requested), exportOk = true) }
                load()
            } catch (e: NachschauRangeException) {
                _state.update { it.copy(exportMessage = app.getString(R.string.nachschau_export_closed_only), exportOk = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(exportMessage = failureText(app, e), exportOk = false) }
            }
        }
    }

    /** Each link is signed just before the download starts. */
    fun download(files: List<NachschauFile>) {
        viewModelScope.launch {
            val manager = app.getSystemService(DownloadManager::class.java)
            for (file in files) {
                val name = file.fileName ?: continue
                val url = attempt(app, { error -> _state.update { it.copy(error = error) } }) { backend.signedUrl(file) } ?: return@launch
                val request =
                    DownloadManager.Request(Uri.parse(url))
                        .setTitle(name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$DOWNLOAD_DIR/$name")
                manager.enqueue(request)
            }
            _state.update { it.copy(exportMessage = app.getString(R.string.nachschau_downloading, DOWNLOAD_DIR), exportOk = true) }
        }
    }

    /** While an export is being made, asked again every few seconds; stops with the screen. */
    private fun pollIfMaking() {
        polling?.cancel()
        if (_state.value.data?.making != true) return
        polling =
            viewModelScope.launch {
                delay(POLL_MILLIS)
                load()
            }
    }

    fun stopPolling() {
        polling?.cancel()
    }

    private companion object {
        const val POLL_MILLIS = 10_000L
        const val DOWNLOAD_DIR = "Kassen-Nachschau"
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy, HH:mm")

private fun dayText(day: String): String = DATE.format(LocalDate.parse(day))

private fun whenText(at: String): String = WHEN.format(OffsetDateTime.parse(at).atZoneSameInstant(AlarmPolicy.LEIPZIG))

private fun sizeText(bytes: Long?): String =
    when {
        bytes == null -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

/** A day on a calendar, up to [max]. */
private fun pickDay(context: Context, day: LocalDate, min: LocalDate?, max: LocalDate, onPicked: (LocalDate) -> Unit) {
    val dialog =
        DatePickerDialog(context, { _, year, month, dayOfMonth -> onPicked(LocalDate.of(year, month + 1, dayOfMonth)) }, day.year, day.monthValue - 1, day.dayOfMonth)
    fun millis(d: LocalDate) = d.atStartOfDay(AlarmPolicy.LEIPZIG).toInstant().toEpochMilli()
    dialog.datePicker.maxDate = millis(max) + 24L * 60 * 60 * 1000 - 1
    if (min != null) dialog.datePicker.minDate = millis(min)
    dialog.show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NachschauScreen(navigation: @Composable () -> Unit) {
    val model: NachschauViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.load() }
    DisposableEffect(Unit) { onDispose { model.stopPolling() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nachschau_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = navigation,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Text(stringResource(R.string.nachschau_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "range") {
                val today = model.today()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${DATE.format(state.from)} – ${DATE.format(state.to)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        pickDay(context, state.from, null, today) { from ->
                            pickDay(context, maxOf(state.to, from).coerceAtMost(today), from, today) { to -> model.pick(from, to) }
                        }
                    }) { Text(stringResource(R.string.nachschau_pick_range)) }
                }
            }
            state.error?.let { error ->
                item(key = "error") {
                    Column {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = model::load) { Text(stringResource(R.string.reload)) }
                    }
                }
            }
            val data = state.data
            if (state.loading && data == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            if (data != null) {
                exportItems(data, state, model)
                closingItems(data, model)
                tarItems(data, model)
            }
        }
    }
}

private fun LazyListScope.exportItems(data: Nachschau, state: NachschauState, model: NachschauViewModel) {
    item(key = "export-head") {
        Section(stringResource(R.string.nachschau_export_heading), stringResource(R.string.nachschau_export_note)) {
            Button(onClick = model::requestExport) { Text(stringResource(R.string.nachschau_export_request)) }
            state.exportMessage?.let {
                Text(it, color = if (state.exportOk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            }
            if (data.exports.isEmpty()) Text(stringResource(R.string.nachschau_exports_none))
        }
    }
    items(data.exports, key = { "export-${it.id}" }) { row ->
        FileRow(
            title = "${dayText(row.dateFrom)} – ${dayText(row.dateTo)}",
            badge = exportState(row),
            file = row,
            error = row.lastError,
            failed = row.failed,
            onDownload = { model.download(listOf(row)) },
        )
    }
}

@Composable
private fun exportState(row: NachschauExport): String =
    stringResource(
        when {
            row.dismissed -> R.string.nachschau_state_dismissed
            row.failed -> R.string.nachschau_state_failed
            row.state == "stored" -> R.string.nachschau_state_stored
            else -> R.string.nachschau_state_requested
        }
    )

private fun LazyListScope.closingItems(data: Nachschau, model: NachschauViewModel) {
    val kept = data.closings.filter { it.path != null }
    item(key = "closings-head") {
        Section(stringResource(R.string.nachschau_closings_heading), stringResource(R.string.nachschau_closings_note)) {
            if (data.closings.isEmpty()) Text(stringResource(R.string.nachschau_closings_none))
            if (kept.size > 1) {
                OutlinedButton(onClick = { model.download(kept) }) { Text(stringResource(R.string.nachschau_download_all, kept.size)) }
            }
        }
    }
    items(data.closings, key = { "closing-${it.businessDate}" }) { row ->
        FileRow(
            title = "${dayText(row.businessDate)} · Z ${row.closingNumber}",
            badge =
                when {
                    row.path == null -> stringResource(R.string.nachschau_closing_built)
                    row.env == "TEST" -> "TEST"
                    else -> null
                },
            file = row,
            error = null,
            failed = row.failed,
            onDownload = { model.download(listOf(row)) },
        )
    }
}

private fun LazyListScope.tarItems(data: Nachschau, model: NachschauViewModel) {
    val kept = data.tars.filter { it.path != null }
    item(key = "tars-head") {
        Section(stringResource(R.string.nachschau_tar_heading), stringResource(R.string.nachschau_tar_note)) {
            val until = data.tarStoredUntil
            val short = until == null || !OffsetDateTime.parse(until).atZoneSameInstant(AlarmPolicy.LEIPZIG).toLocalDate().isAfter(LocalDate.parse(data.to))
            if (short) {
                Text(
                    if (until == null) stringResource(R.string.nachschau_tar_none_kept) else stringResource(R.string.nachschau_tar_until, whenText(until)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (data.tars.isEmpty()) Text(stringResource(R.string.nachschau_tar_none))
            if (kept.size > 1) {
                OutlinedButton(onClick = { model.download(kept) }) { Text(stringResource(R.string.nachschau_download_all, kept.size)) }
            }
        }
    }
    items(data.tars, key = { "tar-${it.id}" }) { row ->
        FileRow(
            title = tarRange(row),
            badge =
                when {
                    row.failed -> stringResource(R.string.nachschau_state_failed)
                    row.state == "pending" -> stringResource(R.string.nachschau_state_pending)
                    else -> null
                },
            file = row,
            error = row.lastError,
            failed = row.failed,
            subtitle = "TSE ${row.serial.take(12)}…",
            onDownload = { model.download(listOf(row)) },
        )
    }
}

@Composable
private fun tarRange(row: NachschauTar): String {
    val from = row.fromTime?.let(::whenText) ?: stringResource(R.string.nachschau_tar_beginning)
    return "$from – ${whenText(row.toTime)}"
}

@Composable
private fun Section(title: String, note: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** One file: what it is, its name, size and checksum, and "Herunterladen" once it is kept. */
@Composable
private fun FileRow(
    title: String,
    badge: String?,
    file: NachschauFile,
    error: String?,
    failed: Boolean,
    onDownload: () -> Unit,
    subtitle: String? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            badge?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            file.fileName?.let { Text("$it · ${sizeText(file.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            file.sha256?.let { Text("SHA-256 $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (file.path != null) {
                TextButton(onClick = onDownload) { Text(stringResource(R.string.nachschau_download)) }
            }
        }
    }
}
