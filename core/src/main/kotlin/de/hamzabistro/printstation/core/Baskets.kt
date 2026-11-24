package de.hamzabistro.printstation.core

/** What taps do to a basket: the same at the counter and on the phone. */
object Baskets {
    /** The same dish with the same choices and note is one line with more of it, as in the site's basket. */
    fun add(lines: List<BasketLine>, line: BasketLine): List<BasketLine> {
        val same = lines.indexOfFirst { alike(it, line) }
        if (same < 0) return lines + line
        return lines.mapIndexed { i, it -> if (i == same) it.copy(qty = (it.qty + line.qty).coerceAtMost(PhoneOrders.MAX_QTY)) else it }
    }

    /** [qty] of the line at [index]; none of it takes the line out. */
    fun setQty(lines: List<BasketLine>, index: Int, qty: Int): List<BasketLine> =
        if (qty <= 0) lines.filterIndexed { i, _ -> i != index }
        else lines.mapIndexed { i, it -> if (i == index) it.copy(qty = qty.coerceAtMost(PhoneOrders.MAX_QTY)) else it }

    /** The note of the line at [index]: where it stands, unless that makes it the same as another line. */
    fun setNote(lines: List<BasketLine>, index: Int, note: String): List<BasketLine> {
        val noted = lines.getOrNull(index)?.copy(note = note.trim().take(PhoneOrders.NOTE_MAX)) ?: return lines
        val others = lines.filterIndexed { i, _ -> i != index }
        return if (others.any { alike(it, noted) }) add(others, noted) else lines.mapIndexed { i, it -> if (i == index) noted else it }
    }

    /** By their ids: a menu read again meanwhile does not make two lines of one. */
    private fun alike(a: BasketLine, b: BasketLine): Boolean =
        a.dish.id == b.dish.id && a.options.map { it.id } == b.options.map { it.id } && a.note.trim() == b.note.trim()
}
