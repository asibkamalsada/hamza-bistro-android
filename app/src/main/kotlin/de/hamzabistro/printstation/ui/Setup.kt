package de.hamzabistro.printstation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.station.Role
import de.hamzabistro.printstation.station.StationSettings

/** Something Android has to allow before this device rings, and prints, as it should — in the order it is asked for. */
enum class Need(@StringRes val title: Int, @StringRes val hint: Int) {
    /** The alarm over the lock screen and every chime. Android 13 and later ask for it. */
    NOTIFICATIONS(R.string.setup_notifications, R.string.setup_notifications_hint),

    /** "Geräte in der Nähe": Bluetooth, for the receipt printer. Asked for in one go with notifications. */
    NEARBY(R.string.setup_nearby, R.string.setup_nearby_hint),

    /** Out of battery optimisation, so that Android does not stop the shift or the printing. */
    BACKGROUND(R.string.setup_background, R.string.setup_background_hint),

    /** Android 14's "full screen over the lock screen", like an incoming call: a settings page of its own. */
    FULL_SCREEN(R.string.setup_full_screen, R.string.setup_full_screen_hint),
}

/** What this device has allowed, and whether the start-up screen asks for the rest. */
object Setup {
    val NEARBY_PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    /**
     * What a device needs allowed: everything, but Bluetooth only where there
     * is any, and the full screen not for a print account, which never rings.
     */
    fun needs(context: Context, role: Role?): List<Need> =
        Need.entries.filter {
            when (it) {
                Need.NEARBY -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                Need.FULL_SCREEN -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && role != Role.PRINTER
                else -> true
            }
        }

    /** What of [needs] Android does not allow now. */
    fun missing(context: Context, role: Role?): List<Need> = needs(context, role).filterNot { allowed(context, it) }

    fun allowed(context: Context, need: Need): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return when (need) {
            Need.NOTIFICATIONS -> notifications.areNotificationsEnabled()
            Need.NEARBY -> NEARBY_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            Need.BACKGROUND -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            Need.FULL_SCREEN ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || notifications.canUseFullScreenIntent()
        }
    }

    /**
     * Whether the start-up screen comes up as the app opens: on an install's
     * first start for anything missing, after that only for what the alarm
     * cannot do without — "Geräte in der Nähe" alone, on a driver's phone
     * with no printer, does not bring it back.
     */
    fun due(context: Context, settings: StationSettings): Boolean {
        val missing = missing(context, settings.device.value.role)
        return if (settings.permissionsAsked) missing.any { it != Need.NEARBY } else missing.isNotEmpty()
    }
}

/**
 * The first screen after an install — or when the alarm has lost something it
 * needs — so that nobody has to go looking: it asks for everything at once,
 * one after the other (Android's own dialogs for notifications, nearby
 * devices and the battery, its settings page for the full screen), and goes
 * on to the app by itself once all of it is allowed. What is still missing
 * stays listed with a button of its own; "Trotzdem weiter" goes on without.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(role: Role?, onDone: () -> Unit) {
    val context = LocalContext.current
    // Android's dialog or page is open; its answer moves on to the next.
    var waiting by rememberSaveable { mutableStateOf(false) }
    // Allowed in Android's dialogs and settings, not here: looked at again on every return.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        checks++
        waiting = false
    }
    val needs = remember(role) { Setup.needs(context, role) }
    val missing = remember(checks, role) { Setup.missing(context, role) }
    // Asked once already: Android answers a second ask with no without a
    // word, so the button opens the settings instead.
    var asked by rememberSaveable { mutableStateOf(setOf<Need>()) }
    // Going through everything missing, one after the other, from the start.
    var chaining by rememberSaveable { mutableStateOf(true) }
    val answered = {
        waiting = false
        checks++
    }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answered() }
    val page = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { answered() }

    fun request(permissions: List<String>) {
        waiting = true
        dialog.launch(permissions.toTypedArray())
    }

    fun open(intent: Intent) {
        waiting = true
        try {
            page.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // Not on this device: on to the next; the list says what is missing.
            answered()
        }
    }

    fun ask(need: Need) {
        val again = need in asked
        asked = asked + need
        when (need) {
            Need.NOTIFICATIONS ->
                if (!again &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    // Nearby devices in the same go: Android asks one after the other.
                    val nearby = Need.NEARBY in missing && Need.NEARBY !in asked
                    if (nearby) asked = asked + Need.NEARBY
                    request(listOf(Manifest.permission.POST_NOTIFICATIONS) + if (nearby) Setup.NEARBY_PERMISSIONS else emptyList())
                } else {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            Need.NEARBY ->
                if (!again) request(Setup.NEARBY_PERMISSIONS)
                else open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            Need.BACKGROUND -> open(batteryDialog(context))
            Need.FULL_SCREEN ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                }
        }
    }

    LaunchedEffect(missing, chaining, waiting) {
        if (!chaining || waiting) return@LaunchedEffect
        val next = missing.firstOrNull { it !in asked }
        if (next == null) chaining = false else ask(next)
    }
    // Everything allowed: on to the app, without a tap.
    LaunchedEffect(missing) { if (missing.isEmpty()) onDone() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.setup_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_intro))
            for (need in needs) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(need.title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(need.hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (need in missing) {
                            Button(onClick = { ask(need) }) { Text(stringResource(R.string.setup_allow)) }
                        } else {
                            Text(stringResource(R.string.setup_allowed), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (missing.isNotEmpty()) Text(stringResource(R.string.setup_missing), color = MaterialTheme.colorScheme.error)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onDone) { Text(stringResource(R.string.setup_continue_anyway)) }
            }
            Text(stringResource(R.string.battery_hint_staff), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Android's own dialog that takes the app out of battery optimisation — what
 * dontkillmyapp.com asks for on every make of phone. An app on Google Play
 * may not ask this way; one installed from an APK for a single job may.
 */
@SuppressLint("BatteryLife")
fun batteryDialog(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
