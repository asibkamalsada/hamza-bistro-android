package de.hamzabistro.printstation

import android.app.Application
import de.hamzabistro.printstation.station.StationNotifications

class PrintStationApp : Application() {
    /** Everything the service, the receiver and the screen share. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // First: a crash anywhere from here on is kept for the next start.
        CrashLog.install(this)
        graph = AppGraph(this)
        StationNotifications.createChannels(this)
    }
}
