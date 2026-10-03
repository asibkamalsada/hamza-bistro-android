package de.hamzabistro.printstation.station

import android.content.Context
import de.hamzabistro.printstation.core.AlarmSettings
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.NavApp
import de.hamzabistro.printstation.core.NewOrderAlarm
import de.hamzabistro.printstation.core.TravelMode
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the signed-in account is to this app. */
enum class Role {
    /** Staff: the queue, the alarm, and printing if this device has the printer. */
    STAFF,

    /** A print account: printing and nothing else. */
    PRINTER,
}

/** The sound an alarm makes. The first three are the site's own, made here rather than shipped. */
enum class AlarmSound {
    BEEPS,
    BELL,
    SIREN,

    /** The alarm sound this device's clock uses. */
    DEVICE,
}

/**
 * How this device works the queue and how it rings. Kept on the device,
 * because it is about the device: the kitchen tablet on the counter and a
 * driver's phone in a jacket pocket want different things.
 */
data class DevicePrefs(
    val role: Role? = null,
    /** On shift: the service runs, the alarm rings, and the site lists this device. */
    val onShift: Boolean = false,
    /** How "Wer von Bestellungen erfährt" names this device. */
    val label: String = "",
    val alarm: AlarmSettings = AlarmSettings(),
    val sound: AlarmSound = AlarmSound.BEEPS,
    /** Turns the alarm volume all the way up while it rings, and back after. */
    val fullVolume: Boolean = true,
    val vibrate: Boolean = true,
    /** The minutes offered as buttons when accepting, besides the estimate. */
    val etaLadder: List<Int> = Eta.LADDER,
    /** Seconds between tapping a step and sending it, during which it can be taken back. */
    val undoSeconds: Int = 5,
    val navApp: NavApp = NavApp.GOOGLE,
    /** The shop delivers by bike. */
    val travelMode: TravelMode = TravelMode.BICYCLING,
    /** Keeps the screen on while the queue is open on it. */
    val keepAwake: Boolean = true,
    /** A driver's phone: the queue opens on "Fahrer", the bags to take and the stops to ride. */
    val driver: Boolean = false,
)

/**
 * What the app remembers that is not secret: which printer, whether it
 * prints, its id as a print station and on-shift device, and [DevicePrefs].
 */
class StationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("station", Context.MODE_PRIVATE)

    private val _device = MutableStateFlow(read())

    /** The device's settings, as they change. */
    val device: StateFlow<DevicePrefs> = _device.asStateFlow()

    /**
     * This device's id, as a print station and as a device on shift: random,
     * made once and kept, so the same tablet is the same one across restarts
     * and sign-ins.
     */
    val stationId: String
        @Synchronized
        get() =
            prefs.getString(STATION_ID, null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString(STATION_ID, it).apply() }

    /** Whether accepted orders print by themselves here. */
    var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, false)
        set(value) = prefs.edit().putBoolean(ENABLED, value).apply()

    /**
     * Whether a "Tütenzettel" (hamza-bistro-web#91) follows each ticket
     * printed here, by itself or by hand. The printer's, not the account's:
     * kept with the printer, through a sign-out and "reset settings".
     */
    var bagSlip: Boolean
        get() = prefs.getBoolean(BAG_SLIP, false)
        set(value) = prefs.edit().putBoolean(BAG_SLIP, value).apply()

    val printer: ChosenPrinter?
        get() =
            prefs.getString(PRINTER_ADDRESS, null)?.let {
                ChosenPrinter(it, prefs.getString(PRINTER_NAME, null))
            }

    fun choosePrinter(printer: ChosenPrinter) {
        prefs.edit().putString(PRINTER_ADDRESS, printer.address).putString(PRINTER_NAME, printer.name).apply()
    }

    @Synchronized
    fun update(change: (DevicePrefs) -> DevicePrefs) {
        val next = change(_device.value)
        write(next)
        _device.value = next
    }

    /** Signed out: what was the account's goes, what was the device's stays. */
    fun forgetAccount() = update { it.copy(role = null, onShift = false) }

    private fun read(): DevicePrefs {
        val d = DevicePrefs()
        val a = d.alarm
        return DevicePrefs(
            role = enumOr<Role>(prefs.getString(ROLE, null), null),
            onShift = prefs.getBoolean(ON_SHIFT, d.onShift),
            label = prefs.getString(LABEL, d.label) ?: d.label,
            alarm =
                AlarmSettings(
                    newOrders = enumOr(prefs.getString(NEW_ORDERS, null), a.newOrders)!!,
                    cookNow = prefs.getBoolean(COOK_NOW, a.cookNow),
                    printer = prefs.getBoolean(PRINTER_ALARM, a.printer),
                    unprinted = prefs.getBoolean(UNPRINTED_ALARM, a.unprinted),
                    connection = prefs.getBoolean(CONNECTION_ALARM, a.connection),
                    packed = if (prefs.contains(PACKED_ALARM)) prefs.getBoolean(PACKED_ALARM, false) else a.packed,
                    quietFrom = time(QUIET_FROM, a.quietFrom),
                    quietTo = time(QUIET_TO, a.quietTo),
                    silenceSeconds = prefs.getInt(SILENCE, a.silenceSeconds).takeIf { it in SILENCE_CHOICES } ?: a.silenceSeconds,
                ),
            sound = enumOr(prefs.getString(SOUND, null), d.sound)!!,
            fullVolume = prefs.getBoolean(FULL_VOLUME, d.fullVolume),
            vibrate = prefs.getBoolean(VIBRATE, d.vibrate),
            etaLadder =
                prefs.getString(ETA_LADDER, null)
                    ?.split(',')
                    ?.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in Eta.CHOICES } }
                    ?.distinct()
                    ?.sorted()
                    ?.takeIf { it.isNotEmpty() } ?: d.etaLadder,
            undoSeconds = prefs.getInt(UNDO, d.undoSeconds).takeIf { it in UNDO_CHOICES } ?: d.undoSeconds,
            navApp = enumOr(prefs.getString(NAV_APP, null), d.navApp)!!,
            travelMode = enumOr(prefs.getString(TRAVEL_MODE, null), d.travelMode)!!,
            keepAwake = prefs.getBoolean(KEEP_AWAKE, d.keepAwake),
            driver = prefs.getBoolean(DRIVER, d.driver),
        )
    }

    private fun write(p: DevicePrefs) {
        prefs.edit()
            .putString(ROLE, p.role?.name)
            .putBoolean(ON_SHIFT, p.onShift)
            .putString(LABEL, p.label)
            .putString(NEW_ORDERS, p.alarm.newOrders.name)
            .putBoolean(COOK_NOW, p.alarm.cookNow)
            .putBoolean(PRINTER_ALARM, p.alarm.printer)
            .putBoolean(UNPRINTED_ALARM, p.alarm.unprinted)
            .putBoolean(CONNECTION_ALARM, p.alarm.connection)
            // Not chosen is no key at all: the device's purpose decides.
            .run { p.alarm.packed?.let { putBoolean(PACKED_ALARM, it) } ?: remove(PACKED_ALARM) }
            .putString(QUIET_FROM, p.alarm.quietFrom?.toString() ?: NEVER)
            .putString(QUIET_TO, p.alarm.quietTo?.toString() ?: NEVER)
            .putInt(SILENCE, p.alarm.silenceSeconds)
            .putString(SOUND, p.sound.name)
            .putBoolean(FULL_VOLUME, p.fullVolume)
            .putBoolean(VIBRATE, p.vibrate)
            .putString(ETA_LADDER, p.etaLadder.joinToString(","))
            .putInt(UNDO, p.undoSeconds)
            .putString(NAV_APP, p.navApp.name)
            .putString(TRAVEL_MODE, p.travelMode.name)
            .putBoolean(KEEP_AWAKE, p.keepAwake)
            .putBoolean(DRIVER, p.driver)
            .apply()
    }

    /** A stored time; [NEVER] for none, a missing key for the default. */
    private fun time(key: String, fallback: LocalTime?): LocalTime? {
        val text = prefs.getString(key, null) ?: return fallback
        if (text == NEVER) return null
        return runCatching { LocalTime.parse(text) }.getOrDefault(fallback)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T?): T? =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    companion object {
        val SILENCE_CHOICES = listOf(30, 60, 120)
        val UNDO_CHOICES = listOf(0, 5, 10)

        private const val STATION_ID = "station_id"
        private const val ENABLED = "enabled"
        private const val BAG_SLIP = "bag_slip"
        private const val PRINTER_ADDRESS = "printer_address"
        private const val PRINTER_NAME = "printer_name"
        private const val ROLE = "role"
        private const val ON_SHIFT = "on_shift"
        private const val LABEL = "label"
        private const val NEW_ORDERS = "alarm_new_orders"
        private const val COOK_NOW = "alarm_cook_now"
        private const val PRINTER_ALARM = "alarm_printer"
        private const val UNPRINTED_ALARM = "alarm_unprinted"
        private const val CONNECTION_ALARM = "alarm_connection"
        private const val PACKED_ALARM = "alarm_packed"
        private const val QUIET_FROM = "quiet_from"
        private const val QUIET_TO = "quiet_to"
        private const val SILENCE = "silence_seconds"
        private const val SOUND = "sound"
        private const val FULL_VOLUME = "full_volume"
        private const val VIBRATE = "vibrate"
        private const val ETA_LADDER = "eta_ladder"
        private const val UNDO = "undo_seconds"
        private const val NAV_APP = "nav_app"
        private const val TRAVEL_MODE = "travel_mode"
        private const val KEEP_AWAKE = "keep_awake"
        private const val DRIVER = "driver"
        private const val NEVER = "never"
    }
}

/** The printer chosen from the scan, by its Bluetooth address. */
data class ChosenPrinter(val address: String, val name: String?)

/**
 * The alarm settings this device decides by: "Bestellung fertig", until
 * somebody chooses here, as what the device is for says — on for a driver's
 * phone ("Fahrer-Handy", [DevicePrefs.driver]), off for the kitchen tablet.
 */
val DevicePrefs.alarmForDevice: AlarmSettings
    get() = alarm.forDevice(driver = driver)

/** The alarm mode as the site's device list names it. */
val NewOrderAlarm.wire: String
    get() =
        when (this) {
            NewOrderAlarm.LOOP -> "loop"
            NewOrderAlarm.ONCE -> "once"
            NewOrderAlarm.OFF -> "off"
        }
