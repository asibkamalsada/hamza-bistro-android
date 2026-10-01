package de.hamzabistro.printstation.station

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R

/**
 * Starts the shift and the print station again after a reboot and after the
 * app was updated, without anybody opening it — whatever of the two was
 * running before.
 *
 * Android sends BOOT_COMPLETED once the device has been unlocked for the
 * first time since it started: before that, the encrypted session cannot be
 * read. A tablet without a screen lock gets there by itself.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = (context.applicationContext as PrintStationApp).graph
        if (!ShiftService.wanted(graph).any) return
        try {
            ShiftService.update(context)
        } catch (e: Exception) {
            graph.logger.warn("Could not start after ${intent.action}", e)
            StationNotifications.stopped(
                context,
                context.getString(R.string.stopped_could_not_start, e.message ?: e.javaClass.simpleName),
            )
        }
    }
}
