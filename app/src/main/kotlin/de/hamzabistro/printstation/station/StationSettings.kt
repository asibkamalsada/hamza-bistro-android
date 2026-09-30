package de.hamzabistro.printstation.station

import android.content.Context
import java.util.UUID

/**
 * What the station remembers that is not secret: which printer, whether it
 * prints, and its name as a print station.
 */
class StationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("station", Context.MODE_PRIVATE)

    /**
     * This tablet's id as a print station: random, made once and kept, so the
     * same tablet is the same station across restarts and sign-ins.
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

    val printer: ChosenPrinter?
        get() =
            prefs.getString(PRINTER_ADDRESS, null)?.let {
                ChosenPrinter(it, prefs.getString(PRINTER_NAME, null))
            }

    fun choosePrinter(printer: ChosenPrinter) {
        prefs.edit().putString(PRINTER_ADDRESS, printer.address).putString(PRINTER_NAME, printer.name).apply()
    }

    private companion object {
        const val STATION_ID = "station_id"
        const val ENABLED = "enabled"
        const val PRINTER_ADDRESS = "printer_address"
        const val PRINTER_NAME = "printer_name"
    }
}

/** The printer chosen from the scan, by its Bluetooth address. */
data class ChosenPrinter(val address: String, val name: String?)
