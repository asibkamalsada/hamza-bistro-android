package de.hamzabistro.printstation.ui

import android.content.Context
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.DeliveryBreakException
import de.hamzabistro.printstation.core.DeliveryPausedException
import de.hamzabistro.printstation.core.InvalidHoursException
import de.hamzabistro.printstation.core.InvalidSettingException
import de.hamzabistro.printstation.core.InvalidSpecialDayException
import de.hamzabistro.printstation.core.IssueActionException
import de.hamzabistro.printstation.core.IssueError
import de.hamzabistro.printstation.core.KitchenFullException
import de.hamzabistro.printstation.core.MenuEditError
import de.hamzabistro.printstation.core.MenuEditException
import de.hamzabistro.printstation.core.NeedsServerUpdateException
import de.hamzabistro.printstation.core.NoShowAccountGoneException
import de.hamzabistro.printstation.core.NoShowDeliveryException
import de.hamzabistro.printstation.core.NotAllowedException
import de.hamzabistro.printstation.core.PhoneOrderError
import de.hamzabistro.printstation.core.PhoneOrderException
import de.hamzabistro.printstation.core.ReportRangeException
import de.hamzabistro.printstation.core.SignedOutException
import de.hamzabistro.printstation.core.SpecialDayError
import de.hamzabistro.printstation.core.StornoError
import de.hamzabistro.printstation.core.CashBookError
import de.hamzabistro.printstation.core.CashBookException
import de.hamzabistro.printstation.core.StornoException
import de.hamzabistro.printstation.core.OutageError
import de.hamzabistro.printstation.core.OutageException
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
        is KitchenFullException -> context.getString(R.string.kitchen_full)
        is NoShowDeliveryException -> context.getString(R.string.no_show_delivery)
        is NoShowAccountGoneException -> context.getString(R.string.no_shows_reset_gone)
        is MenuEditException -> menuEditText(context, e)
        is ReportRangeException -> context.getString(R.string.report_range_invalid)
        is IssueActionException -> issueText(context, e.reason)
        is PhoneOrderException -> phoneOrderText(context, e)
        is StornoException -> stornoText(context, e.reason)
        is CashBookException -> cashBookText(context, e.reason)
        is OutageException -> outageText(context, e.reason)
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

/** Why cash_book_add refused (hamza-bistro-web 20261128130000_kassenbuch). */
private fun cashBookText(context: Context, reason: CashBookError): String =
    context.getString(
        when (reason) {
            CashBookError.NOT_STAFF -> R.string.problem_not_staff
            CashBookError.BAD_VALUE -> R.string.kb_error_amount
            CashBookError.KEY_REUSED -> R.string.kb_error_key_reused
            CashBookError.OPENING_TWICE -> R.string.kb_error_opening_twice
            CashBookError.TOO_MUCH -> R.string.kb_error_too_much
            CashBookError.NO_NOTE -> R.string.kb_error_no_note
            CashBookError.COUNT_WAITS -> R.string.kb_error_count_waits
        }
    )

/** Why the Kassenausfall refused (hamza-bistro-web 20261128140000_kassenausfall). */
private fun outageText(context: Context, reason: OutageError): String =
    context.getString(
        when (reason) {
            OutageError.NOT_STAFF -> R.string.problem_not_staff
            OutageError.BAD_NOTE -> R.string.ka_error_note
            OutageError.CORRECTION -> R.string.ka_error_correction
            OutageError.OVERLAP -> R.string.ka_error_overlap
            OutageError.NOT_A_SLIP -> R.string.ka_error_slip
            OutageError.TWICE -> R.string.ka_error_twice
            OutageError.AMOUNT -> R.string.ka_error_amount
            OutageError.KEY_REUSED -> R.string.ka_error_key_reused
            OutageError.SIGNING_OFF -> R.string.ka_error_signing_off
        }
    )

/** Why order_storno refused (hamza-bistro-web 20261127100000_storno). */
private fun stornoText(context: Context, reason: StornoError): String =
    context.getString(
        when (reason) {
            StornoError.NOT_STAFF -> R.string.problem_not_staff
            StornoError.BAD_VALUE -> R.string.storno_error_bad_value
            StornoError.KEY_REUSED -> R.string.storno_error_key_reused
            StornoError.NO_REASON -> R.string.storno_error_no_reason
            StornoError.NOT_DELIVERED -> R.string.storno_error_not_delivered
            StornoError.ALREADY -> R.string.storno_error_already
            StornoError.METHOD -> R.string.storno_error_method
            StornoError.UNSIGNED -> R.string.storno_error_unsigned
        }
    )

/** Why staff_place_order refused a phone or walk-in order (#9). */
private fun phoneOrderText(context: Context, e: PhoneOrderException): String =
    when (e.reason) {
        PhoneOrderError.NOT_STAFF -> context.getString(R.string.phone_error_not_staff)
        PhoneOrderError.BAD_VALUE -> context.getString(R.string.phone_error_bad_value)
        PhoneOrderError.CONTACT -> context.getString(R.string.phone_error_contact)
        PhoneOrderError.ETA -> context.getString(R.string.phone_error_eta)
        PhoneOrderError.TIME -> context.getString(R.string.phone_error_time)
        PhoneOrderError.BASKET -> context.getString(R.string.phone_error_basket)
        PhoneOrderError.ADDRESS_INCOMPLETE -> context.getString(R.string.phone_error_address_incomplete)
        PhoneOrderError.OUTSIDE -> context.getString(R.string.phone_error_outside)
        PhoneOrderError.MINIMUM ->
            e.minimum?.let { context.getString(R.string.phone_error_minimum, Format.euro(it)) }
                ?: context.getString(R.string.phone_error_minimum_plain)
        PhoneOrderError.UNCHECKED -> context.getString(R.string.phone_error_unchecked)
        PhoneOrderError.TOTAL_CHANGED -> context.getString(R.string.phone_error_total_changed)
    }

/** Why a problem report could not be answered (HB461–HB463, P0002). */
private fun issueText(context: Context, reason: IssueError): String =
    context.getString(
        when (reason) {
            IssueError.ALREADY_RESOLVED -> R.string.issue_error_resolved
            IssueError.NOT_FOUND -> R.string.issue_error_gone
            IssueError.INVALID_AMOUNT -> R.string.issue_voucher_invalid
            IssueError.NOTE_TOO_LONG -> R.string.issue_error_note
            IssueError.NO_EMAIL -> R.string.issue_error_no_email
        }
    )

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
