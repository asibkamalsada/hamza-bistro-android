package de.hamzabistro.printstation.ui

import android.content.Context
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.DeliveryBreakException
import de.hamzabistro.printstation.core.DeliveryPausedException
import de.hamzabistro.printstation.core.InvalidHoursException
import de.hamzabistro.printstation.core.InvalidSettingException
import de.hamzabistro.printstation.core.InvalidSpecialDayException
import de.hamzabistro.printstation.core.MenuEditError
import de.hamzabistro.printstation.core.MenuEditException
import de.hamzabistro.printstation.core.NeedsServerUpdateException
import de.hamzabistro.printstation.core.NotAllowedException
import de.hamzabistro.printstation.core.ReportRangeException
import de.hamzabistro.printstation.core.SignedOutException
import de.hamzabistro.printstation.core.SpecialDayError
import de.hamzabistro.printstation.core.UnknownAllergenException
import kotlinx.coroutines.CancellationException

/** What went wrong, said for the person holding the device. */
fun failureText(context: Context, e: Exception): String =
    when (e) {
        is SignedOutException -> context.getString(R.string.stopped_signed_out)
        is NotAllowedException -> context.getString(R.string.problem_not_staff)
        is InvalidHoursException -> context.getString(R.string.hours_invalid)
        is InvalidSettingException -> context.getString(R.string.setting_invalid)
        is InvalidSpecialDayException ->
            context.getString(if (e.reason == SpecialDayError.DATE) R.string.special_invalid_date else R.string.special_invalid_hours)
        is NeedsServerUpdateException -> context.getString(R.string.special_needs_update, e.migration)
        is UnknownAllergenException -> context.getString(R.string.menu_allergen_unknown)
        is DeliveryPausedException -> context.getString(R.string.delivery_paused)
        is DeliveryBreakException -> context.getString(R.string.delivery_break)
        is MenuEditException -> menuEditText(context, e)
        is ReportRangeException -> context.getString(R.string.report_range_invalid)
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

/**
 * The menu editor's refusals (HB450–HB454), in the words the server's API
 * note suggests. "Wert nicht erlaubt" keeps the database's own words after
 * it: they name the rule, which is what tells the owner what to change.
 */
private fun menuEditText(context: Context, e: MenuEditException): String =
    when (e.reason) {
        MenuEditError.NOT_FOUND -> context.getString(R.string.menu_error_not_found)
        MenuEditError.NAME_TAKEN -> context.getString(R.string.menu_error_name_taken)
        MenuEditError.VALUE_NOT_ALLOWED -> context.getString(R.string.menu_error_value, e.message?.substringAfter(": ").orEmpty())
        MenuEditError.ARCHIVED -> context.getString(R.string.menu_error_archived)
        MenuEditError.GROUP_WOULD_BE_EMPTY -> context.getString(R.string.menu_error_group_empty)
    }
