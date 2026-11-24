package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BasketsTest {
    private val sharp = MenuOption(1, "Scharf", 0.0, true, emptyList())
    private val doener = MenuDish(1, "Speisen", "Döner", "", 7.5, 10, 0, "", true, emptyList())
    private val cola = MenuDish(2, "Getränke", "Cola", "", 2.5, 0, 0, "", true, emptyList())

    @Test
    fun `the same dish is one line, even once the menu has been read again`() {
        val first = Baskets.add(emptyList(), BasketLine(doener, 1, listOf(sharp)))
        // Sold out since, and dearer: still the same choice of the same dish.
        val again = BasketLine(doener.copy(price = 8.0), 2, listOf(sharp.copy(available = false)))
        assertEquals(listOf(3), Baskets.add(first, again).map { it.qty })
    }

    @Test
    fun `other choices or another note are another line`() {
        var lines = Baskets.add(emptyList(), BasketLine(doener, 1, listOf(sharp)))
        lines = Baskets.add(lines, BasketLine(doener, 1))
        lines = Baskets.add(lines, BasketLine(doener, 1, note = "ohne Zwiebeln"))
        lines = Baskets.add(lines, BasketLine(doener, 1, note = " ohne Zwiebeln "))
        assertEquals(listOf(1, 1, 2), lines.map { it.qty })
        assertEquals(PhoneOrders.MAX_QTY, Baskets.add(lines, BasketLine(doener, 99))[1].qty)
    }

    @Test
    fun `none of a line takes it out`() {
        val lines = listOf(BasketLine(doener, 2), BasketLine(cola, 1))
        assertEquals(listOf(cola), Baskets.setQty(lines, 0, 0).map { it.dish })
        assertEquals(listOf(2, 99), Baskets.setQty(lines, 1, 120).map { it.qty })
    }

    @Test
    fun `a note stays on its line`() {
        val lines = listOf(BasketLine(doener, 2), BasketLine(cola, 1))
        val noted = Baskets.setNote(lines, 0, "  gut durch ")
        assertEquals(listOf("gut durch", ""), noted.map { it.note })
        assertEquals(PhoneOrders.NOTE_MAX, Baskets.setNote(lines, 0, "x".repeat(200))[0].note.length)
        assertEquals(lines, Baskets.setNote(noted, 0, ""))
        assertEquals(lines, Baskets.setNote(lines, 7, "zu spät"))
    }

    @Test
    fun `a note that makes two lines the same makes one line of them`() {
        val lines = listOf(BasketLine(doener, 2), BasketLine(cola, 1), BasketLine(doener, 1, note = "ohne Zwiebeln"))
        val merged = Baskets.setNote(lines, 2, "")
        assertEquals(listOf(doener to 3, cola to 1), merged.map { it.dish to it.qty })
    }
}
