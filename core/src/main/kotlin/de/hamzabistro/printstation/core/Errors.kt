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
 * The account is signed in but may not print: it is neither staff nor a
 * print account (see 20260930180000_print_accounts.sql in hamza-bistro-web).
 */
class NotAllowedException : Exception("this account may not print")

/**
 * The sign-in was refused for good — wrong password, unconfirmed address, a
 * captcha that did not pass, a refresh token that was revoked. [code] is the
 * error code Supabase Auth gave, when it gave one.
 */
class AuthRejectedException(val code: String?, message: String) : Exception(message)

/**
 * The server answered, but not with what was asked for. An [IOException],
 * like a network that is down: both are tried again at the next look.
 */
class BackendException(val status: Int, message: String) : IOException(message)

/** The ticket did not come out: printer off, out of range, or not answering. */
class PrinterException(message: String, cause: Throwable? = null) : IOException(message, cause)
