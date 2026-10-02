package de.hamzabistro.printstation.ui

import android.content.Context
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.InvalidHoursException
import de.hamzabistro.printstation.core.NotAllowedException
import de.hamzabistro.printstation.core.SignedOutException
import kotlinx.coroutines.CancellationException

/** What went wrong, said for the person holding the device. */
fun failureText(context: Context, e: Exception): String =
    when (e) {
        is SignedOutException -> context.getString(R.string.stopped_signed_out)
        is NotAllowedException -> context.getString(R.string.problem_not_staff)
        is InvalidHoursException -> context.getString(R.string.hours_invalid)
        else -> context.getString(R.string.problem_offline, e.message ?: e.javaClass.simpleName)
    }

/**
 * [block]'s answer, or null with [onFailure] told what went wrong in words.
 * Cancellation is not a failure: it goes on up.
 */
suspend fun <T> attempt(context: Context, onFailure: (String) -> Unit, block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(failureText(context, e))
        null
    }
