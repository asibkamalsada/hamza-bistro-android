package de.hamzabistro.printstation.station

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R

/**
 * Starts the station after a reboot and after the app was updated, without
 * anybody opening it — if it was printing before.
 *
 * Android sends BOOT_COMPLETED once the tablet has been unlocked for the
 * first time since it started: before that, the encrypted session cannot be
 * read. A tablet without a screen lock gets there by itself.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = (context.applicationContext as PrintStationApp).graph
        if (!PrintStationService.ready(graph)) return
        try {
            PrintStationService.start(context, skipWaiting = false)
        } catch (e: Exception) {
            graph.logger.warn("Could not start the print station after ${intent.action}", e)
            StationNotifications.stopped(
                context,
                context.getString(R.string.stopped_could_not_start, e.message ?: e.javaClass.simpleName),
            )
        }
    }
}
