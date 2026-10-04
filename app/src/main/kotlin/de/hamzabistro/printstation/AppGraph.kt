package de.hamzabistro.printstation

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import de.hamzabistro.printstation.alarm.AlarmPlayer
import de.hamzabistro.printstation.core.AlarmDecision
import de.hamzabistro.printstation.core.AlarmPolicy
import de.hamzabistro.printstation.core.DeviceReport
import de.hamzabistro.printstation.core.Eta
import de.hamzabistro.printstation.core.FilePrintLog
import de.hamzabistro.printstation.core.GitHubReleases
import de.hamzabistro.printstation.core.IssueWatch
import de.hamzabistro.printstation.core.IssuesState
import de.hamzabistro.printstation.core.Logger
import de.hamzabistro.printstation.core.OrderQueue
import de.hamzabistro.printstation.core.OrdersRealtime
import de.hamzabistro.printstation.core.PrintStation
import de.hamzabistro.printstation.core.PrinterException
import de.hamzabistro.printstation.core.QueueState
import de.hamzabistro.printstation.core.SessionManager
import de.hamzabistro.printstation.core.SignedOutException
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.core.SupabaseAuth
import de.hamzabistro.printstation.core.SupabaseConfig
import de.hamzabistro.printstation.core.SupabasePrintBackend
import de.hamzabistro.printstation.core.SupabaseDevicesBackend
import de.hamzabistro.printstation.core.SupabaseHistoryBackend
import de.hamzabistro.printstation.core.SupabaseIssuesBackend
import de.hamzabistro.printstation.core.SupabaseMenuBackend
import de.hamzabistro.printstation.core.SupabaseShopBackend
import de.hamzabistro.printstation.core.SupabaseStaffBackend
import de.hamzabistro.printstation.core.UpdateChecker
import de.hamzabistro.printstation.printer.BlePrinter
import de.hamzabistro.printstation.queue.PendingSteps
import de.hamzabistro.printstation.security.KeystoreSessionStore
import de.hamzabistro.printstation.station.Role
import de.hamzabistro.printstation.station.StationSettings
import de.hamzabistro.printstation.station.StationState
import de.hamzabistro.printstation.station.wire
import de.hamzabistro.printstation.ui.ShopView
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The app's objects, made once per process. By hand rather than with a DI
 * framework: there are a few dozen of them, and each is plain to see here.
 */
class AppGraph(context: Context) {
    private val context = context.applicationContext

    val logger: Logger = AndroidLogger

    /** Milliseconds since boot: intervals that a clock change cannot bend. */
    private val monotonic: () -> Long = SystemClock::elapsedRealtime

    /** For the work that outlives a screen: the queue, the steps on their undo window. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val config = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)

    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    val sessions = SessionManager(SupabaseAuth(config, http, monotonic), KeystoreSessionStore(this.context), monotonic)

    val backend = SupabasePrintBackend(config, http, sessions, logger)

    val staff = SupabaseStaffBackend(config, http, sessions)

    /** Opening and closing the shop, and its delivery hours. */
    val shop = SupabaseShopBackend(config, http, sessions)

    /**
     * Whether the shop takes orders, as last read or switched — one line
     * for the queue and the hours screen alike, so they never disagree.
     */
    val shopView = MutableStateFlow(ShopView())

    /** "Letzte Bestellungen" and today's takings. */
    val history = SupabaseHistoryBackend(config, http, sessions)

    /** Who hears about orders, the print stations, the address check. */
    val devices = SupabaseDevicesBackend(config, http, sessions)

    /** What is sold out, and what a dish is and costs. */
    val menu = SupabaseMenuBackend(config, http, sessions)

    val realtime = OrdersRealtime(config, http, sessions, logger, monotonic)

    /** The customers' problem reports: "Mehr → Reklamationen" (hamza-bistro-web#88). */
    val issues = SupabaseIssuesBackend(config, http, sessions)

    val settings = StationSettings(this.context)

    /** Not in a backup, like everything else: see data_extraction_rules.xml. */
    private val printLog = FilePrintLog(File(this.context.noBackupFilesDir, "print-log.json"))

    /** What the print station is doing, for the screen. */
    val stationState = MutableStateFlow<StationState>(StationState.Stopped)

    // -----------------------------------------------------------------------
    // The queue
    // -----------------------------------------------------------------------

    /** The open orders, read for the screen and the alarm alike. */
    val queue =
        OrderQueue(
            backend = staff,
            logger = logger,
            now = monotonic,
            wallClock = System::currentTimeMillis,
            report = ::deviceReport,
        )

    /**
     * Keeps [queue] read while anybody — the queue screen, the shift service
     * — collects [liveQueue]. Waits out a sign-out and starts again with the
     * next account, so it never ends while somebody listens.
     */
    private val queueRunner =
        flow<Nothing> {
                while (true) {
                    sessions.account.first { it != null }
                    queue.reset()
                    try {
                        queue.run(realtime.changes(OrdersRealtime.Watch.ALL))
                    } catch (e: SignedOutException) {
                        logger.warn("The queue stopped: signed out")
                        sessions.account.first { it == null }
                    }
                }
            }
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000))

    /** The queue as it is read, kept fresh for as long as it is collected. */
    val liveQueue: Flow<QueueState> = merge(queueRunner, queue.state)

    /** What this device says about itself while it is on shift; nothing while it is not. */
    private fun deviceReport(): DeviceReport? {
        val device = settings.device.value
        if (device.role != Role.STAFF || !device.onShift) return null
        return DeviceReport(settings.stationId, deviceLabel(), device.alarm.newOrders.wire)
    }

    /**
     * What this device is called on the site — on "Wer von Bestellungen
     * erfährt", and in the Kassensturz beside what it delivered. Unnamed, it
     * goes by its model, which beats "Unbenanntes Gerät" on the list.
     */
    fun deviceLabel(): String = settings.device.value.label.trim().ifEmpty { Build.MODEL }

    /**
     * The minutes the ETA buttons are built around, from the dishes and the
     * ring, the wait for the order's kitchen slot, plus busy mode's while it
     * is on at [now].
     */
    fun suggestedEta(order: StaffOrder, prep: Map<Long, Int>, now: Instant): Int =
        Eta.estimate(order, prep, shopView.value.hours?.busyMinutes(now) ?: 0, now)

    val steps =
        PendingSteps(
            scope = scope,
            backend = staff,
            queue = queue,
            logger = logger,
            undoSeconds = { settings.device.value.undoSeconds },
            now = System::currentTimeMillis,
        )

    // -----------------------------------------------------------------------
    // The problem reports
    // -----------------------------------------------------------------------

    /** How many reports are open, for the badge and the chime. */
    val issueWatch = IssueWatch(issues, logger)

    /** Keeps [issueWatch] counting while anybody collects [liveIssues], through sign-outs, as [queueRunner] does. */
    private val issueRunner =
        flow<Nothing> {
                while (true) {
                    sessions.account.first { it != null }
                    issueWatch.reset()
                    try {
                        issueWatch.run(realtime.changes(OrdersRealtime.Watch.ISSUES))
                    } catch (e: SignedOutException) {
                        logger.warn("The problem reports stopped: signed out")
                        sessions.account.first { it == null }
                    }
                }
            }
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000))

    /** The open reports as counted, kept fresh for as long as it is collected. */
    val liveIssues: Flow<IssuesState> = merge(issueRunner, issueWatch.state)

    // -----------------------------------------------------------------------
    // A newer build
    // -----------------------------------------------------------------------

    /**
     * Whether CI has published a newer APK than this one, from GitHub: a
     * host of its own, so a slow or refusing GitHub holds up no call to the
     * shop's database.
     */
    val updates =
        UpdateChecker(
            source = GitHubReleases(http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build(), "HamzaTeam/${BuildConfig.VERSION_NAME}"),
            installed = BuildConfig.VERSION_CODE,
            logger = logger,
            now = monotonic,
        )

    // -----------------------------------------------------------------------
    // The alarm
    // -----------------------------------------------------------------------

    val alarmPolicy = AlarmPolicy()

    val alarmPlayer = AlarmPlayer(this.context)

    /** What the alarm is doing, for the alarm screen and the queue's banner. */
    val alarm = MutableStateFlow(AlarmDecision())

    /** Bumped to have the alarm decide again at once — after "Stumm". */
    val alarmPoke = MutableStateFlow(0)

    /** "Stumm": what rings now stays quiet for a while; an order still waiting rings again after. */
    fun silence() {
        alarmPolicy.silence(Instant.now(), settings.device.value.alarm.silenceSeconds)
        alarmPlayer.stopLoop()
        alarmPoke.value++
    }

    // -----------------------------------------------------------------------
    // Printing
    // -----------------------------------------------------------------------

    private var printer: BlePrinter? = null

    /**
     * The printer at [address]. One object for the service, the test print
     * and a ticket by hand: the printer takes one connection at a time, and
     * this is where tickets queue for it.
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
            bagSlip = { settings.bagSlip },
            logger = logger,
            now = monotonic,
            wallClock = System::currentTimeMillis,
        )

    /**
     * One order's ticket on this device's printer, by hand: a second copy, a
     * jammed one, or the first on a device that does not print by itself.
     * Recorded as printed, as the site's "Bon drucken" does.
     */
    suspend fun printByHand(order: StaffOrder) {
        val chosen = settings.printer ?: throw PrinterException("no printer chosen")
        val ticket =
            backend.ticket(order.id, TICKET_LANG, settings.bagSlip) ?: throw PrinterException("the order is gone")
        withTimeoutOrNull(TICKET_TIMEOUT) { printer(chosen.address).print(ticket) }
            ?: throw PrinterException("the printer did not answer")
        printLog.markDone(listOf(order.id))
        runCatching { backend.finish(order.id, true) }
        queue.refresh()
    }

    /**
     * A dish's photo, for the preview beside its form: at most
     * [PHOTO_PREVIEW_BYTES], over HTTPS like everything else, or null.
     */
    suspend fun photoPreview(url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val address = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@withContext null
            runCatching {
                http.newCall(Request.Builder().url(address).build()).execute().use { response ->
                    val source = response.body.source()
                    // More than the limit buffered is too big, whatever the length header said.
                    if (!response.isSuccessful || source.request(PHOTO_PREVIEW_BYTES + 1)) null
                    else source.buffer.readByteArray()
                }
            }.getOrNull()
        }

    companion object {
        private const val PHOTO_PREVIEW_BYTES = 2L * 1024 * 1024

        /** How the station is listed under "Druckstationen" on /orders/settings. */
        const val STATION_LABEL = "Android-App"

        /** The kitchen's language. */
        const val TICKET_LANG = "de"

        private val TICKET_TIMEOUT = 20.seconds
    }
}

/**
 * The core's log, into logcat. Only this app and adb read it, and nothing
 * the core logs carries a token or what is on a ticket.
 */
private object AndroidLogger : Logger {
    private const val TAG = "HamzaTeam"

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String, error: Throwable?) {
        Log.w(TAG, if (error == null) message else "$message: ${error.message}")
    }
}
