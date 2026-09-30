package de.hamzabistro.printstation.station

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Problem
import de.hamzabistro.printstation.core.StationStatus
import de.hamzabistro.printstation.ui.MainActivity
import java.text.DateFormat
import java.util.Date

/**
 * The permanent "Druckstation läuft" notification a foreground service must
 * show, and the one that says printing stopped. Order numbers at most: the
 * lock screen shows them.
 */
object StationNotifications {
    const val RUNNING_ID = 1
    private const val ALERT_ID = 2
    private const val CHANNEL_RUNNING = "station"
    private const val CHANNEL_ALERTS = "alerts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                        CHANNEL_RUNNING,
                        context.getString(R.string.channel_running),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply { setShowBadge(false) },
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        )
    }

    /** One line on how the station is doing: its problem if it has one. */
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

    fun running(context: Context, status: StationStatus?): Notification {
        val text = summary(context, status)
        return Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(context.getString(R.string.running_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Printing stopped, and why — loud, because nothing prints until somebody acts. */
    fun stopped(context: Context, reason: String) {
        val notification =
            Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle(context.getString(R.string.stopped_title))
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(reason))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, notification) }
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

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
