package de.hamzabistro.printstation.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.AddressCheckHealth
import de.hamzabistro.printstation.core.AddressProbe
import de.hamzabistro.printstation.core.AddressVerdict
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.AppDevice
import de.hamzabistro.printstation.core.AppVersions
import de.hamzabistro.printstation.core.OpsHealth
import de.hamzabistro.printstation.core.OpsRow
import de.hamzabistro.printstation.core.OpsVerdict
import de.hamzabistro.printstation.core.PrintStationRow
import de.hamzabistro.printstation.core.PushDevice
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The health of the things that fail without anybody noticing. Each null until read. */
data class HealthState(
    val appDevices: List<AppDevice>? = null,
    val pushDevices: List<PushDevice>? = null,
    val stations: List<PrintStationRow> = emptyList(),
    val address: AddressCheckHealth? = null,
    /** "Überwachung" (hamza-bistro-web#92): null until read, and on a database without it. */
    val ops: List<OpsRow>? = null,
    val probing: Boolean = false,
    val probe: Message? = null,
    /** What removing a device last said, when it failed. */
    val problem: String? = null,
)

/**
 * Who is going to hear about the next order — the answer that matters most
 * is "nobody", and it is otherwise silent — which devices print by
 * themselves, and whether the address check works: the health half of the
 * site's /orders/settings.
 */
class HealthViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    /** This device's id, to mark it in the lists. */
    val thisDevice: String
        get() = graph.settings.stationId

    private val _state = MutableStateFlow(HealthState())
    val state: StateFlow<HealthState> = _state.asStateFlow()

    /** Each part on its own: one that fails leaves the others to say what they know. */
    fun load() {
        val devices = graph.devices
        viewModelScope.launch { attempt(app, {}) { devices.appDevices() }?.let { list -> _state.update { it.copy(appDevices = list) } } }
        viewModelScope.launch { attempt(app, {}) { devices.pushDevices() }?.let { list -> _state.update { it.copy(pushDevices = list) } } }
        viewModelScope.launch { attempt(app, {}) { devices.printStations(thisDevice) }?.let { list -> _state.update { it.copy(stations = list) } } }
        viewModelScope.launch { attempt(app, {}) { devices.addressHealth() }?.let { health -> _state.update { it.copy(address = health) } } }
        viewModelScope.launch { attempt(app, {}) { graph.ops.overview() }?.let { rows -> _state.update { it.copy(ops = rows) } } }
    }

    /** Lost, wiped or given away: it cannot end its own shift. One still on shift is back within a minute. */
    fun removeAppDevice(id: String) = remove { graph.devices.removeAppDevice(id) }

    /** No longer printing. One that still prints puts itself back within a minute, so this cannot silence it. */
    fun removeStation(id: String) = remove { graph.devices.removePrintStation(id) }

    private fun remove(call: suspend () -> Unit) {
        _state.update { it.copy(problem = null) }
        viewModelScope.launch {
            attempt(app, { error -> _state.update { it.copy(problem = error) } }) { call() }
            load()
        }
    }

    /**
     * Looks the shop's own address up live: the only way to tell "the
     * geocoder works" from "the cache hides that it stopped an hour ago".
     * A few seconds at worst; then the panel is read again, since the test
     * is itself the newest outcome.
     */
    fun testAddress() {
        if (_state.value.probing) return
        _state.update { it.copy(probing = true, probe = null) }
        viewModelScope.launch {
            val probe =
                attempt(app, { error -> _state.update { it.copy(probe = Message(app.getString(R.string.address_test_failed, error), error = true)) } }) {
                    graph.devices.testAddress()
                }
            if (probe != null) _state.update { it.copy(probe = Message(describe(app, probe), error = !probe.ok || probe.skipped != null)) }
            attempt(app, {}) { graph.devices.addressHealth() }?.let { health -> _state.update { it.copy(address = health) } }
            _state.update { it.copy(probing = false) }
        }
    }

    companion object {
        /** What a live test said, as a sentence — describeProbe() of the site. */
        fun describe(context: Context, probe: AddressProbe): String {
            val skipped = probe.skipped
            if (probe.ok && skipped != null) return context.getString(R.string.address_test_fallback, probe.via ?: "?", skipped)
            if (probe.ok) return context.getString(R.string.address_test_ok, probe.postalCode ?: "", probe.ms)
            // Found, but somewhere other than the shop: it answers, and answers wrongly.
            val reason =
                when {
                    probe.outcome == "found" -> "${probe.postalCode ?: "?"} / ${probe.zone ?: "?"}"
                    probe.detail != null -> "${probe.outcome} (${probe.detail})"
                    else -> probe.outcome
                }
            return context.getString(R.string.address_test_failed, reason)
        }
    }
}

/** The sections, for the settings screen. */
@Composable
fun HealthSections() {
    val health: HealthViewModel = viewModel()
    val state by health.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { health.load() }
    WhoHears(state, health)
    PrintStations(state, health)
    AddressCheck(state, health)
    Monitoring(state)
}

@Composable
private fun WhoHears(state: HealthState, health: HealthViewModel) {
    val context = LocalContext.current
    val now = Instant.now()
    Section(stringResource(R.string.who_hears)) {
        val apps = state.appDevices
        val pushes = state.pushDevices
        if (apps == null && pushes == null) {
            Text(stringResource(R.string.please_wait), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        // Only when both lists are known: unknown is not nobody.
        if (apps != null && pushes != null && apps.none { it.rings(now) } && pushes.none { it.notifyNew }) {
            Text(stringResource(R.string.who_hears_nobody), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        if (!apps.isNullOrEmpty()) {
            Hint(stringResource(R.string.who_hears_app_hint))
            val versions = apps.map { it.appVersion }
            for (device in apps) {
                val listening = device.listening(now)
                Listed(
                    name = device.label.ifBlank { stringResource(R.string.device_unnamed) },
                    whose =
                        when {
                            device.id == health.thisDevice -> stringResource(R.string.device_this)
                            device.mine -> stringResource(R.string.device_mine)
                            else -> null
                        },
                    lines =
                        listOf(
                            "${stringResource(R.string.device_app)} · ${appAlarm(context, device.alarm)}" to true,
                            (if (listening) stringResource(R.string.device_seen, stamp(device.startedAt), stamp(device.lastSeenAt))
                            else stringResource(R.string.device_silent, stamp(device.lastSeenAt))) to listening,
                        ),
                    onRemove = { health.removeAppDevice(device.id) },
                ) {
                    if (AppVersions.parse(device.appVersion) != null) {
                        AppVersionLine(device.appVersion!!.trim(), AppVersions.outdated(device.appVersion, versions))
                    }
                }
            }
        }
        if (!pushes.isNullOrEmpty()) {
            Hint(stringResource(R.string.who_hears_push_hint))
            for (device in pushes) {
                Listed(
                    name = device.label.ifBlank { stringResource(R.string.device_unnamed) },
                    whose = if (device.mine) stringResource(R.string.device_mine) else null,
                    lines =
                        listOf(
                            pushKinds(context, device) to true,
                            when {
                                device.failing -> stringResource(R.string.device_push_failed, stamp(device.lastErrorAt), device.lastError ?: "?") to false
                                device.lastSentAt != null -> stringResource(R.string.device_push_sent, stamp(device.lastSentAt)) to true
                                else -> stringResource(R.string.device_push_never) to true
                            },
                        ),
                    onRemove = null,
                )
            }
        }
        state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * The devices that print every accepted order. While one is listed, a ticket
 * not out two minutes after its order was accepted rings the phones — so
 * one that no longer prints belongs off the list.
 */
@Composable
private fun PrintStations(state: HealthState, health: HealthViewModel) {
    if (state.stations.isEmpty()) return
    Section(stringResource(R.string.print_stations)) {
        Hint(stringResource(R.string.print_stations_hint))
        for (station in state.stations) {
            Listed(
                name = station.label.ifBlank { stringResource(R.string.print_station_unnamed) },
                whose = if (station.thisStation) stringResource(R.string.device_this) else null,
                lines = listOf(stringResource(R.string.print_station_seen, stamp(station.lastSeenAt)) to true),
                onRemove = { health.removeStation(station.id) },
            )
        }
    }
}

/** Whether the address check works: the failure it exists for otherwise shows only as orders priced on a typed postcode. */
@Composable
private fun AddressCheck(state: HealthState, health: HealthViewModel) {
    Section(stringResource(R.string.address_check)) {
        val address = state.address
        if (address != null) {
            val verdict = address.verdict()
            Text(
                when (verdict) {
                    is AddressVerdict.Ok -> stringResource(R.string.address_ok, stamp(verdict.lastFound))
                    is AddressVerdict.Failing -> stringResource(R.string.address_failing, stamp(verdict.at), verdict.error)
                    is AddressVerdict.Fallback -> stringResource(R.string.address_fallback, stamp(verdict.at), verdict.error)
                    AddressVerdict.Unconfigured -> stringResource(R.string.address_unconfigured)
                    AddressVerdict.NoData -> stringResource(R.string.address_no_data)
                },
                color = if (verdict.ok) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            if (!address.requireCheck) Text(stringResource(R.string.address_require_off), color = MaterialTheme.colorScheme.error)
            if (address.unverified7d > 0) Hint(stringResource(R.string.address_unverified, address.unverified7d, address.deliveries7d))
        }
        OutlinedButton(onClick = health::testAddress, enabled = !state.probing) {
            Text(stringResource(if (state.probing) R.string.please_wait else R.string.address_test))
        }
        state.probe?.let { Text(it.text, color = if (it.error) MaterialTheme.colorScheme.error else Color.Unspecified) }
    }
}

/**
 * "Überwachung", as on the site's /orders/settings: one line per part of the
 * shop that fails quietly — the functions, the pg_cron jobs, the heartbeat.
 * Left out on a database without it.
 */
@Composable
private fun Monitoring(state: HealthState) {
    val rows = state.ops?.takeIf { it.isNotEmpty() } ?: return
    val context = LocalContext.current
    Section(stringResource(R.string.ops_heading)) {
        Hint(stringResource(R.string.ops_hint))
        for (row in OpsHealth.sorted(rows)) {
            val verdict = OpsHealth.verdict(row)
            Listed(name = opsLabel(context, row.source), whose = null, lines = listOf(opsText(context, verdict) to verdict.ok), onRemove = null)
        }
    }
}

/** The site's name for a source; one this app does not know shows as itself. */
private fun opsLabel(context: Context, source: String): String =
    when (source) {
        "notify-order" -> R.string.ops_notify_order
        "notify-customer" -> R.string.ops_notify_customer
        "telegram-bot" -> R.string.ops_telegram_bot
        "cron:reminders" -> R.string.ops_cron_reminders
        "cron:email-retry" -> R.string.ops_cron_email_retry
        "cron:retention" -> R.string.ops_cron_retention
        "cron:geocodes" -> R.string.ops_cron_geocodes
        "cron:history" -> R.string.ops_cron_history
        "cron:daily-summary" -> R.string.ops_cron_daily_summary
        "heartbeat" -> R.string.ops_heartbeat
        else -> null
    }?.let(context::getString) ?: source

/** "OK 03.10., 18:32", "Fehler …: …", "Läuft nicht — …", on a Leipzig clock. */
private fun opsText(context: Context, verdict: OpsVerdict): String =
    when (verdict) {
        is OpsVerdict.Ok ->
            verdict.lastError?.let { context.getString(R.string.ops_ok_last_error, stamp(verdict.at), stamp(it)) }
                ?: context.getString(R.string.ops_ok, stamp(verdict.at))
        is OpsVerdict.Failing -> context.getString(R.string.ops_failing, stamp(verdict.at), verdict.error)
        is OpsVerdict.Stale -> verdict.lastOk?.let { context.getString(R.string.ops_stale, stamp(it)) } ?: context.getString(R.string.ops_stale_never)
        OpsVerdict.Never -> context.getString(R.string.ops_never)
        OpsVerdict.NotSetUp -> context.getString(R.string.ops_heartbeat_off)
    }

/** One device in a list: its name and whose, a line or two, each good or not, and a way to take it off. */
@Composable
private fun Listed(
    name: String,
    whose: String?,
    lines: List<Pair<String, Boolean>>,
    onRemove: (() -> Unit)?,
    more: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(if (whose == null) name else "$name ($whose)", fontWeight = FontWeight.SemiBold)
            for ((line, ok) in lines) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            more()
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
    }
}

/** "Version 0.2.57", and "· veraltet" in red behind one older than the newest on the list. */
@Composable
private fun AppVersionLine(version: String, outdated: Boolean) {
    val outdatedText = stringResource(R.string.device_version_outdated)
    val error = MaterialTheme.colorScheme.error
    Text(
        buildAnnotatedString {
            append(stringResource(R.string.app_version, version))
            if (outdated) withStyle(SpanStyle(color = error)) { append(" · $outdatedText") }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Hamza-Team-App · klingelt, bis jemand annimmt". */
private fun appAlarm(context: Context, alarm: String): String =
    context.getString(
        when (alarm) {
            "once" -> R.string.device_alarm_once
            "off" -> R.string.device_alarm_off
            else -> R.string.device_alarm_loop
        }
    )

/** "neue Bestellungen, Erinnerungen · laut · Nachtruhe 22:00–09:00". */
private fun pushKinds(context: Context, device: PushDevice): String {
    val kinds =
        listOfNotNull(
            context.getString(R.string.kind_new).takeIf { device.notifyNew },
            context.getString(R.string.kind_reminders).takeIf { device.notifyReminders },
            context.getString(R.string.kind_preorders).takeIf { device.notifyPreorders },
            context.getString(R.string.kind_unprinted).takeIf { device.notifyUnprinted },
        )
    val from = device.quietFrom
    val to = device.quietTo
    val quiet = if (from != null && to != null) context.getString(R.string.kind_quiet, Format.time(from), Format.time(to)) else null
    return listOfNotNull(
            kinds.joinToString(", ").ifEmpty { null },
            context.getString(R.string.kind_loud).takeIf { device.loud },
            quiet,
        )
        .joinToString(" · ")
}

/** "22.09., 18:43", on a Leipzig clock. */
private fun stamp(at: Instant?): String = at?.let { STAMP.format(it.atZone(AlarmPolicy.LEIPZIG)) } ?: ""

private val STAMP = DateTimeFormatter.ofPattern("dd.MM., HH:mm")
