package de.hamzabistro.printstation.core

/**
 * Where the station says what it is doing. Messages carry order numbers and
 * ids at most — never a token, a password, or anything off a ticket.
 */
interface Logger {
    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    companion object {
        val NONE: Logger =
            object : Logger {
                override fun info(message: String) = Unit

                override fun warn(message: String, error: Throwable?) = Unit
            }
    }
}
