package de.hamzabistro.printstation.calls

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On the phone with the shop's SIM, with "SMS-Links senden" on: sends the
 * text with the address link the tablet asked for, from this phone's own
 * number — the one the caller has just rung, so the text is from somebody
 * they know rather than from a gateway's short code, and their number goes
 * to nobody new.
 */
class TextSender(context: Context) {
    private val context = context.applicationContext

    /** Whether this device can send a text at all: a phone, and the permission given. */
    fun canSend(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) &&
            context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Sends [text] to [phone] and waits for the network's answer: null when
     * every part went out, else why not. A text the network never answers
     * for is reported as such; the tablet can then send it again.
     */
    suspend fun send(phone: String, text: String): String? {
        // Asked here, beside the call it is for: the permission can be taken away in Android's settings at any time.
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "no permission to send SMS"
        val sms = context.getSystemService(SmsManager::class.java) ?: return "no SMS on this device"
        val parts = sms.divideMessage(text)
        val action = "${context.packageName}.SMS_SENT.${COUNTER.incrementAndGet()}"
        val results = mutableListOf<Int>()
        val done = CompletableDeferred<Unit>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    synchronized(results) {
                        results += resultCode
                        if (results.size >= parts.size) done.complete(Unit)
                    }
                }
            }
        // Not exported: only this app's own PendingIntents, below, reach it — on every Android this app runs on.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent =
                ArrayList(
                    parts.indices.map { i ->
                        PendingIntent.getBroadcast(
                            context,
                            i,
                            Intent(action).setPackage(context.packageName),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                        )
                    }
                )
            try {
                sms.sendMultipartTextMessage(phone, null, parts, sent, null)
            } catch (e: SecurityException) {
                return "no permission to send SMS"
            } catch (e: Exception) {
                // A number Android cannot send to, or a SIM it will not use.
                return e.message ?: e.javaClass.simpleName
            }
            withTimeoutOrNull(ANSWER_WITHIN) { done.await() } ?: return "no answer from the network"
            val failed = synchronized(results) { results.firstOrNull { it != Activity.RESULT_OK } } ?: return null
            return describe(failed)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun describe(code: Int): String =
        when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
            SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
            else -> "SMS error $code"
        }

    private companion object {
        val ANSWER_WITHIN = 60.seconds

        /** Each text its own action, so one text's answer is never taken for another's. */
        val COUNTER = AtomicInteger()
    }
}
