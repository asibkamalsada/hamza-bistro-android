package de.hamzabistro.printstation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.station.StationNotifications
import de.hamzabistro.printstation.station.StationState

/**
 * The one screen: sign in, choose the printer, switch printing on. Used once
 * when the tablet is set up, and afterwards only to look at how it is going.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.message?.let { Notice(it, onDismiss = viewModel::dismissMessage) }
            AccountSection(state, viewModel)
            if (state.account != null) {
                PrinterSection(state, viewModel)
                StationSection(state, viewModel)
                BackgroundSection(state)
            }
        }
    }
}

@Composable
private fun Notice(message: Message, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss)) {
        Text(
            text = message.text,
            color = if (message.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun AccountSection(state: UiState, viewModel: MainViewModel) {
    Section(stringResource(R.string.section_account)) {
        val account = state.account
        if (account != null) {
            Text(stringResource(R.string.signed_in_as, account.email ?: account.userId))
            OutlinedButton(onClick = viewModel::signOut, enabled = !state.busy) {
                Text(stringResource(R.string.sign_out))
            }
            return@Section
        }
        Text(stringResource(R.string.sign_in_hint), style = MaterialTheme.typography.bodyMedium)
        var email by rememberSaveable { mutableStateOf("") }
        // Not saveable: a password is not written into the saved state.
        var password by remember { mutableStateOf("") }
        var token by remember { mutableStateOf<String?>(null) }
        val captcha = viewModel.captchaSiteKey.isNotEmpty()
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (captcha) {
            Captcha(
                siteKey = viewModel.captchaSiteKey,
                origin = viewModel.captchaOrigin,
                lang = AppGraph.TICKET_LANG,
                round = state.captchaRound,
                onToken = { token = it },
            )
        }
        Button(
            onClick = {
                viewModel.signIn(email, password, token)
                password = ""
            },
            enabled = !state.busy && email.isNotBlank() && password.isNotEmpty() && (!captcha || token != null),
        ) {
            Text(stringResource(R.string.sign_in))
        }
    }
}

@Composable
private fun PrinterSection(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val askForBluetooth =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) viewModel.scan()
        }
    Section(stringResource(R.string.section_printer)) {
        val printer = state.printer
        Text(
            if (printer == null) stringResource(R.string.printer_none)
            else stringResource(R.string.printer_chosen, printer.name ?: printer.address)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val missing = BLUETOOTH.filterNot { context.granted(it) }
                    if (missing.isEmpty()) viewModel.scan() else askForBluetooth.launch(missing.toTypedArray())
                },
                enabled = !state.scanning,
            ) {
                Text(stringResource(R.string.printer_scan))
            }
            if (printer != null) {
                OutlinedButton(onClick = viewModel::testPrint, enabled = !state.busy) {
                    Text(stringResource(R.string.printer_test))
                }
            }
            if (state.scanning) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        if (state.scanning || state.found.isNotEmpty()) {
            Text(stringResource(R.string.printer_pick), style = MaterialTheme.typography.bodyMedium)
            state.found.forEach { found ->
                TextButton(onClick = { viewModel.choose(found) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${found.name ?: stringResource(R.string.printer_unnamed)} · ${found.address}")
                }
            }
        }
    }
}

@Composable
private fun StationSection(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val askToRun =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Without notifications it still prints; without Bluetooth it cannot.
            if (granted[Manifest.permission.BLUETOOTH_CONNECT] != false) viewModel.setEnabled(true)
        }
    Section(stringResource(R.string.section_station)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.station_switch), modifier = Modifier.weight(1f))
            Switch(
                checked = state.enabled,
                enabled = !state.busy && state.printer != null,
                onCheckedChange = { on ->
                    if (!on) {
                        viewModel.setEnabled(false)
                        return@Switch
                    }
                    val missing = RUN.filterNot { context.granted(it) }
                    if (missing.isEmpty()) viewModel.setEnabled(true) else askToRun.launch(missing.toTypedArray())
                },
            )
        }
        Text(stringResource(R.string.station_one_printer), style = MaterialTheme.typography.bodySmall)
        val status =
            when (val station = state.station) {
                StationState.Stopped -> stringResource(R.string.station_stopped)
                StationState.SignedOut -> stringResource(R.string.stopped_signed_out)
                is StationState.Failed -> station.reason
                is StationState.Running -> StationNotifications.summary(context, station.status)
            }
        val failing = (state.station as? StationState.Running)?.status?.problem != null || state.station is StationState.Failed
        Text(status, color = if (failing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun BackgroundSection(state: UiState) {
    if (!state.batteryOptimised) return
    val context = LocalContext.current
    Section(stringResource(R.string.section_background)) {
        Text(stringResource(R.string.battery_hint))
        Button(onClick = { context.askToIgnoreBatteryOptimisation() }) {
            Text(stringResource(R.string.battery_button))
        }
    }
}

/**
 * Android's own dialog that takes the app out of battery optimisation — what
 * dontkillmyapp.com asks for on every make of phone. An app on Google Play
 * may not ask this way; one installed from an APK for a single job may.
 */
@SuppressLint("BatteryLife")
private fun Context.askToIgnoreBatteryOptimisation() {
    startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    )
}

private fun Context.granted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private val BLUETOOTH = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
/** Notifications became a permission in Android 13; before that there is nothing to ask for. */
private val RUN =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    }
