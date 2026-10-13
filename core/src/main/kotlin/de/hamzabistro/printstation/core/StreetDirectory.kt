package de.hamzabistro.printstation.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Where a street lies against the outermost delivery ring. */
@Serializable
enum class StreetDelivery {
    @SerialName("inside") INSIDE,

    /** Some of it: which end a house is at, only check-address can say. */
    @SerialName("partly") PARTLY,
    @SerialName("outside") OUTSIDE,
}

/**
 * One of Leipzig's streets, as tools/streets/generate.mjs took it from
 * OpenStreetMap: its name as the city spells it, the postcodes along it
 * (the commonest first) and the district most of it is in.
 */
@Serializable
data class StreetEntry(
    val name: String,
    val postcodes: List<String> = emptyList(),
    val district: String? = null,
    val delivery: StreetDelivery = StreetDelivery.OUTSIDE,
)

/**
 * Leipzig's streets, built into the app: "Neue Bestellung" offers them
 * while a caller's street is typed, with no network and no geocoder asked.
 */
object StreetDirectory {
    private const val RESOURCE = "streets.json"

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
