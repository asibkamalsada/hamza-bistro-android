package de.hamzabistro.printstation.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.hamzabistro.printstation.core.Logger
import de.hamzabistro.printstation.core.PrinterException
import de.hamzabistro.printstation.core.TicketPrinter
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The shop's 58 mm receipt printer over Bluetooth Low Energy — the CY-BX58D,
 * or any cheap ESC/POS printer that speaks BLE — the way the site's
 * receipt-printer.service.ts reaches it from Chrome:
 *
 *   * the characteristic to write to is found among the ones such printers
 *     use, else the first one that takes writes;
 *   * a ticket goes in 20-byte writes, the most the smallest BLE link
 *     carries, 20 ms apart when the printer does not confirm each one, or a
 *     cheap printer's buffer overflows and the end of the ticket is lost;
 *   * the link stays up between tickets and is made again when it has
 *     dropped, so a printer switched off and on again simply works for the
 *     next ticket.
 *
 * No pairing: the printer is chosen from a scan, and remembered by address.
 */
// Every call below is made after ensureAllowed() checked BLUETOOTH_CONNECT.
@SuppressLint("MissingPermission")
class BlePrinter(
    private val context: Context,
    val address: String,
    private val logger: Logger,
) : TicketPrinter, Closeable {
    private val lock = Mutex()

    @Volatile private var link: Link? = null

    override suspend fun print(ticket: ByteArray) {
        lock.withLock {
            try {
                val current = link?.takeIf { it.connected } ?: connect().also { link = it }
                current.write(ticket)
            } catch (e: Throwable) {
                // Whatever went wrong, the next ticket starts from a fresh link.
                drop()
                if (e is CancellationException || e is PrinterException) throw e
                throw PrinterException(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    /** Lets the printer go, for another device or app to take. */
    override fun close() = drop()

    private fun drop() {
        link?.close()
        link = null
    }

    private suspend fun connect(): Link {
        ensureAllowed()
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw PrinterException("this device has no Bluetooth")
        if (!adapter.isEnabled) throw PrinterException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)

        // The notorious status 133 of Android's Bluetooth stack is more often
        // than not gone at the second try.
        var last: PrinterException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_PAUSE_MS)
            try {
                return open(device)
            } catch (e: PrinterException) {
                last = e
            }
        }
        throw last ?: PrinterException("could not connect")
    }

    private suspend fun open(device: BluetoothDevice): Link {
        val events = GattEvents()
        // On the main thread: some vendors' stacks misbehave otherwise.
        val gatt =
            withContext(Dispatchers.Main) {
                device.connectGatt(context, false, events, BluetoothDevice.TRANSPORT_LE)
            } ?: throw PrinterException("could not connect")
        try {
            val state =
                events.await<GattEvent.State>(CONNECT_TIMEOUT_MS) { true }
                    ?: throw PrinterException("the printer is off or out of range")
            if (state.state != BluetoothProfile.STATE_CONNECTED || state.status != BluetoothGatt.GATT_SUCCESS) {
                throw PrinterException("could not connect (status ${state.status})")
            }
            if (!gatt.discoverServices()) throw PrinterException("could not ask the printer what it offers")
            val found =
                events.await<GattEvent.ServicesFound>(CONNECT_TIMEOUT_MS) { true }
                    ?: throw PrinterException("the printer did not say what it offers")
            if (found.status != BluetoothGatt.GATT_SUCCESS) {
                throw PrinterException("the printer did not say what it offers (status ${found.status})")
            }
            val target = writable(gatt) ?: throw PrinterException("the printer offers nothing to print on")
            logger.info("Printer connected, writing to ${target.uuid}")
            return Link(gatt, events, target)
        } catch (e: Throwable) {
            gatt.disconnect()
            gatt.close()
            throw e
        }
    }

    /**
     * The characteristic the printer's maker meant for printing, where it is
     * a known one; otherwise the first that takes writes — outside the
     * services every BLE device has, one of which would take a ticket as the
     * printer's new name.
     */
    private fun writable(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val candidates =
            gatt.services
                .filter { it.uuid !in STANDARD_SERVICES }
                .flatMap { it.characteristics }
                .filter { it.properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0 }
        return candidates.firstOrNull { it.uuid in KNOWN_WRITE_CHARACTERISTICS } ?: candidates.firstOrNull()
    }

    private fun ensureAllowed() {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw PrinterException("Bluetooth permission missing — open the app")
        }
    }

    /** One connection to the printer, and the characteristic tickets go to. */
    private inner class Link(
        private val gatt: BluetoothGatt,
        private val events: GattEvents,
        private val target: BluetoothGattCharacteristic,
    ) {
        /** A printer that confirms each write is written to that way, as Chrome does. */
        private val confirmed = target.properties and PROPERTY_WRITE != 0

        val connected: Boolean
            get() = events.connected

        suspend fun write(ticket: ByteArray) {
            val type =
                if (confirmed) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            var at = 0
            while (at < ticket.size) {
                val chunk = ticket.copyOfRange(at, minOf(at + CHUNK, ticket.size))
                send(chunk, type)
                // Android says when each write has left, confirmed or not;
                // that is the flow control a write without response has.
                val written =
                    events.await<GattEvent.Written>(WRITE_TIMEOUT_MS) { true }
                        ?: throw PrinterException("the printer did not answer")
                if (written.status != BluetoothGatt.GATT_SUCCESS) {
                    throw PrinterException("the printer refused a write (status ${written.status})")
                }
                if (!confirmed) delay(PACE_MS)
                at += CHUNK
            }
        }

        /** Hands one write to the stack, waiting a moment while it is busy with the last. */
        private suspend fun send(chunk: ByteArray, type: Int) {
            repeat(BUSY_RETRIES) {
                val started =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(target, chunk, type) == BluetoothStatusCodes.SUCCESS
                    } else {
                        target.writeType = type
                        @Suppress("DEPRECATION")
                        target.value = chunk
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(target)
                    }
                if (started) return
                if (!events.connected) throw PrinterException("the printer went away")
                delay(PACE_MS)
            }
            throw PrinterException("the printer's link is stuck")
        }

        fun close() {
            gatt.disconnect()
            gatt.close()
        }
    }

    /** What Android reports about a connection, one event at a time. */
    private sealed interface GattEvent {
        class State(val status: Int, val state: Int) : GattEvent

        class ServicesFound(val status: Int) : GattEvent

        class Written(val status: Int) : GattEvent
    }

    /**
     * The GATT callback, as a queue to wait on. Android calls it on a binder
     * thread; the printer's coroutine takes the events off in order.
     */
    private class GattEvents : BluetoothGattCallback() {
        val events = Channel<GattEvent>(Channel.UNLIMITED)

        @Volatile var connected = false
            private set

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            events.trySend(GattEvent.State(status, newState))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            events.trySend(GattEvent.ServicesFound(status))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            events.trySend(GattEvent.Written(status))
        }

        /**
         * The next event of type [T] that [accept]s, or null after
         * [timeoutMs]. A disconnect while waiting for anything else is the
         * printer gone, and says so at once rather than at the timeout.
         */
        suspend inline fun <reified T : GattEvent> await(timeoutMs: Long, crossinline accept: (T) -> Boolean): T? =
            withTimeoutOrNull(timeoutMs) {
                for (event in events) {
                    if (event is T && accept(event)) return@withTimeoutOrNull event
                    if (event is GattEvent.State && event.state == BluetoothProfile.STATE_DISCONNECTED) {
                        throw PrinterException("the printer went away (status ${event.status})")
                    }
                }
                null
            }
    }

    private companion object {
        const val CHUNK = 20
        const val PACE_MS = 20L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        const val CONNECT_ATTEMPTS = 2
        const val RETRY_PAUSE_MS = 500L
        const val BUSY_RETRIES = 50

        const val PROPERTY_WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        const val PROPERTY_WRITE_NO_RESPONSE = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        /** Generic Access, Generic Attribute, Device Information. */
        val STANDARD_SERVICES =
            setOf("1800", "1801", "180a").map { UUID.fromString("0000$it-0000-1000-8000-00805f9b34fb") }.toSet()

        /**
         * The characteristics cheap BLE receipt printers take ESC/POS on —
         * BLE_PRINTER_SERVICES in the site's receipt-printer.service.ts.
         */
        val KNOWN_WRITE_CHARACTERISTICS =
            listOf(
                    "00002af1-0000-1000-8000-00805f9b34fb",
                    "0000ff02-0000-1000-8000-00805f9b34fb",
                    "0000ffe1-0000-1000-8000-00805f9b34fb",
                    "0000ae01-0000-1000-8000-00805f9b34fb",
                    "49535343-8841-43f4-a8d4-ecbe34729bb3",
                    "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
                )
                .map(UUID::fromString)
                .toSet()
    }
}
