package de.hamzabistro.printstation.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Where a street lies against the outermost delivery ring. */
@Serializable
enum class StreetDelivery {
    @SerialName("inside") INSIDE,

    /** Some of it: which end a house is at, its house number says ([StreetEntry.houses]), or check-address. */
    @SerialName("partly") PARTLY,
    @SerialName("outside") OUTSIDE,
}

/**
 * The delivery ring a house is in, from the inside out, by the letter the
 * list has for it and the name check-address gives the same ring.
 */
enum class DeliveryRing(val letter: Char, val zone: String) {
    INNER('i', "inner"),
    NEAR('n', "near"),
    FAR('f', "far"),
    EDGE('e', "edge"),

    /** Outside every ring: nobody rides there. */
    OUTSIDE('x', "outside"),
}

/** What is one house's own on a street that has several of each: its postcode, and its ring. */
data class House(val postcode: String, val ring: DeliveryRing)

/**
 * One of Leipzig's streets, as tools/streets/generate.mjs took it from
 * OpenStreetMap: its name as the city spells it, the postcodes along it
 * (the commonest first), the district most of it is in, and its house
 * numbers.
 */
@Serializable
data class StreetEntry(
    val name: String,
    val postcodes: List<String> = emptyList(),
    val district: String? = null,
    val delivery: StreetDelivery = StreetDelivery.OUTSIDE,
    /**
     * The house numbers, packed by postcode and ring: "04229i:1-4 4a 6;04177n:5 7".
     * "1-4" is every number from 1 to 4. Empty for a street nobody rides to
     * (its houses have nothing to add to that) and for one OpenStreetMap
     * has no numbers on.
     */
    val houses: String = "",
) {
    /**
     * Where house [number] is, as typed ("9", "9 a", "12-14"). More than one
     * answer where two streets of this name lie in different postcodes;
     * none when the list does not have the number. A letter the list does
     * not know ("9c" beside "9" and "9a") is its neighbours' house.
     */
    fun houses(number: String): List<House> {
        val wanted = HOUSE_NUMBER.find(number.lowercase().filterNot { it.isWhitespace() })?.value ?: return emptyList()
        val digits = wanted.takeWhile { it.isDigit() }
        val exact = mutableListOf<House>()
        val beside = mutableListOf<House>()
        for (group in houses.split(';')) {
            val numbers = group.substringAfter(':', "")
            val ring = DeliveryRing.entries.firstOrNull { it.letter == group.getOrNull(POSTCODE_LENGTH) }
            if (numbers.isEmpty() || ring == null) continue
            val house = House(group.take(POSTCODE_LENGTH), ring)
            for (token in numbers.split(' ')) {
                val to = token.substringAfter('-', "")
                if (to.isNotEmpty()) {
                    // A run of whole numbers: only a whole number is in it, but a lettered one stands beside it.
                    if (digits.toInt() in token.substringBefore('-').toInt()..to.toInt()) (if (wanted == digits) exact else beside).add(house)
                } else if (token == wanted) {
                    exact.add(house)
                } else if (token.takeWhile { it.isDigit() } == digits) {
                    beside.add(house)
                }
            }
        }
        return exact.ifEmpty { beside }.distinct()
    }
}

private const val POSTCODE_LENGTH = 5

/** The number a house is found by: "12" of "12-14", "9a" of "9 a". */
private val HOUSE_NUMBER = Regex("^\\d{1,4}[a-z]?")

/**
 * Leipzig's streets, built into the app: "Neue Bestellung" offers them
 * while a caller's street is typed, with no network and no geocoder asked.
 */
object StreetDirectory {
    /**
     * By its whole path, not by a name beside this class: the release
     * build's shrinker moves the class out of its package and leaves the
     * file where it is, and a name beside the class is then not found.
     */
    private const val RESOURCE = "/de/hamzabistro/printstation/core/streets.json"

    fun parse(text: String): List<StreetEntry> = json.decodeFromString(ListSerializer(StreetEntry.serializer()), text)

    /** The list as built in. */
    fun read(): List<StreetEntry> {
        val stream = checkNotNull(StreetDirectory::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE is not in the build" }
        return parse(stream.bufferedReader().use { it.readText() })
    }

    /**
     * The built-in list, ready to search. Read and indexed the first time
     * it is asked for, which takes a moment: ask off the main thread.
     */
    val matcher: StreetMatcher by lazy { StreetMatcher(read()) }
}
