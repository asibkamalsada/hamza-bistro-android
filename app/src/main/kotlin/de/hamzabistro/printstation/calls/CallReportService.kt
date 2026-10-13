package de.hamzabistro.printstation.calls

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import android.telecom.PhoneAccount
import de.hamzabistro.printstation.PrintStationApp
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On the shop's phone, with "Anrufe ans Tablet melden" on: tells the order
 * tablet who is calling, so "Neue Bestellung" starts with the number in it.
 *
 * Android asks the screening app about every incoming call from a number
 * that is not in the phone's contacts. This one screens nothing: it answers
 * at once that the call goes through as it is, and only then files the
 * number, as the signed-in staff account. The phone rings exactly as it
 * would without it, whatever becomes of the report.
 */
class CallReportService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        // First, and whatever follows: the phone does not ring until Android has this answer.
        respondToCall(details, CallResponse.Builder().build())
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val graph = (application as PrintStationApp).graph
        if (!graph.settings.device.value.reportCalls) return
        // A withheld number has none to pass on, and the tablet would learn only what the ringing says anyway.
        val phone = details.handle?.takeIf { it.scheme == PhoneAccount.SCHEME_TEL }?.schemeSpecificPart?.trim().orEmpty()
        if (phone.isEmpty()) return
        // On the app's scope: Android lets go of this service the moment it has its answer.
        graph.scope.launch {
            // The call is now. Offline or slow, the report is dropped: late, it would be of no use to anybody.
            withTimeoutOrNull(REPORT_WITHIN) {
                try {
                    graph.calls.report(phone)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.logger.warn("The call could not be reported", e)
                }
            }
        }
    }

    private companion object {
        val REPORT_WITHIN = 10.seconds
    }
}
