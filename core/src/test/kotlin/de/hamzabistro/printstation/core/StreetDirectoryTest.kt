package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The list as built in: what tools/streets/generate.mjs wrote, and what the matcher makes of it. */
class StreetDirectoryTest {
    private val streets = StreetDirectory.read()

    @Test
    fun `the built-in list is Leipzig's streets, each once, each with a postcode`() {
        assertTrue(streets.size in 2_500..4_000, "${streets.size} streets")
        assertEquals(streets.size, streets.distinctBy { it.name.lowercase() }.size, "a street twice")
        val leipzig = Regex("04[123]\\d\\d")
        for (street in streets) {
            assertTrue(street.name.isNotBlank() && street.name == street.name.trim(), "\"${street.name}\"")
            assertTrue(street.postcodes.isNotEmpty(), "${street.name} has no postcode")
            assertTrue(street.postcodes.all(leipzig::matches), "${street.name}: ${street.postcodes}")
            // What is not a street: a house number, a bus stop's note, a car park's lane.
            assertTrue(street.name.none { it in ":()„“\"" } && !street.name.last().isDigit(), street.name)
        }
    }

    @Test
    fun `streets around the shop are in it as the city spells them`() {
        val byName = streets.associateBy { it.name }
        assertEquals(listOf("04229"), byName.getValue("Karl-Heine-Straße").postcodes)
        assertEquals(StreetDelivery.INSIDE, byName.getValue("Karl-Heine-Straße").delivery)
        assertEquals("Plagwitz", byName.getValue("Karl-Heine-Straße").district)
        // Long enough for three postcodes: the commonest is the one filled in.
        assertEquals("04177", byName.getValue("Lützner Straße").postcodes.first())
        assertEquals(StreetDelivery.OUTSIDE, byName.getValue("Karl-Härting-Straße").delivery)
        assertEquals(StreetDelivery.PARTLY, byName.getValue("Georg-Schumann-Straße").delivery)
    }

    @Test
    fun `what is heard on the phone finds the street among all of them`() {
        val matcher = StreetDirectory.matcher
        val heard =
            mapOf(
                "karl heine 9" to "Karl-Heine-Straße 9",
                "carl heine" to "Karl-Heine-Straße",
                "karl herting 9" to "Karl-Härting-Straße 9",
                "herting 9" to "Karl-Härting-Straße 9",
                "lutzner 5" to "Lützner Straße 5",
                "kornerstr" to "Körnerstraße",
                "tschochersche" to "Zschochersche Straße",
                "eisenbahnstr 12" to "Eisenbahnstraße 12",
                "aisenbahn" to "Eisenbahnstraße",
                "schuhmann 4" to "Georg-Schumann-Straße 4",
                "demeringstr 44" to "Demmeringstraße 44",
                "gisserstr" to "Gießerstraße",
                "kalvisiusstr" to "Calvisiusstraße",
                "diskaustr 100" to "Dieskaustraße 100",
                "antonienstrase 3" to "Antonienstraße 3",
                "konnerizstr 5" to "Könneritzstraße 5",
                "kete kolwiz" to "Käthe-Kollwitz-Straße",
                "wiliam ziperer 12" to "William-Zipperer-Straße 12",
                "gutsmuts" to "GutsMuthsstraße",
                "zeigner" to "Erich-Zeigner-Allee",
                "strasse des 18 oktober 5" to "Straße des 18. Oktober 5",
                "bruel" to "Brühl",
            )
        for ((typed, street) in heard) assertEquals(street, matcher.match(typed).firstOrNull()?.fullStreet, "\"$typed\"")
        for (typed in listOf("qwertz", "asdf", "xyz")) assertTrue(matcher.match(typed).isEmpty(), "\"$typed\" found ${matcher.match(typed).map { it.fullStreet }}")
    }
}
