package de.hamzabistro.printstation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import de.hamzabistro.printstation.printer.BlePrinter
import de.hamzabistro.printstation.security.KeystoreSessionStore
import de.hamzabistro.printstation.station.StationSettings
import de.hamzabistro.printstation.station.StationState
import de.hamzabistro.printstation.core.FilePrintLog
import de.hamzabistro.printstation.core.Logger
import de.hamzabistro.printstation.core.OrdersRealtime
import de.hamzabistro.printstation.core.PrintStation
import de.hamzabistro.printstation.core.SessionManager
import de.hamzabistro.printstation.core.SupabaseAuth
import de.hamzabistro.printstation.core.SupabaseConfig
import de.hamzabistro.printstation.core.SupabasePrintBackend
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

/**
 * The app's objects, made once per process. By hand rather than with a DI
 * framework: there are a dozen of them, and each is plain to see here.
 */
class AppGraph(context: Context) {
    private val context = context.applicationContext

    val logger: Logger = AndroidLogger

    /** Milliseconds since boot: intervals that a clock change cannot bend. */
    private val monotonic: () -> Long = SystemClock::elapsedRealtime

    val config = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)

    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    val sessions = SessionManager(SupabaseAuth(config, http, monotonic), KeystoreSessionStore(this.context), monotonic)

    val backend = SupabasePrintBackend(config, http, sessions)

    val realtime = OrdersRealtime(config, http, sessions, logger, monotonic)

    val settings = StationSettings(this.context)

    /** Not in a backup, like everything else: see data_extraction_rules.xml. */
    private val printLog = FilePrintLog(File(this.context.noBackupFilesDir, "print-log.json"))

    /** What the service is doing, for the screen. */
    val stationState = MutableStateFlow<StationState>(StationState.Stopped)

    private var printer: BlePrinter? = null

    /**
     * The printer at [address]. One object for the service and the test
     * print alike: the printer takes one connection at a time, and this is
     * where tickets queue for it.
     */
    @Synchronized
    fun printer(address: String): BlePrinter =
        printer?.takeIf { it.address == address }
            ?: BlePrinter(context, address, logger).also {
                printer?.close()
                printer = it
            }

    fun station(printer: BlePrinter) =
        PrintStation(
            backend = backend,
            printer = printer,
            log = printLog,
            station = settings.stationId,
            label = STATION_LABEL,
            lang = TICKET_LANG,
            logger = logger,
            now = monotonic,
            wallClock = System::currentTimeMillis,
        )

    companion object {
        /** How the station is listed under "Druckstationen" on /orders/settings. */
        const val STATION_LABEL = "Android-App"

        /** The kitchen's language. */
        const val TICKET_LANG = "de"
    }
}

/**
 * The core's log, into logcat. Only this app and adb read it, and nothing
 * the core logs carries a token or what is on a ticket.
 */
private object AndroidLogger : Logger {
    private const val TAG = "PrintStation"

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String, error: Throwable?) {
        Log.w(TAG, if (error == null) message else "$message: ${error.message}")
    }
}
