package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same three states and the same ticks as the site's allergen picker on /menu-admin. */
class AllergensTest {
    @Test
    fun `ticking letters keeps them sorted, and unticking the last is not stated again`() {
        val one = Allergens.toggle(null, "g")
        assertEquals(listOf("g"), one)
        val two = Allergens.toggle(one, "a")
        assertEquals(listOf("a", "g"), two)
        assertEquals(listOf("a"), Allergens.toggle(two, "g"))
        // Never [] by unticking: that would say "none" without anybody saying it.
        assertNull(Allergens.toggle(listOf("a"), "a"))
    }

    @Test
    fun `none is its own box, and only that box says none`() {
        assertEquals(emptyList(), Allergens.toggleNone(null))
        assertEquals(emptyList(), Allergens.toggleNone(listOf("a", "g")))
        assertNull(Allergens.toggleNone(emptyList()))
        // A letter ticked after "none" replaces it.
        assertEquals(listOf("c"), Allergens.toggle(emptyList(), "c"))
    }

    @Test
    fun `not stated and none are different answers`() {
        assertTrue(Allergens.same(null, null))
        assertTrue(Allergens.same(emptyList(), emptyList()))
        assertTrue(Allergens.same(listOf("a", "g"), listOf("g", "a")))
        assertFalse(Allergens.same(null, emptyList()))
        assertFalse(Allergens.same(emptyList(), null))
        assertFalse(Allergens.same(listOf("a"), listOf("a", "g")))
    }

    @Test
    fun `saves only what changed, and an untouched form saves nothing`() {
        // Untouched: nothing goes out, and above all no "none".
        assertNull(Allergens.toSave(null, null))
        assertNull(Allergens.toSave(listOf("a"), listOf("a")))
        assertEquals(Allergens.Change(emptyList()), Allergens.toSave(null, emptyList()))
        assertEquals(Allergens.Change(listOf("a", "g")), Allergens.toSave(null, listOf("a", "g")))
        // Back to "not stated" is a change too, and is sent as null.
        assertEquals(Allergens.Change(null), Allergens.toSave(listOf("a"), null))
        assertEquals(Allergens.Change(null), Allergens.toSave(emptyList(), null))
    }

    @Test
    fun `counts the rows still missing`() {
        assertEquals(2, Allergens.missing(listOf(null, emptyList(), listOf("a"), null)))
        assertEquals(0, Allergens.missing(emptyList()))
    }

    @Test
    fun `names an allergen in the app's language`() {
        val gluten = Allergen("a", "Glutenhaltiges Getreide", "Cereals containing gluten")
        assertEquals("Glutenhaltiges Getreide", gluten.name(german = true))
        assertEquals("Cereals containing gluten", gluten.name(german = false))
    }
}
