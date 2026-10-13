package de.hamzabistro.printstation.core

import java.text.Normalizer

/**
 * A street offered for what was typed, with the house number typed after
 * it — and, where the list has that number on the street, the [house]: its
 * own postcode and ring, which on a long street are not the street's.
 */
data class StreetSuggestion(val entry: StreetEntry, val houseNumber: String? = null, val house: House? = null) {
    /** What goes into the street field: the name as the city spells it, and the number. */
    val fullStreet: String
        get() = if (houseNumber == null) entry.name else "${entry.name} $houseNumber"

    /** The postcodes to show: the house's own, or all the street has. */
    val postcodes: List<String>
        get() = house?.let { listOf(it.postcode) } ?: entry.postcodes

    /** The postcode to fill in: the house's own, or else the one most of the street has. */
    val primaryPostcode: String?
        get() = postcodes.firstOrNull()

    /** Whether the shop rides there: the house's own answer where the list has the house, else the street's. */
    val delivery: StreetDelivery
        get() =
            when (house?.ring) {
                null -> entry.delivery
                DeliveryRing.OUTSIDE -> StreetDelivery.OUTSIDE
                else -> StreetDelivery.INSIDE
            }

    /** The house's ring, by check-address's name for it; null without a house on the list, or for one nobody rides to. */
    val ringZone: String?
        get() = house?.ring?.takeUnless { it == DeliveryRing.OUTSIDE }?.zone

    /**
     * The list has this street's numbers, and the one typed is not among
     * them: misheard, or a house OpenStreetMap does not have yet.
     */
    val houseUnlisted: Boolean
        get() = houseNumber != null && house == null && entry.houses.isNotEmpty()
}

/**
 * Finds the street somebody on the phone meant from what was typed of it:
 * half a name, without its umlauts, hyphens or "Straße", spelled the way it
 * sounded ("karl herting 9" is Karl-Härting-Straße 9), or with a slip of
 * the finger. The house number typed after it is kept, and looked up on the
 * street: a house the list has brings its own postcode and ring.
 *
 * What was typed is taken, in this order, as
 *
 *  1. the name, or the name less its "Straße";
 *  2. the beginning of the name;
 *  3. the beginning of one of the name's words ("heine", "zeigner");
 *  4. something in the middle of it ("keller" in Felsenkellerstraße);
 *  5. one of the above misspelled: a few letters off, more of them when it
 *     still sounds the same (Kölner Phonetik).
 *
 * Within each, the streets the shop delivers to come first, then the
 * shorter names. Guesses (5) are left out beside a name that was found,
 * and nonsense finds nothing rather than the nearest street.
 */
class StreetMatcher(streets: List<StreetEntry>) {
    /**
     * [key] is the name in [fold]'s letters; [starts] is where each of its
     * words begins in it, and [codes] how it sounds from there on.
     */
    private class Indexed(val entry: StreetEntry, val key: String, val base: String, val starts: IntArray, val codes: List<String>)

    private val index: List<Indexed> =
        streets.map { street ->
            val words = words(street.name)
            val key = words.joinToString("")
            // Not from "Straße" at the end: "str" would find every street there is.
            val searchable = if (words.size > 1 && words.last() == STRASSE) words.dropLast(1) else words
            val starts = IntArray(searchable.size)
            for (i in 1 until searchable.size) starts[i] = starts[i - 1] + searchable[i - 1].length
            Indexed(street, key, key.removeSuffix(STRASSE).ifEmpty { key }, starts, starts.map { KoelnerPhonetik.encode(key.substring(it)) })
        }

    /** The [limit] likeliest streets for [typed], the best first; none for less than two letters. */
    fun match(typed: String, limit: Int = 5): List<StreetSuggestion> {
        val (street, number) = splitHouseNumber(typed)
        val found = mutableListOf<StreetSuggestion>()
        // "Straße des 18" is on its way to "Straße des 18. Oktober", not house number 18 of some "Straße des".
        if (number != null) found += rank(typed, misspelled = false).map { StreetSuggestion(it) }
        for (entry in rank(street, misspelled = true)) {
            // Two streets of one name, in two postcodes, both with this number: both are offered.
            val houses = if (number == null) emptyList() else entry.houses(number)
            if (houses.isEmpty()) found += StreetSuggestion(entry, number) else houses.forEach { found += StreetSuggestion(entry, number, it) }
        }
        return found.distinctBy { it.entry.name to it.house?.postcode }.take(limit)
    }

    private class Hit(val street: Indexed, val tier: Int, val slips: Int = 0, val soundsAlike: Boolean = true)

    private fun rank(typed: String, misspelled: Boolean): List<StreetEntry> {
        val query = words(typed).joinToString("")
        if (query.length < 2) return emptyList()
        // "Straße" is the same in every name: it says nothing about how long what was typed is.
        val length = query.removeSuffix(STRASSE).ifEmpty { query }.length
        val sound = KoelnerPhonetik.encode(query)
        val slipsAllowed =
            when {
                !misspelled || length < 4 -> -1
                length <= 6 -> 1
                length <= 10 -> 2
                else -> 3
            }
        // Sounding the same forgives more: "kete kolwiz" is four letters off Käthe-Kollwitz.
        val slipsAllowedAlike = if (slipsAllowed < 0) -1 else maxOf(slipsAllowed, (length + 2) / 3)
        val hits = index.mapNotNull { hit(it, query, sound, slipsAllowed, slipsAllowedAlike) }
        // A name that was found needs no guesses beside it; the beginning of
        // one, only those a letter off that sound the same ("mayer": the Meyers).
        val best = hits.minOfOrNull { it.tier } ?: return emptyList()
        return hits
            .filter { it.tier < MISSPELLED || (best > WORD) || (best > EXACT && it.slips <= 1 && it.soundsAlike) }
            .sortedWith(
                compareBy<Hit>({ it.tier }, { it.slips }, { !it.soundsAlike }, { it.street.entry.delivery == StreetDelivery.OUTSIDE }, { it.street.base.length })
                    .thenBy { it.street.entry.name }
            )
            .map { it.street.entry }
    }

    private fun hit(street: Indexed, query: String, sound: String, slipsAllowed: Int, slipsAllowedAlike: Int): Hit? {
        val key = street.key
        if (key == query || street.base == query) return Hit(street, EXACT)
        if (key.startsWith(query)) return Hit(street, BEGINNING)
        if (street.starts.any { key.startsWith(query, it) }) return Hit(street, WORD)
        if (query.length >= 4 && query in street.base) return Hit(street, MIDDLE)
        if (slipsAllowedAlike < 0) return null
        var best: Hit? = null
        for ((i, start) in street.starts.withIndex()) {
            val slips = DamerauLevenshtein.toBeginning(query, key, start, slipsAllowedAlike)
            val alike = sound.length >= 2 && street.codes[i].startsWith(sound)
            if (slips > if (alike) slipsAllowedAlike else slipsAllowed) continue
            if (best == null || slips < best.slips || (slips == best.slips && alike && !best.soundsAlike)) best = Hit(street, MISSPELLED, slips, alike)
        }
        return best
    }

    companion object {
        private const val STRASSE = "strasse"

        private const val EXACT = 0
        private const val BEGINNING = 1
        private const val WORD = 2
        private const val MIDDLE = 3
        private const val MISSPELLED = 4

        private val ACCENTS = Regex("\\p{M}+")
        private val NOT_A_LETTER_OR_DIGIT = Regex("[^a-z0-9]+")

        /** "9", "9a", "12-14", "12 b" at the end, after a name. */
        private val HOUSE_NUMBER = Regex("""^(.*\p{L}\.?)\s*(\d+\s*(?:[-/]\s*\d+\s*)?\p{L}?)$""")

        /**
         * A name as it is compared: small letters without their accents and
         * dots, ß as ss, and ae, oe and ue as the a, o and u that ä, ö and ü
         * have become — so "Goethe", "Göthe" and "Gothe" are one name, as
         * they are on the phone.
         */
        private fun fold(text: String): String =
            Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
                .replace(ACCENTS, "")
                .replace("ae", "a")
                .replace("oe", "o")
                .replace("ue", "u")

        /** [text]'s words, folded, with "Str.", "-str" and "Strase" written out as the city writes them. */
        private fun words(text: String): List<String> =
            fold(text).split(NOT_A_LETTER_OR_DIGIT).filter { it.isNotEmpty() }.map { word ->
                when {
                    word.endsWith("strase") -> word.removeSuffix("strase") + STRASSE
                    word.endsWith("str") -> word + "asse"
                    else -> word
                }
            }

        /** [typed] as the street and the house number after it; no number when it does not end in one. */
        fun splitHouseNumber(typed: String): Pair<String, String?> {
            val text = typed.trim()
            val match = HOUSE_NUMBER.matchEntire(text) ?: return text to null
            return match.groupValues[1].trim() to match.groupValues[2].trim()
        }

        /** Whether [street] ends in a house number: without one there is no door to check. */
        fun hasHouseNumber(street: String): Boolean = splitHouseNumber(street).second != null
    }
}
