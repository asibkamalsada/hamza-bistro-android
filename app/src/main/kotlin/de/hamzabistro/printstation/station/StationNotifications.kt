package de.hamzabistro.printstation.station

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.alarm.AlarmActionReceiver
import de.hamzabistro.printstation.alarm.AlarmActivity
import de.hamzabistro.printstation.core.Chime
import de.hamzabistro.printstation.core.OrderStatus
import de.hamzabistro.printstation.core.Problem
import de.hamzabistro.printstation.core.QueueState
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.core.StationStatus
import de.hamzabistro.printstation.ui.Format
import de.hamzabistro.printstation.ui.MainActivity
import java.text.DateFormat
import java.util.Date

/**
 * Every notification the app shows: the permanent one a foreground service
 * must have, the alarm for a new order, the one-off chimes, and the one that
 * says printing stopped.
 *
 * What one says on a lock screen is the order number, what to cook and the
 * total — never who ordered it or where they live, as with the site's pushes.
 */
object StationNotifications {
    const val RUNNING_ID = 1
    private const val ALERT_ID = 2
    private const val ALARM_ID = 3
    private const val PRINTER_ID = 4
    private const val OFFLINE_ID = 5
    private const val ORDER_IDS = 1_000

    private const val CHANNEL_RUNNING = "station"
    private const val CHANNEL_ALERTS = "alerts"
    private const val CHANNEL_ALARM = "order_alarm"
    private const val CHANNEL_KITCHEN = "kitchen"
    private const val CHANNEL_ORDERS = "orders"
    private const val CHANNEL_QUIET = "quiet"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RUNNING, context.getString(R.string.channel_running), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
                // The app makes the sound itself, on the alarm stream, in a
                // loop: a channel's sound would play once, on the ringer.
                NotificationChannel(CHANNEL_ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = context.getString(R.string.channel_alarm_description)
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
                NotificationChannel(CHANNEL_KITCHEN, context.getString(R.string.channel_kitchen), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = context.getString(R.string.channel_kitchen_description)
                        setSound(null, null)
                        enableVibration(false)
                    },
                // "Once, like a message": the channel's own sound, which the
                // phone's ringer and Do Not Disturb decide about as usual.
                NotificationChannel(CHANNEL_ORDERS, context.getString(R.string.channel_orders), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_orders_description) },
                NotificationChannel(CHANNEL_QUIET, context.getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_quiet_description) },
            )
        )
    }

    // -----------------------------------------------------------------------
    // The service
    // -----------------------------------------------------------------------

    /** One line on how the print station is doing: its problem if it has one. */
    fun summary(context: Context, status: StationStatus?): String {
        val problem = status?.problem
        return when {
            problem != null -> describe(context, problem)
            status?.lastPrinted != null ->
                context.getString(R.string.running_last_printed, status.lastPrinted, time(context, status.lastLook))
            status?.lastLook != null -> context.getString(R.string.running_looked, time(context, status.lastLook))
            else -> context.getString(R.string.running_starting)
        }
    }

    /** One line on the queue: how many are waiting, cooking, out — or why it cannot say. */
    fun queueSummary(context: Context, queue: QueueState?): String {
        if (queue == null || !queue.loaded) return context.getString(R.string.running_starting)
        if (queue.notAllowed) return context.getString(R.string.queue_not_staff)
        queue.failingSince?.let { return context.getString(R.string.queue_offline_since, time(context, it)) }
        val counts = queue.orders.groupingBy { it.status }.eachCount()
        val parts =
            listOfNotNull(
                counts[OrderStatus.NEW]?.let { context.getString(R.string.count_new, it) },
                counts[OrderStatus.CONFIRMED]?.let { context.getString(R.string.count_cooking, it) },
                counts[OrderStatus.ON_THE_WAY]?.let { context.getString(R.string.count_out, it) },
            )
        return if (parts.isEmpty()) context.getString(R.string.queue_empty_short) else parts.joinToString(" · ")
    }

    /**
     * The permanent notification. [queue] is null on a device that only
     * prints; [printing] is null on one that has no printer switched on.
     */
    fun running(context: Context, queue: QueueState?, onShift: Boolean, printing: StationStatus?, printingOn: Boolean): Notification {
        val lines =
            listOfNotNull(
                if (onShift) queueSummary(context, queue) else null,
                if (printingOn) context.getString(R.string.running_printer_line, summary(context, printing)) else null,
            )
        val text = lines.joinToString("\n")
        val title = if (onShift) R.string.running_shift_title else R.string.running_title
        return Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(if (onShift) R.drawable.ic_stat_bell else R.drawable.ic_stat_print)
            .setContentTitle(context.getString(title))
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, null))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // -----------------------------------------------------------------------
    // The alarm
    // -----------------------------------------------------------------------

    /**
     * The alarm for the orders ringing, the oldest first: over the lock
     * screen, the screen switched on, like an incoming call. [alert] is
     * whether to pop up again — when an order joined the ones ringing.
     */
    fun alarm(context: Context, ringing: List<StaffOrder>, alert: Boolean) {
        val first = ringing.firstOrNull() ?: return cancelAlarm(context)
        val more = ringing.size - 1
        val title =
            if (first.scheduledFor != null) context.getString(R.string.alarm_title_preorder, first.orderNumber)
            else context.getString(R.string.alarm_title, first.orderNumber)
        val text =
            listOfNotNull(
                Format.dishes(first),
                context.getString(R.string.alarm_total, Format.euro(first.total)),
                if (more > 0) context.resources.getQuantityString(R.plurals.alarm_more, more, more) else null,
            )
                .joinToString(" · ")
        val fullScreen =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(context, CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(!alert)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .addAction(
                    Notification.Action.Builder(null, context.getString(R.string.alarm_silence), silence(context)).build()
                )
                .addAction(
                    Notification.Action.Builder(null, context.getString(R.string.alarm_open), fullScreen).build()
                )
                .build()
        notify(context, ALARM_ID, notification)
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALARM_ID)
    }

    /** A one-off: a sound once (made by the caller), and this. In the night window, without a sound. */
    fun chime(context: Context, chime: Chime, quiet: Boolean) {
        val (id, title, text, order) =
            when (chime) {
                is Chime.NewOrder ->
                    Shown(
                        ORDER_IDS + (chime.order.orderNumber % 100_000).toInt(),
                        context.getString(
                            if (chime.order.scheduledFor != null) R.string.alarm_title_preorder else R.string.alarm_title,
                            chime.order.orderNumber,
                        ),
                        Format.dishes(chime.order) + " · " + context.getString(R.string.alarm_total, Format.euro(chime.order.total)),
                        chime.order.id,
                    )
                is Chime.CookNow ->
                    Shown(
                        ORDER_IDS + (chime.order.orderNumber % 100_000).toInt(),
                        context.getString(R.string.chime_cook_now, chime.order.orderNumber),
                        context.getString(
                            R.string.chime_cook_now_text,
                            Format.clock(chime.order.scheduledFor!!),
                            Format.dishes(chime.order),
                        ),
                        chime.order.id,
                    )
                is Chime.PrinterFailed ->
                    Shown(
                        PRINTER_ID,
                        context.getString(R.string.chime_printer, chime.order),
                        context.getString(R.string.chime_printer_text, chime.reason),
                        null,
                    )
                is Chime.Offline ->
                    Shown(
                        OFFLINE_ID,
                        context.getString(R.string.chime_offline),
                        context.getString(R.string.chime_offline_text, Format.clock(chime.since)),
                        null,
                    )
            }
        val channel =
            when {
                quiet -> CHANNEL_QUIET
                chime is Chime.NewOrder -> CHANNEL_ORDERS
                else -> CHANNEL_KITCHEN
            }
        val notification =
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(if (chime is Chime.NewOrder) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openApp(context, order))
                .setAutoCancel(true)
                .build()
        notify(context, id, notification)
    }

    /** Takes the notifications off for orders nobody needs to act on any more. */
    fun clearOrders(context: Context, keep: Collection<StaffOrder>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val wanted = keep.map { ORDER_IDS + (it.orderNumber % 100_000).toInt() }.toSet()
        for (shown in manager.activeNotifications) {
            if (shown.id >= ORDER_IDS && shown.id !in wanted) manager.cancel(shown.id)
        }
    }

    private data class Shown(val id: Int, val title: String, val text: String, val order: String?)

    // -----------------------------------------------------------------------
    // Printing stopped
    // -----------------------------------------------------------------------

    /** Printing stopped, and why — loud, because nothing prints until somebody acts. */
    fun stopped(context: Context, reason: String) {
        val notification =
            Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle(context.getString(R.string.stopped_title))
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(reason))
                .setContentIntent(openApp(context, null))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build()
        notify(context, ALERT_ID, notification)
    }

    fun clearStopped(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }

    fun describe(context: Context, problem: Problem): String =
        when (problem) {
            is Problem.NotPrinted -> context.getString(R.string.problem_not_printed, problem.order, problem.reason)
            is Problem.Offline -> context.getString(R.string.problem_offline, problem.reason)
            Problem.NotAllowed -> context.getString(R.string.problem_not_allowed)
        }

    fun time(context: Context, at: Long?): String =
        if (at == null) "–" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

    private fun notify(context: Context, id: Int, notification: Notification) {
        // Notifications switched off for the app: the screen still says it.
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    private fun silence(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.SILENCE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The app, at the order's card when there is one. */
    fun openApp(context: Context, order: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            order?.hashCode() ?: 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (order != null) putExtra(MainActivity.EXTRA_ORDER, order) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
