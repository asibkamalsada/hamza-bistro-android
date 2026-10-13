package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A street's house numbers: each with its own postcode and ring, where a long street has several of both. */
class HouseNumbersTest {
    private val inner = House("04177", DeliveryRing.INNER)
    private val near = House("04179", DeliveryRing.NEAR)
    private val outside = House("04205", DeliveryRing.OUTSIDE)

    /** A long street: two postcodes inside the area, and its far end outside. */
    private val long =
        StreetEntry(
            name = "Lützner Straße",
            postcodes = listOf("04177", "04179", "04205"),
            district = "Altlindenau",
            delivery = StreetDelivery.PARTLY,
            houses = "04177i:2 2a 4-7 7a 9 11;04179n:125 129 131-140 145a;04205x:300-310 312a",
        )

    // -----------------------------------------------------------------------
    // The list of one street
    // -----------------------------------------------------------------------

    @Test
    fun `a number is found as it is typed`() {
        assertEquals(listOf(inner), long.houses("2"))
        assertEquals(listOf(inner), long.houses("2a"))
        assertEquals(listOf(inner), long.houses("2 A"))
        assertEquals(listOf(inner), long.houses(" 9 "))
        assertEquals(listOf(near), long.houses("125"))
        assertEquals(listOf(near), long.houses("145a"))
        assertEquals(listOf(outside), long.houses("312a"))
        // A building given as a range is found by where it starts.
        assertEquals(listOf(inner), long.houses("4-6"))
        assertEquals(listOf(near), long.houses("131/133"))
    }

    @Test
    fun `a run of numbers holds every whole number in it, and only those`() {
        for (number in listOf("4", "5", "6", "7")) assertEquals(listOf(inner), long.houses(number), number)
        for (number in listOf("131", "135", "140")) assertEquals(listOf(near), long.houses(number), number)
        assertEquals(listOf(outside), long.houses("300"))
        assertEquals(listOf(outside), long.houses("310"))
        for (number in listOf("3", "8", "10", "130", "141", "299", "311")) assertEquals(emptyList(), long.houses(number), number)
    }

    @Test
    fun `a letter the list does not have is its neighbours' house`() {
        // 9 is there, 9b is not: the same door, or the one beside it.
        assertEquals(listOf(inner), long.houses("9b"))
        assertEquals(listOf(near), long.houses("133a"))
        // Only 145a and 312a are there: 145 and 312 are theirs.
        assertEquals(listOf(near), long.houses("145"))
        assertEquals(listOf(outside), long.houses("312"))
        // Typed on after the number: still the number.
        assertEquals(listOf(inner), long.houses("11 hinterhaus"))
    }

    @Test
    fun `the house itself is the answer, whatever stands beside it`() {
        // Across a postcode's edge: 9 on one side, 9a and 9b on the other; 7a apart from the run it lies in.
        val edge = StreetEntry("Grenzstraße", listOf("04177", "04179"), houses = "04177i:5-9;04179n:7a 9a 9b")
        assertEquals(listOf(inner), edge.houses("9"))
        assertEquals(listOf(near), edge.houses("9a"))
        assertEquals(listOf(near), edge.houses("7a"))
        assertEquals(listOf(inner), edge.houses("7"))
        // Neither is 9c's own: both neighbours are offered.
        assertEquals(listOf(inner, near), edge.houses("9c"))
    }

    @Test
    fun `what is not a house number, or not on the street, finds nothing`() {
        for (number in listOf("", " ", "a", "hinterhaus", "-", "0", "99999", "1")) assertEquals(emptyList(), long.houses(number), "\"$number\"")
        assertEquals(emptyList(), StreetEntry("Hainstraße", listOf("04109")).houses("1"))
        // A list written wrong is a list without that part, not a crash.
        assertEquals(listOf(inner), StreetEntry("X", houses = ";04177i:1;;04179:2;0417:3;04177q:4").houses("1"))
        assertEquals(emptyList(), StreetEntry("X", houses = ";04177i:1;;04179:2;0417:3;04177q:4").houses("2"))
    }

    @Test
    fun `two streets of one name are two houses`() {
        val gardens = StreetEntry("Rosenweg", listOf("04157", "04209"), houses = "04157e:6 8 12;04209f:6-9")
        assertEquals(listOf(House("04157", DeliveryRing.EDGE), House("04209", DeliveryRing.FAR)), gardens.houses("6"))
        assertEquals(listOf(House("04209", DeliveryRing.FAR)), gardens.houses("7"))
        assertEquals(listOf(House("04157", DeliveryRing.EDGE)), gardens.houses("12"))
    }

    // -----------------------------------------------------------------------
    // What the matcher offers with it
    // -----------------------------------------------------------------------

    private val matcher =
        StreetMatcher(
            listOf(
                long,
                StreetEntry("Lützner Plan", listOf("04179"), "Lindenau", StreetDelivery.INSIDE),
                StreetEntry("Rosenweg", listOf("04157", "04209"), "Gohlis", StreetDelivery.INSIDE, houses = "04157e:6 8 12;04209f:6-9"),
                StreetEntry("Karl-Härting-Straße", listOf("04318"), "Sellerhausen-Stünz", StreetDelivery.OUTSIDE),
            )
        )

    @Test
    fun `a house on the list brings its own postcode and ring`() {
        val here = matcher.match("lutzner 5").first()
        assertEquals("Lützner Straße 5", here.fullStreet)
        assertEquals(inner, here.house)
        assertEquals("04177", here.primaryPostcode)
        assertEquals(listOf("04177"), here.postcodes)
        assertEquals("inner", here.ringZone)
        // Not "partly" any more: this house is in.
        assertEquals(StreetDelivery.INSIDE, here.delivery)
        assertFalse(here.houseUnlisted)

        // Further down the same street: another postcode, another ring.
        val there = matcher.match("lützner str 131").first()
        assertEquals("04179", there.primaryPostcode)
        assertEquals("near", there.ringZone)
    }

    @Test
    fun `a house past the end of the area says so, on a street that is partly in it`() {
        val far = matcher.match("lützner straße 305").first()
        assertEquals("04205", far.primaryPostcode)
        assertEquals(StreetDelivery.OUTSIDE, far.delivery)
        assertNull(far.ringZone)
    }

    @Test
    fun `a number the list does not have is said, and the street is offered as before`() {
        val unknown = matcher.match("lutzner 999").first()
        assertEquals("Lützner Straße 999", unknown.fullStreet)
        assertNull(unknown.house)
        assertTrue(unknown.houseUnlisted)
        // The street's own: its commonest postcode, and "partly".
        assertEquals("04177", unknown.primaryPostcode)
        assertEquals(listOf("04177", "04179", "04205"), unknown.postcodes)
        assertEquals(StreetDelivery.PARTLY, unknown.delivery)
    }

    @Test
    fun `no number, or a street without numbers on the list, is the street alone`() {
        val street = matcher.match("lutzner str").first()
        assertNull(street.house)
        assertFalse(street.houseUnlisted)
        assertEquals(listOf("04177", "04179", "04205"), street.postcodes)

        // Nothing is known of this street's houses: nothing is said about number 7.
        val plan = matcher.match("lützner plan 7").first()
        assertEquals("Lützner Plan 7", plan.fullStreet)
        assertFalse(plan.houseUnlisted)
        assertEquals("04179", plan.primaryPostcode)

        val outside = matcher.match("karl herting 9").first()
        assertFalse(outside.houseUnlisted)
        assertEquals(StreetDelivery.OUTSIDE, outside.delivery)
    }

    @Test
    fun `a number on two streets of one name is offered once for each`() {
        val both = matcher.match("rosenweg 6")
        assertEquals(listOf("04157", "04209"), both.map { it.primaryPostcode })
        assertEquals(listOf("Rosenweg 6", "Rosenweg 6"), both.map { it.fullStreet })
        assertEquals(listOf("edge", "far"), both.map { it.ringZone })
        assertEquals(1, matcher.match("rosenweg 7").size)
    }

    // -----------------------------------------------------------------------
    // The list as built in
    // -----------------------------------------------------------------------

    private val streets = StreetDirectory.read()

    @Test
    fun `the built-in list's house numbers are written as they are read`() {
        val group = Regex("04[123]\\d\\d[infex]:(\\d{1,4}[a-z]?|\\d{1,4}-\\d{1,4})( (\\d{1,4}[a-z]?|\\d{1,4}-\\d{1,4}))*")
        var withHouses = 0
        for (street in streets) {
            if (street.houses.isEmpty()) continue
            withHouses++
            for (part in street.houses.split(';')) assertTrue(group.matches(part), "${street.name}: \"${part.take(60)}\"")
            // A street nobody rides to carries none: there is nothing its houses could add.
            assertFalse(street.delivery == StreetDelivery.OUTSIDE, "${street.name} is outside and has house numbers")
        }
        assertTrue(withHouses > 1_200, "$withHouses streets with house numbers")
        // Nearly every street the shop rides to has its numbers.
        val delivered = streets.filter { it.delivery != StreetDelivery.OUTSIDE }
        assertTrue(delivered.count { it.houses.isNotEmpty() } > delivered.size * 9 / 10, "${delivered.count { it.houses.isEmpty() }} of ${delivered.size} delivered streets without numbers")
    }

    @Test
    fun `houses around the shop are where they stand`() {
        val byName = streets.associateBy { it.name }
        assertEquals(listOf(House("04229", DeliveryRing.INNER)), byName.getValue("Karl-Heine-Straße").houses("9"))
        // One street, two postcodes: the list knows which house has which.
        assertEquals("04177", byName.getValue("Lützner Straße").houses("20").single().postcode)
        assertEquals("04179", byName.getValue("Lützner Straße").houses("150").single().postcode)
        assertEquals("04105", byName.getValue("Georg-Schumann-Straße").houses("1").single().postcode)
        // A street that only partly lies in the area has houses on both sides of its edge.
        val partly = streets.filter { it.delivery == StreetDelivery.PARTLY && it.houses.isNotEmpty() }
        assertTrue(partly.any { ":" in it.houses && Regex("04\\d{3}x:").containsMatchIn(it.houses) && Regex("04\\d{3}[infe]:").containsMatchIn(it.houses) })

        val found = StreetDirectory.matcher.match("lutzner 150").first()
        assertEquals("Lützner Straße 150", found.fullStreet)
        assertEquals("04179", found.primaryPostcode)
    }
}
