package de.hamzabistro.printstation.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.NavApp
import de.hamzabistro.printstation.core.NewOrderAlarm
import de.hamzabistro.printstation.core.TravelMode
import de.hamzabistro.printstation.core.UpdateChecker
import de.hamzabistro.printstation.station.AlarmSound
import de.hamzabistro.printstation.station.DevicePrefs
import de.hamzabistro.printstation.station.StationSettings
import de.hamzabistro.printstation.station.alarmForDevice
import java.time.LocalTime

/** The night windows offered: none, or the hours a shop that closes at nine sleeps through. */
private val QUIET_WINDOWS: List<Pair<LocalTime, LocalTime>?> =
    listOf(null, LocalTime.of(22, 0) to LocalTime.of(9, 0), LocalTime.of(21, 30) to LocalTime.of(9, 30), LocalTime.of(23, 0) to LocalTime.of(8, 0))

/**
 * Everything that is about this device — the shift, how it rings, how it
 * accepts and routes, the printer, the account — and the health of the
 * shop's: who hears about orders, the print stations, the address check.
 * /orders/settings, for a phone or tablet with the app.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(staff: StaffViewModel, main: MainViewModel, onBack: () -> Unit) {
    val state by staff.state.collectAsStateWithLifecycle()
    val mainState by main.state.collectAsStateWithLifecycle()
    val update by staff.update.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        checks++
        main.refresh()
        staff.checkForUpdate(UpdateChecker.ON_OPEN)
    }
    val askForNotifications =
        // The shift starts either way: without notifications it still rings,
        // only not over the lock screen — which the section below says.
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { staff.setOnShift(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            mainState.message?.let { Notice(it, onDismiss = main::dismissMessage) }

            Section(stringResource(R.string.section_shift)) {
                SwitchRow(stringResource(R.string.shift_switch), prefs.onShift) { on ->
                    if (on && needsNotificationPermission(context)) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else staff.setOnShift(on)
                }
                Text(stringResource(R.string.shift_hint), style = MaterialTheme.typography.bodySmall)
                var label by remember { mutableStateOf(prefs.label) }
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it.take(40)
                        staff.updatePrefs { p -> p.copy(label = label) }
                    },
                    label = { Text(stringResource(R.string.device_name)) },
                    placeholder = { Text(stringResource(R.string.device_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section(stringResource(R.string.section_alarm)) {
                Text(stringResource(R.string.alarm_mode), style = MaterialTheme.typography.labelLarge)
                for (mode in NewOrderAlarm.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = prefs.alarm.newOrders == mode,
                            onClick = { staff.updatePrefs { it.copy(alarm = it.alarm.copy(newOrders = mode)) } },
                        )
                        Text(
                            stringResource(
                                when (mode) {
                                    NewOrderAlarm.LOOP -> R.string.alarm_mode_loop
                                    NewOrderAlarm.ONCE -> R.string.alarm_mode_once
                                    NewOrderAlarm.OFF -> R.string.alarm_mode_off
                                }
                            )
                        )
                    }
                }
                Text(stringResource(R.string.alarm_sound), style = MaterialTheme.typography.labelLarge)
                Chips(AlarmSound.entries, prefs.sound, { soundName(context, it) }) { sound -> staff.updatePrefs { it.copy(sound = sound) } }
                OutlinedButton(onClick = staff::testSound) { Text(stringResource(R.string.alarm_test)) }
                SwitchRow(stringResource(R.string.alarm_full_volume), prefs.fullVolume) { on -> staff.updatePrefs { it.copy(fullVolume = on) } }
                SwitchRow(stringResource(R.string.alarm_vibrate), prefs.vibrate) { on -> staff.updatePrefs { it.copy(vibrate = on) } }
                Text(stringResource(R.string.alarm_pause), style = MaterialTheme.typography.labelLarge)
                Chips(StationSettings.PAUSE_CHOICES, prefs.pauseSeconds, { pauseName(context, it) }) { seconds ->
                    staff.updatePrefs { it.copy(pauseSeconds = seconds) }
                }
                // How long each ring is only matters between pauses.
                if (prefs.pauseSeconds > 0) {
                    Text(stringResource(R.string.alarm_ring_for), style = MaterialTheme.typography.labelLarge)
                    Chips(StationSettings.RING_CHOICES, prefs.ringSeconds, { secondsName(context, it) }) { seconds ->
                        staff.updatePrefs { it.copy(ringSeconds = seconds) }
                    }
                }
                Text(stringResource(R.string.alarm_pause_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.alarm_silence_for), style = MaterialTheme.typography.labelLarge)
                Chips(StationSettings.SILENCE_CHOICES, prefs.alarm.silenceSeconds, { secondsName(context, it) }) { seconds ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(silenceSeconds = seconds)) }
                }
                Text(stringResource(R.string.alarm_quiet), style = MaterialTheme.typography.labelLarge)
                val from = prefs.alarm.quietFrom
                val to = prefs.alarm.quietTo
                val window: Pair<LocalTime, LocalTime>? = if (from != null && to != null) from to to else null
                Chips(QUIET_WINDOWS, window, { quietName(context, it) }) { chosen ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(quietFrom = chosen?.first, quietTo = chosen?.second)) }
                }
                Text(stringResource(R.string.alarm_quiet_hint), style = MaterialTheme.typography.bodySmall)
                SwitchRow(stringResource(R.string.alarm_cook_now), prefs.alarm.cookNow) { on ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(cookNow = on)) }
                }
                SwitchRow(stringResource(R.string.alarm_printer), prefs.alarm.printer) { on ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(printer = on)) }
                }
                SwitchRow(stringResource(R.string.alarm_unprinted), prefs.alarm.unprinted) { on ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(unprinted = on)) }
                }
                SwitchRow(stringResource(R.string.alarm_connection), prefs.alarm.connection) { on ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(connection = on)) }
                }
                // "Bestellung fertig": for the driver's phone, not the kitchen tablet.
                SwitchRow(stringResource(R.string.alarm_packed), prefs.alarmForDevice.packed == true) { on ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(packed = on)) }
                }
                Text(stringResource(R.string.alarm_packed_hint), style = MaterialTheme.typography.bodySmall)
                // "Neue Reklamation": for the kitchen tablet, not the driver's phone.
                SwitchRow(stringResource(R.string.alarm_issues), prefs.alarmForDevice.issues == true) { on ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(issues = on)) }
                }
                Text(stringResource(R.string.alarm_issues_hint), style = MaterialTheme.typography.bodySmall)
                // "Schlechte Bewertung": like the reports, for the kitchen tablet.
                SwitchRow(stringResource(R.string.alarm_bad_ratings), prefs.alarmForDevice.badRatings == true) { on ->
                    staff.updatePrefs { it.copy(alarm = it.alarm.copy(badRatings = on)) }
                }
                Text(stringResource(R.string.alarm_bad_ratings_hint), style = MaterialTheme.typography.bodySmall)
            }

            Section(stringResource(R.string.section_ringing)) {
                val missing = remember(checks) { missingForAlarm(context) }
                if (missing.isEmpty()) {
                    Text(stringResource(R.string.ringing_ok))
                } else {
                    Text(stringResource(R.string.ringing_missing), color = MaterialTheme.colorScheme.error)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (R.string.need_notifications in missing) {
                        OutlinedButton(onClick = { context.openAppNotificationSettings() }) { Text(stringResource(R.string.fix_notifications)) }
                    }
                    if (R.string.need_full_screen in missing) {
                        OutlinedButton(onClick = { context.openFullScreenSettings() }) { Text(stringResource(R.string.fix_full_screen)) }
                    }
                    if (R.string.need_background in missing) {
                        Button(onClick = { context.askToIgnoreBatteryOptimisation() }) { Text(stringResource(R.string.battery_button)) }
                    }
                }
                Text(stringResource(R.string.ringing_dnd_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.battery_hint_staff), style = MaterialTheme.typography.bodySmall)
            }

            Section(stringResource(R.string.section_accept)) {
                Text(stringResource(R.string.eta_ladder), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (minutes in Eta.CHOICES) {
                        val on = minutes in prefs.etaLadder
                        FilterChip(
                            selected = on,
                            onClick = {
                                val next = if (on) prefs.etaLadder - minutes else (prefs.etaLadder + minutes).sorted()
                                // An empty ladder would leave only the estimate: nobody chooses that on purpose.
                                if (next.isNotEmpty()) staff.updatePrefs { it.copy(etaLadder = next) }
                            },
                            label = { Text(stringResource(R.string.eta_button, minutes)) },
                        )
                    }
                }
                Text(stringResource(R.string.undo_window), style = MaterialTheme.typography.labelLarge)
                Chips(StationSettings.UNDO_CHOICES, prefs.undoSeconds, { undoName(context, it) }) { seconds ->
                    staff.updatePrefs { it.copy(undoSeconds = seconds) }
                }
            }

            Section(stringResource(R.string.section_route)) {
                Chips(NavApp.entries, prefs.navApp, { navName(context, it) }) { app -> staff.updatePrefs { it.copy(navApp = app) } }
                Chips(TravelMode.entries, prefs.travelMode, { travelName(context, it) }) { mode ->
                    staff.updatePrefs { it.copy(travelMode = mode) }
                }
                Text(stringResource(R.string.travel_by_ring_hint), style = MaterialTheme.typography.bodySmall)
                SwitchRow(stringResource(R.string.driver_device), prefs.driver) { on -> staff.updatePrefs { it.copy(driver = on) } }
                Text(stringResource(R.string.driver_device_hint), style = MaterialTheme.typography.bodySmall)
            }

            Section(stringResource(R.string.section_screen)) {
                SwitchRow(stringResource(R.string.keep_awake), prefs.keepAwake) { on -> staff.updatePrefs { it.copy(keepAwake = on) } }
            }

            Text(stringResource(R.string.section_printer_staff), style = MaterialTheme.typography.titleMedium)
            PrinterSection(mainState, main)
            StationSection(mainState, main)

            // Who hears about orders, the print stations, the address check.
            HealthSections()

            // Back to how the app comes; the account, the shift and the name stay.
            TextButton(onClick = { staff.updatePrefs { DevicePrefs(role = it.role, onShift = it.onShift, label = it.label) } }) {
                Text(stringResource(R.string.settings_reset))
            }

            AccountSection(mainState, main)

            AppSection(update, onCheck = { staff.checkForUpdate() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(choices: List<T>, selected: T, name: (T) -> String, onChoose: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (choice in choices) {
            FilterChip(selected = choice == selected, onClick = { onChoose(choice) }, label = { Text(name(choice)) })
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.weight(1f).widthIn(max = 600.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun soundName(context: Context, sound: AlarmSound) =
    context.getString(
        when (sound) {
            AlarmSound.BEEPS -> R.string.sound_beeps
            AlarmSound.BELL -> R.string.sound_bell
            AlarmSound.SIREN -> R.string.sound_siren
            AlarmSound.DEVICE -> R.string.sound_device
        }
    )

private fun secondsName(context: Context, seconds: Int) =
    if (seconds % 60 == 0) context.getString(R.string.minutes_n, seconds / 60) else context.getString(R.string.seconds_n, seconds)

private fun pauseName(context: Context, seconds: Int) =
    if (seconds == 0) context.getString(R.string.alarm_pause_none) else secondsName(context, seconds)

private fun undoName(context: Context, seconds: Int) =
    if (seconds == 0) context.getString(R.string.undo_none) else context.getString(R.string.seconds_n, seconds)

private fun quietName(context: Context, window: Pair<LocalTime, LocalTime>?) =
    if (window == null) context.getString(R.string.quiet_never)
    else context.getString(R.string.quiet_window, Format.time(window.first), Format.time(window.second))

private fun navName(context: Context, app: NavApp) =
    context.getString(
        when (app) {
            NavApp.GOOGLE -> R.string.nav_google
            NavApp.APPLE -> R.string.nav_apple
            NavApp.WAZE -> R.string.nav_waze
            NavApp.GEO -> R.string.nav_geo
        }
    )

private fun travelName(context: Context, mode: TravelMode) =
    context.getString(if (mode == TravelMode.BICYCLING) R.string.travel_bike else R.string.travel_car)

private fun Context.openAppNotificationSettings() {
    tryToOpen(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}

/** Android 14's own switch for "may show over the lock screen like a call". */
private fun Context.openFullScreenSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        tryToOpen(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
    }
}

private fun Context.tryToOpen(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Not on this device; the screen says what is missing.
    }
}
