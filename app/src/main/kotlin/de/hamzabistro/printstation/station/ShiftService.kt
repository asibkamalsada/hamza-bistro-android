package de.hamzabistro.printstation.station

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.alarm.AlarmController
import de.hamzabistro.printstation.core.QueueState
import de.hamzabistro.printstation.core.SignedOutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * The part of the app that runs with the screen off: the shift (the queue
 * and its alarm) and the print station, either or both, in one foreground
 * service with one permanent notification.
 *
 *   * On shift — a staff account that switched it on — it reads the queue
 *     the way /orders does and rings when an order comes in, in a loop,
 *     over the lock screen, until somebody answers it anywhere. Type
 *     specialUse: none of Android's other types is "a till that rings".
 *   * Printing — switched on, a printer chosen — it prints every accepted
 *     order, as the print station always has. Type connectedDevice, which
 *     Android 14 and later require of a service that keeps a Bluetooth link.
 *
 * Started from the app, after a reboot and after an update; Android starts
 * it again by itself if it ever has to kill it (START_STICKY). Asked again
 * whenever what it should run changes, it starts or stops each part.
 */
class ShiftService : LifecycleService() {
    private val graph: AppGraph
        get() = (application as PrintStationApp).graph

    private var shiftJob: Job? = null
    private var printJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** What each part shows in the permanent notification. */
    private val queueShown = MutableStateFlow<QueueState?>(null)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        var parts = wanted(graph)
        if (!parts.any) {
            // Asked to start as a foreground service and then found nothing
            // to do (switched off in the moment between): Android still
            // wants the notification before the service may stop.
            runCatching {
                startForeground(StationNotifications.RUNNING_ID, notification(Parts(shift = true, printing = false)), types(Parts(shift = true, printing = false)))
            }
            stopEverything()
            return START_NOT_STICKY
        }
        try {
            startForeground(StationNotifications.RUNNING_ID, notification(parts), types(parts))
        } catch (e: Exception) {
            // BLUETOOTH_CONNECT taken away in the settings, or Android not
            // letting a service start from where this was asked.
            val reason = getString(R.string.stopped_could_not_start, e.message ?: e.javaClass.simpleName)
            if (!(parts.shift && parts.printing)) {
                fail(reason)
                return START_NOT_STICKY
            }
            // The shift does not need Bluetooth: it rings without the printer.
            parts = parts.copy(printing = false)
            try {
                startForeground(StationNotifications.RUNNING_ID, notification(parts), types(parts))
            } catch (again: Exception) {
                fail(reason)
                return START_NOT_STICKY
            }
            printJob?.cancel()
            graph.stationState.value = StationState.Failed(reason)
            StationNotifications.stopped(this, reason)
        }
        holdCpu()

        if (parts.shift && shiftJob?.isActive != true) shiftJob = lifecycleScope.launch { shift() }
        if (!parts.shift) {
            shiftJob?.cancel()
            shiftJob = null
            queueShown.value = null
        }

        if (parts.printing) {
            StationNotifications.clearStopped(this)
            val skipWaiting = intent?.getBooleanExtra(EXTRA_SKIP_WAITING, false) == true
            // Another printer chosen: the running station lets go of the old one.
            if (intent?.getBooleanExtra(EXTRA_RESTART_PRINTING, false) == true) printJob?.cancel()
            if (printJob?.isActive != true) printJob = lifecycleScope.launch { print(skipWaiting) }
        } else {
            printJob?.cancel()
            printJob = null
        }

        if (!started) {
            started = true
            lifecycleScope.launch {
                combine(queueShown, graph.stationState, graph.settings.device) { queue, station, _ -> queue to station }
                    .collect { (queue, station) ->
                        val now = wanted(graph)
                        if (!now.any) return@collect
                        getSystemService(NotificationManager::class.java)
                            .notify(StationNotifications.RUNNING_ID, notification(now, queue, station))
                    }
            }
        }
        return START_STICKY
    }

    private var started = false

    private fun types(parts: Parts): Int {
        // Before Android 14 a type is not asked for, and specialUse does not exist.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        var types = 0
        if (parts.shift) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (parts.printing) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return types
    }

    private fun notification(parts: Parts, queue: QueueState? = queueShown.value, station: StationState = graph.stationState.value) =
        StationNotifications.running(
            this,
            queue,
            onShift = parts.shift,
            printing = (station as? StationState.Running)?.status,
            printingOn = parts.printing,
        )

    /**
     * The queue and its alarm, until the shift ends here — and, beside them,
     * a look once a day for a newer build. That look never throws, and runs
     * in a job of its own, so GitHub can do nothing to the alarm.
     */
    private suspend fun shift() {
        coroutineScope {
            val queue = graph.liveQueue.shareIn(this, SharingStarted.Eagerly, replay = 1)
            launch { queue.collect { queueShown.value = it } }
            launch {
                try {
                    graph.updates.daily()
                } catch (e: Exception) {
                    // It catches its own failures; should one slip through, the shift goes on.
                    if (e is CancellationException) throw e
                    graph.logger.warn("The update check stopped", e)
                }
            }
            AlarmController(this@ShiftService, graph).run(queue)
        }
    }

    private suspend fun print(skipWaiting: Boolean) {
        val chosen = graph.settings.printer ?: return fail(getString(R.string.stopped_no_printer))
        val printer = graph.printer(chosen.address)
        val station = graph.station(printer)
        try {
            coroutineScope {
                launch { station.status.collect { graph.stationState.value = StationState.Running(it) } }
                if (skipWaiting) {
                    try {
                        station.skipWaiting()
                    } catch (e: Exception) {
                        if (e is CancellationException || e is SignedOutException) throw e
                        // Offline for a moment: what was accepted before
                        // prints after all, which beats not printing.
                        graph.logger.warn("Could not leave the waiting orders to others", e)
                    }
                }
                station.run(graph.realtime.changes())
            }
        } catch (e: SignedOutException) {
            graph.settings.enabled = false
            graph.stationState.value = StationState.SignedOut
            StationNotifications.stopped(this, getString(R.string.stopped_signed_out))
            update(this)
        } finally {
            if (graph.stationState.value is StationState.Running) graph.stationState.value = StationState.Stopped
            // The printer takes one connection at a time: free it for the
            // site, or another app, while this device is not printing.
            printer.close()
        }
    }

    override fun onDestroy() {
        shiftJob?.cancel()
        printJob?.cancel()
        releaseCpu()
        if (graph.stationState.value is StationState.Running) graph.stationState.value = StationState.Stopped
        super.onDestroy()
    }

    private fun stopEverything() {
        shiftJob?.cancel()
        printJob?.cancel()
        releaseCpu()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(reason: String) {
        graph.logger.warn("Not started: $reason")
        graph.stationState.value = StationState.Failed(reason)
        StationNotifications.stopped(this, reason)
        printJob?.cancel()
        if (shiftJob?.isActive != true) stopEverything()
    }

    /**
     * The CPU stays awake while the service runs, so an order that arrives
     * with the screen off rings — and an accepted one prints — in seconds
     * rather than whenever Android next wakes. Released the moment it stops.
     */
    // Held on purpose for as long as the service runs; released in stopEverything and onDestroy.
    @SuppressLint("WakelockTimeout")
    private fun holdCpu() {
        if (wakeLock?.isHeld == true) return
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HamzaBistro:Shift")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    private fun releaseCpu() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** What the service should be running. */
    data class Parts(val shift: Boolean, val printing: Boolean) {
        val any: Boolean
            get() = shift || printing
    }

    companion object {
        /** Switching printing on: leave what was accepted before to others, as the site's switch does. */
        private const val EXTRA_SKIP_WAITING = "skip_waiting"
        private const val EXTRA_RESTART_PRINTING = "restart_printing"

        /** Signed in, and on shift as staff; signed in, switched on and a printer chosen. */
        fun wanted(graph: AppGraph): Parts {
            if (graph.sessions.account.value == null) return Parts(shift = false, printing = false)
            val device = graph.settings.device.value
            return Parts(
                shift = device.role == Role.STAFF && device.onShift,
                printing = graph.settings.enabled && graph.settings.printer != null,
            )
        }

        /**
         * Brings the service in line with the settings: starts it, changes
         * what it runs, or stops it. [skipWaiting] is for printing just
         * switched on.
         */
        fun update(context: Context, skipWaiting: Boolean = false, restartPrinting: Boolean = false) {
            val graph = (context.applicationContext as PrintStationApp).graph
            val intent =
                Intent(context, ShiftService::class.java)
                    .putExtra(EXTRA_SKIP_WAITING, skipWaiting)
                    .putExtra(EXTRA_RESTART_PRINTING, restartPrinting)
            if (wanted(graph).any) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
