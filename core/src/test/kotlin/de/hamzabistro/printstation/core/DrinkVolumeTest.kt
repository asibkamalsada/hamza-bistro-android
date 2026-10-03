package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same field and the same line as /menu-admin and src/app/unit-price.ts on the site. */
class DrinkVolumeTest {
    @Test
    fun `blank is no size, a whole number is millilitres, anything else does not read`() {
        assertEquals(DrinkVolume.Typed(null), DrinkVolume.parse(""))
        assertEquals(DrinkVolume.Typed(null), DrinkVolume.parse("  "))
        assertEquals(DrinkVolume.Typed(330), DrinkVolume.parse(" 330 "))
        assertNull(DrinkVolume.parse("0,33"))
        assertNull(DrinkVolume.parse("330 ml"))
    }

    @Test
    fun `refuses 0 and anything past ten litres, as the database does`() {
        assertTrue(DrinkVolume.allowed(null))
        assertTrue(DrinkVolume.allowed(1))
        assertTrue(DrinkVolume.allowed(10_000))
        assertFalse(DrinkVolume.allowed(0))
        assertFalse(DrinkVolume.allowed(-330))
        assertFalse(DrinkVolume.allowed(10_001))

        val edit = DishEdit("Cola 0,33l", "", 2.5, 0, "")
        assertTrue(edit.copy(volumeMl = 330).valid)
        assertTrue(edit.copy(volumeMl = null).valid)
        assertFalse(edit.copy(volumeMl = 0).valid)
        assertFalse(edit.copy(volumeMl = 10_001).valid)
    }

    @Test
    fun `prints the size and the price per litre without the Pfand`() {
        // 2,40 € with 0,25 € Pfand in 0,33 l: 2,15 / 0,33 = 6,515… → 6,52.
        assertEquals("0,33 l · 6,52 €/l", DrinkVolume.line(330, 2.40, 0.25))
        assertEquals("1,5 l · 2,00 €/l", DrinkVolume.line(1500, 3.25, 0.25))
        assertEquals("1 l · 3,00 €/l", DrinkVolume.line(1000, 3.0, 0.0))
        assertEquals("1,25 l · 2,40 €/l", DrinkVolume.line(1250, 3.0, 0.0))
    }

    @Test
    fun `prints nothing for food, an unsized drink, or a price that is all Pfand`() {
        assertNull(DrinkVolume.line(null, 7.5, 0.0))
        assertNull(DrinkVolume.line(0, 2.5, 0.25))
        assertNull(DrinkVolume.line(330, 0.25, 0.25))
    }
}
