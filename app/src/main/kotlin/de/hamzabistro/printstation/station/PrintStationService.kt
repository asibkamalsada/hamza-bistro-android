package de.hamzabistro.printstation.station

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.SignedOutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Prints every accepted order with the app closed and the screen off.
 *
 * A foreground service of type connectedDevice — what Android 14 and later
 * require of a service that keeps a Bluetooth link — with the permanent
 * notification that goes with it. Started from the app when printing is
 * switched on, after a reboot, and after an update; Android starts it again
 * by itself if it ever has to kill it (START_STICKY).
 */
class PrintStationService : LifecycleService() {
    private val graph: AppGraph
        get() = (application as PrintStationApp).graph

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startForeground(
                StationNotifications.RUNNING_ID,
                StationNotifications.running(this, null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // BLUETOOTH_CONNECT taken away in the settings, or Android not
            // letting a service start from where this was asked.
            fail(getString(R.string.stopped_could_not_start, e.message ?: e.javaClass.simpleName))
            return START_NOT_STICKY
        }
        StationNotifications.clearStopped(this)
        val skipWaiting = intent?.getBooleanExtra(EXTRA_SKIP_WAITING, false) == true
        if (job?.isActive != true) job = lifecycleScope.launch { run(skipWaiting) }
        return START_STICKY
    }

    private suspend fun run(skipWaiting: Boolean) {
        val chosen = graph.settings.printer ?: return fail(getString(R.string.stopped_no_printer))
        val printer = graph.printer(chosen.address)
        val station = graph.station(printer)
        holdCpu()
        try {
            coroutineScope {
                launch {
                    station.status.collect {
                        graph.stationState.value = StationState.Running(it)
                        getSystemService(NotificationManager::class.java)
                            .notify(StationNotifications.RUNNING_ID, StationNotifications.running(this@PrintStationService, it))
                    }
                }
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
            stopSelf()
        } finally {
            releaseCpu()
            // The printer takes one connection at a time: free it for the
            // site, or another app, while this station is not printing.
            printer.close()
        }
    }

    override fun onDestroy() {
        job?.cancel()
        releaseCpu()
        if (graph.stationState.value is StationState.Running) graph.stationState.value = StationState.Stopped
        super.onDestroy()
    }

    private fun fail(reason: String) {
        graph.logger.warn("Print station not started: $reason")
        graph.stationState.value = StationState.Failed(reason)
        StationNotifications.stopped(this, reason)
        stopSelf()
    }

    /**
     * The CPU stays awake while the station runs, so an order accepted with
     * the screen off prints in seconds rather than whenever Android next
     * wakes. The tablet is on mains power beside the printer; this is what
     * that power is for. Released the moment the station stops.
     */
    // Held on purpose for as long as the service runs; released in finally and onDestroy.
    @SuppressLint("WakelockTimeout")
    private fun holdCpu() {
        if (wakeLock?.isHeld == true) return
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HamzaBistro:PrintStation")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    private fun releaseCpu() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        /** Switching printing on: leave what was accepted before to others, as the site's switch does. */
        private const val EXTRA_SKIP_WAITING = "skip_waiting"

        fun start(context: Context, skipWaiting: Boolean) {
            context.startForegroundService(
                Intent(context, PrintStationService::class.java).putExtra(EXTRA_SKIP_WAITING, skipWaiting)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrintStationService::class.java))
        }

        /** Everything the station needs to run: switched on, signed in, a printer chosen. */
        fun ready(graph: AppGraph): Boolean =
            graph.settings.enabled && graph.sessions.account.value != null && graph.settings.printer != null
    }
}
