package de.hamzabistro.printstation.core

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable

/**
 * What this device remembers about printing, across restarts.
 *
 *   done        printed here, or none of this station's business (accepted
 *               before printing was switched on, printed elsewhere): never
 *               printed here again
 *   unreported  printed here, but the database has not heard so yet — the
 *               network went at the wrong moment. Told again at every look,
 *               or the phones would be rung about a ticket that is lying on
 *               the counter.
 *
 * Order ids only: nothing that says who ordered what.
 */
interface PrintLog {
    fun isDone(order: String): Boolean

    fun markDone(orders: Collection<String>)

    fun unreported(): Set<String>

    fun setUnreported(order: String, unreported: Boolean)
}

/** A [PrintLog] in a small JSON file, written whole on every change. */
class FilePrintLog(private val file: File, private val limit: Int = 200) : PrintLog {
    private var state: State = read()

    @Synchronized override fun isDone(order: String): Boolean = order in state.done

    @Synchronized
    override fun markDone(orders: Collection<String>) {
        val fresh = orders.filterNot { it in state.done }
        if (fresh.isEmpty()) return
        // A few busy evenings' worth; an order older than that cannot come
        // back as accepted and unprinted.
        state = state.copy(done = (state.done + fresh).takeLast(limit))
        write()
    }

    @Synchronized override fun unreported(): Set<String> = state.unreported.toSet()

    @Synchronized
    override fun setUnreported(order: String, unreported: Boolean) {
        val next = if (unreported) (state.unreported + order).distinct().takeLast(limit) else state.unreported - order
        if (next == state.unreported) return
        state = state.copy(unreported = next)
        write()
    }

    private fun read(): State =
        try {
            if (file.exists()) json.decodeFromString(State.serializer(), file.readText()) else State()
        } catch (e: Exception) {
            // Unreadable is the same as empty: at worst an order prints twice,
            // which is the better way round than not at all.
            State()
        }

    private fun write() {
        // A whole new file, then a rename, so a crash mid-write leaves the
        // old one rather than half of the new.
        val next = File(file.parentFile, "${file.name}.new")
        try {
            next.writeText(json.encodeToString(State.serializer(), state))
            if (!next.renameTo(file)) throw IOException("could not replace ${file.name}")
        } catch (e: IOException) {
            next.delete()
            throw e
        }
    }

    @Serializable
    private data class State(val done: List<String> = emptyList(), val unreported: List<String> = emptyList())
}

/** A [PrintLog] that lasts as long as the process — for tests. */
class MemoryPrintLog : PrintLog {
    private val done = linkedSetOf<String>()
    private val unreported = linkedSetOf<String>()

    @Synchronized override fun isDone(order: String) = order in done

    @Synchronized override fun markDone(orders: Collection<String>) { done += orders }

    @Synchronized override fun unreported(): Set<String> = unreported.toSet()

    @Synchronized
    override fun setUnreported(order: String, unreported: Boolean) {
        if (unreported) this.unreported += order else this.unreported -= order
    }
}
