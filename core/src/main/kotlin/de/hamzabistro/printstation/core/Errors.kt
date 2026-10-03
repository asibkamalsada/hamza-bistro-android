package de.hamzabistro.printstation.core

import java.io.IOException

/**
 * There is no session any more: never signed in, signed out, or the server
 * ended it (the refresh token was revoked, the account deleted). Only signing
 * in again helps, so the station stops rather than trying every 25 seconds.
 */
class SignedOutException(cause: Throwable? = null) :
    Exception("signed out — sign in again", cause)

/**
 * The account is signed in but may not do what it asked: print, when it is
 * neither staff nor a print account (20260930180000_print_accounts.sql in
 * hamza-bistro-web), or work the queue, when it is not staff (any more).
 */
class NotAllowedException : Exception("this account may not do this")

/**
 * The sign-in was refused for good — wrong password, unconfirmed address, a
 * captcha that did not pass, a refresh token that was revoked. [code] is the
 * error code Supabase Auth gave, when it gave one.
 */
class AuthRejectedException(val code: String?, message: String) : Exception(message)

/**
 * The server answered, but not with what was asked for. An [IOException],
 * like a network that is down: both are tried again at the next look.
 * [code] is PostgREST's or the database's own, when it gave one.
 */
class BackendException(val status: Int, message: String, val code: String? = null) : IOException(message)

/**
 * The step was already taken, or overtaken, somewhere else — another phone,
 * Telegram, the customer cancelling. Not a failure: the queue is read again
 * and shows where the order really is.
 */
class OrderMovedException : Exception("the order had already moved on")

/**
 * The database would not delay the order (HB435, or P0002 for one that is
 * gone): it is no longer accepted — delivered, cancelled, collected
 * meanwhile — or it has reached the three hours a delay may add up to. Like
 * [OrderMovedException], the queue is read again and shows it as it is.
 */
class NotDelayableException : Exception("the order can no longer be delayed")

/**
 * The database refused what was sent as delivery hours or as a closure: a
 * day that closes before it opens, a time off the quarter hour, a closure
 * that ends before it starts (HB432).
 */
class InvalidHoursException(message: String) : Exception(message)

/** Why special_days_set() refused: the dates (HB456), or the hours or label (HB432). */
enum class SpecialDayError {
    /** A date before today in Leipzig, more than 366 days ahead, or no date at all. */
    DATE,

    /** Off the quarter hour, the end not after the start, or a label over 60 characters. */
    HOURS,
}

/** The database refused special days (hamza-bistro-web#119), for [reason]. */
class InvalidSpecialDayException(val reason: SpecialDayError, message: String) : Exception(message)

/**
 * The shop's database has not got what was asked for yet: it needs the
 * server update [migration] ("20261003140000_special_days").
 */
class NeedsServerUpdateException(val migration: String) : Exception("needs the server update $migration")

/**
 * The database refused a shop setting: auto-decline minutes outside 3–60
 * (HB433 from set_auto_decline_minutes), or busy mode's minutes or length
 * (HB434).
 */
class InvalidSettingException(message: String) : Exception(message)

/**
 * The database does not know an allergen letter that was sent (HB433):
 * public.allergens was changed in the dashboard while a form was open.
 */
class UnknownAllergenException(message: String) : Exception(message)

/**
 * The database took no delivery because delivery is paused, all of it or to
 * the order's ring (HB436, 20261002180000_scoped_pause.sql in
 * hamza-bistro-web). Collection goes through.
 */
class DeliveryPausedException(message: String) : Exception(message)

/**
 * The database took no delivery for that time because of a weekly delivery
 * break — inside it, or for right now within its lead (HB437). Collection
 * goes through.
 */
class DeliveryBreakException(message: String) : Exception(message)

/**
 * The database took no pre-order for that time: the kitchen's quarter hour
 * it would leave in is full (HB457, hamza-bistro-web#73). Only orders for a
 * set time are refused; one for right now is booked into the first quarter
 * hour with room. Another time, or right now, goes through.
 */
class KitchenFullException(message: String) : Exception(message)

/**
 * The database refused how the order was paid (HB438): the method of an
 * order already delivered cannot change, and "online" is not staff's to
 * say. A "Bar" that should have been "Karte" and was not taken back within
 * the undo window is put right in the dashboard.
 */
class PaymentLockedException : Exception("the order's payment method is already recorded")

/**
 * The database took no report for that range (HB455): it ends before it
 * starts, or covers more than 366 days. The app clamps the range, so this
 * means a clock far off or a server with other limits.
 */
class ReportRangeException(message: String) : Exception(message)

/** The ticket did not come out: printer off, out of range, or not answering. */
class PrinterException(message: String, cause: Throwable? = null) : IOException(message, cause)
