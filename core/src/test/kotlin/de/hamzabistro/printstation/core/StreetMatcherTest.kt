package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreetMatcherTest {
    private fun street(name: String, postcode: String = "04229", delivery: StreetDelivery = StreetDelivery.INSIDE) =
        StreetEntry(name, listOf(postcode), "Plagwitz", delivery)

    /** A few of Leipzig's streets, each with the neighbours it gets mistaken for. */
    private val matcher =
        StreetMatcher(
            listOf(
                street("Karl-Heine-Straße"),
                street("Karl-Heine-Platz"),
                street("Karl-Härting-Straße", "04318", StreetDelivery.OUTSIDE),
                street("Karl-Helbig-Straße", "04229", StreetDelivery.OUTSIDE),
                street("Carl-Hinné-Straße", "04318"),
                // Sounds like "carl heine" letter for letter, and is nothing like it.
                street("Grillenstraße", "04349"),
                street("Gerlindeweg", "04249"),
                street("Herwigstraße", "04277"),
                street("Lützner Straße", "04177"),
                street("Lützner Plan", "04179"),
                street("Lutherstraße", "04315"),
                street("Körnerstraße", "04107"),
                street("Körnerplatz", "04107"),
                street("Krönerstraße", "04318"),
                street("Eisenbahnstraße", "04315"),
                street("An der Eisenbahn", "04349", StreetDelivery.OUTSIDE),
                street("Zschochersche Straße"),
                street("Zschochersche Allee", "04207"),
                street("Käthe-Kollwitz-Straße", "04109"),
                street("Kulkwitzer Straße", "04229"),
                street("Demmeringstraße", "04177"),
                street("Dieskaustraße", "04229"),
                street("Gießerstraße", "04229"),
                street("Felsenkellerstraße", "04177"),
                street("Erich-Zeigner-Allee", "04229"),
                street("Robert-Mayer-Straße", "04347"),
                street("Carl-Meyer-Straße", "04315"),
                street("Ernst-Meier-Straße", "04249"),
                street("Markt", "04109"),
                street("Straße des 17. Juni", "04107"),
                street("Straße des 18. Oktober", "04103"),
                street("Querstraße", "04103"),
                street("Brühl", "04109"),
            )
        )

    private fun names(typed: String) = matcher.match(typed).map { it.fullStreet }

    @Test
    fun `a name typed without its umlauts, hyphens or Strasse is that street`() {
        assertEquals("Karl-Heine-Straße", names("karl heine").first())
        assertEquals(listOf("Karl-Heine-Straße", "Karl-Heine-Platz"), names("karlheine"))
        assertEquals(listOf("Körnerstraße"), names("kornerstr"))
        assertEquals(listOf("Körnerstraße", "Körnerplatz"), names("koerner"))
        assertEquals(listOf("Lützner Straße"), names("lutzner str."))
        assertEquals(listOf("Lützner Straße", "Lützner Plan"), names("lutzner"))
        assertEquals(listOf("Gießerstraße"), names("giesserstrasse"))
        assertEquals(listOf("Brühl"), names("bruhl"))
    }

    @Test
    fun `the house number typed stays on the street`() {
        val top = matcher.match("eisenbahnstr 12").first()
        assertEquals("Eisenbahnstraße", top.entry.name)
        assertEquals("12", top.houseNumber)
        assertEquals("Eisenbahnstraße 12", top.fullStreet)
        assertEquals("04315", top.primaryPostcode)
        assertEquals("Karl-Heine-Straße 9a", names("karl heine 9a").first())
        assertEquals("Karl-Heine-Straße 12-14", names("Karl-Heine-Str. 12-14").first())
        assertEquals("Brühl 10", names("brühl 10").first())
        assertNull(matcher.match("karl heine").first().houseNumber)
    }

    @Test
    fun `half a name finds the streets that begin with it, the delivered ones first`() {
        assertEquals(listOf("Karl-Heine-Straße", "Karl-Heine-Platz", "Karl-Helbig-Straße", "Karl-Härting-Straße"), names("karl he"))
        assertEquals(listOf("Zschochersche Straße", "Zschochersche Allee"), names("zscho"))
        assertTrue(names("k").isEmpty(), "one letter is every street")
    }

    @Test
    fun `one word of a name finds it`() {
        assertEquals(listOf("Erich-Zeigner-Allee"), names("zeigner"))
        assertEquals(listOf("Käthe-Kollwitz-Straße 5"), names("kollwitz 5"))
        assertEquals(listOf("Eisenbahnstraße", "An der Eisenbahn"), names("eisenbahn"))
        assertEquals(listOf("Felsenkellerstraße"), names("keller"))
    }

    @Test
    fun `a name spelled the way it sounded finds the street`() {
        assertEquals("Karl-Härting-Straße 9", names("karl herting 9").first())
        assertEquals("Karl-Härting-Straße 9", names("herting 9").first())
        assertEquals("Karl-Heine-Straße", names("carl heine").first())
        assertEquals("Zschochersche Straße", names("tschochersche").first())
        assertEquals(listOf("Käthe-Kollwitz-Straße"), names("kete kolwiz"))
        assertEquals("Eisenbahnstraße", names("aisenbahn").first())
    }

    @Test
    fun `a slip of the finger finds the street`() {
        assertEquals(listOf("Demmeringstraße 44"), names("demeringstr 44"))
        assertEquals(listOf("Dieskaustraße 100"), names("diskaustr 100"))
        assertEquals(listOf("Gießerstraße"), names("gisserstr"))
        assertEquals(listOf("Körnerstraße 3"), names("körnerstrase 3"))
        assertEquals("Lützner Straße", names("lüzner").first())
    }

    @Test
    fun `what only sounds alike is not offered`() {
        assertFalse("Grillenstraße" in names("carl heine"))
        assertFalse("Gerlindeweg" in names("carl heine"))
        assertFalse("Herwigstraße 9" in names("karl herting 9"))
    }

    @Test
    fun `a name that was found has no guesses beside it`() {
        assertEquals(listOf("Lützner Straße 5"), names("lützner str 5"))
        assertEquals(listOf("Körnerstraße"), names("körnerstraße"))
        // The beginning of a name: only what is a letter off and sounds the same.
        assertEquals(listOf("Robert-Mayer-Straße", "Carl-Meyer-Straße"), names("mayer"))
    }

    @Test
    fun `nonsense finds nothing`() {
        for (typed in listOf("qwertz", "asdf", "xyz", "", "  ", "9", "12a")) assertTrue(names(typed).isEmpty(), "\"$typed\" found ${names(typed)}")
    }

    @Test
    fun `a number inside a name is not a house number`() {
        assertEquals(listOf("Straße des 18. Oktober"), names("strasse des 18 oktober"))
        assertEquals(listOf("Straße des 18. Oktober 5"), names("str. des 18. Oktober 5"))
        // On the way there, "18" is not yet a house number of both.
        assertEquals("Straße des 18. Oktober", names("straße des 18").first())
        assertEquals(listOf("Straße des 17. Juni", "Straße des 18. Oktober"), names("str"))
    }

    @Test
    fun `the house number is what a street ends in`() {
        assertEquals("Karl-Heine-Straße" to "9", StreetMatcher.splitHouseNumber("Karl-Heine-Straße 9"))
        assertEquals("Karl-Heine-Straße" to "9a", StreetMatcher.splitHouseNumber(" Karl-Heine-Straße 9a "))
        assertEquals("Karl-Heine-Straße" to "12-14", StreetMatcher.splitHouseNumber("Karl-Heine-Straße 12-14"))
        assertEquals("Karl-Heine-Straße" to "12 b", StreetMatcher.splitHouseNumber("Karl-Heine-Straße 12 b"))
        assertEquals("Hainstr." to "1", StreetMatcher.splitHouseNumber("Hainstr.1"))
        assertEquals("Karl-Heine-Straße" to null, StreetMatcher.splitHouseNumber("Karl-Heine-Straße"))
        assertEquals("Straße des 18. Oktober" to null, StreetMatcher.splitHouseNumber("Straße des 18. Oktober"))
        assertEquals("Straße des 18. Oktober" to "5", StreetMatcher.splitHouseNumber("Straße des 18. Oktober 5"))
        assertEquals("12" to null, StreetMatcher.splitHouseNumber("12"))

        assertTrue(StreetMatcher.hasHouseNumber("Lützner Str. 51"))
        assertTrue(StreetMatcher.hasHouseNumber("Lützner Straße 51-53"))
        assertFalse(StreetMatcher.hasHouseNumber("Lützner Straße"))
        assertFalse(StreetMatcher.hasHouseNumber("Straße des 18. Oktober"))
    }
}
