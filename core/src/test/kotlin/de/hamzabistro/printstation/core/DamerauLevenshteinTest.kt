package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DamerauLevenshteinTest {
    private fun slips(typed: String, name: String, from: Int = 0, max: Int = 3) = DamerauLevenshtein.toBeginning(typed, name, from, max)

    @Test
    fun `what was typed may stop mid-name`() {
        assertEquals(0, slips("karl", "karlheinestrasse"))
        assertEquals(0, slips("", "karlheinestrasse"))
        assertEquals(0, slips("karlheinestrasse", "karlheinestrasse"))
    }

    @Test
    fun `each kind of slip counts once`() {
        assertEquals(1, slips("carl", "karlheinestrasse"), "a wrong letter")
        assertEquals(1, slips("kral", "karlheinestrasse"), "two swapped")
        assertEquals(1, slips("kalhein", "karlheinestrasse"), "one missing")
        assertEquals(1, slips("karrlhein", "karlheinestrasse"), "one too many")
        assertEquals(2, slips("carlhiene", "karlheinestrasse"))
    }

    @Test
    fun `a name can be read from one of its words on`() {
        assertEquals(0, slips("heine", "karlheinestrasse", from = 4))
        assertEquals(1, slips("haine", "karlheinestrasse", from = 4))
        // Typed longer than what is left of the name: the rest is too many.
        assertEquals(1, slips("strassen", "karlheinestrasse", from = 9))
    }

    @Test
    fun `too far off is not counted to the end`() {
        assertEquals(2, slips("qwertz", "karlheinestrasse", max = 1))
        assertEquals(4, slips("qwertz", "karlheinestrasse", max = 3))
        assertEquals(1, slips("abc", "", max = 0))
    }
}
