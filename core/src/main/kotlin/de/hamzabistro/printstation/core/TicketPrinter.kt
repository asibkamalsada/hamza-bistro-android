package de.hamzabistro.printstation.core

/** Where tickets come out: the Bluetooth printer, in the app. */
fun interface TicketPrinter {
    /**
     * Sends one ticket and returns once it has gone, or throws — a
     * [PrinterException] when the printer could not be reached. One at a
     * time: a caller waits for the last ticket before sending the next.
     */
    suspend fun print(ticket: ByteArray)
}
