package de.hamzabistro.printstation.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import de.hamzabistro.printstation.core.PrinterException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A Bluetooth LE device nearby, as the scan heard it. */
data class FoundPrinter(val address: String, val name: String?, val rssi: Int)

/**
 * The list the printer is chosen from: every BLE device nearby, not a
 * filtered one — cheap printers seldom announce the service they print on,
 * so a filter would hide them. Named devices first, the closest first.
 */
class PrinterScanner(private val context: Context) {
    /** Scans for as long as it is collected. Needs BLUETOOTH_SCAN (and BLUETOOTH_CONNECT for names). */
    // Checked below; lint cannot see through the check into the callback.
    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<FoundPrinter>> = callbackFlow {
        if (!granted(Manifest.permission.BLUETOOTH_SCAN)) throw PrinterException("Bluetooth permission missing")
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw PrinterException("this device has no Bluetooth")
        if (!adapter.isEnabled) throw PrinterException("Bluetooth is off")
        val scanner = adapter.bluetoothLeScanner ?: throw PrinterException("Bluetooth is off")
        val canName = granted(Manifest.permission.BLUETOOTH_CONNECT)
        val found = LinkedHashMap<String, FoundPrinter>()

        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.scanRecord?.deviceName ?: if (canName) result.device.name else null
                    found[result.device.address] = FoundPrinter(result.device.address, name, result.rssi)
                    trySend(
                        found.values.sortedWith(
                            compareBy<FoundPrinter> { it.name == null }.thenByDescending { it.rssi }
                        )
                    )
                }

                override fun onScanFailed(errorCode: Int) {
                    close(PrinterException("the scan failed ($errorCode)"))
                }
            }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
