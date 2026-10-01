package de.hamzabistro.printstation.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.hamzabistro.printstation.PrintStationApp

/** "Stumm" on the alarm's notification. Not exported: only the app's own notification reaches it. */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SILENCE) return
        (context.applicationContext as PrintStationApp).graph.silence()
    }

    companion object {
        const val SILENCE = "de.hamzabistro.printstation.SILENCE"
    }
}
