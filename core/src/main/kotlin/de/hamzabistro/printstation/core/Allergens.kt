package de.hamzabistro.printstation.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One of the 14 allergens of LMIV Anhang II, by the letter the menu prints
 * after a dish (public.allergens, 20261002120000_allergens.sql in
 * hamza-bistro-web). The list is read from the database, so the legend, the
 * site's /menu-admin and this app all show the same letters.
 */
@Serializable
data class Allergen(
    val code: String,
    @SerialName("name_de") val nameDe: String,
    @SerialName("name_en") val nameEn: String,
) {
    fun name(german: Boolean): String = if (german) nameDe else nameEn
}

/**
 * What a dish or a choice says about allergens. Three states, not two, and
 * the third is the point — a dish nobody has looked at must never read to a
 * customer as a dish without allergens:
 *
 * - `null`: nobody has said yet; the menu prints "Angaben folgen";
 * - `[]`: stated, none of the 14;
 * - `["a", "g"]`: those, and nothing else.
 *
 * The ticks follow the site's allergen picker: unticking the last letter goes
 * back to `null`, and `[]` is only ever the "Keines der 14" box, so a form
 * saved untouched never claims "none".
 */
object Allergens {
    /** Ticks or unticks one letter. Kept sorted, as the database stores it. */
    fun toggle(current: List<String>?, code: String): List<String>? {
        val now = current.orEmpty()
        val next = if (code in now) now - code else (now + code).sorted()
        return next.ifEmpty { null }
    }

    /** The "Keines der 14" box: ticking it clears the letters; unticking it is "not stated" again. */
    fun toggleNone(current: List<String>?): List<String>? = if (current?.isEmpty() == true) null else emptyList()

    /** Null and [] differ — "not stated" against "none" — and so are unequal. */
    fun same(a: List<String>?, b: List<String>?): Boolean = if (a == null || b == null) a === b else a.sorted() == b.sorted()

    /**
     * What to send when a form that read [stored] is saved with [chosen]:
     * null when nothing changed and nothing need be sent, otherwise the one
     * change — which may itself be "not stated" again.
     */
    fun toSave(stored: List<String>?, chosen: List<String>?): Change? = if (same(stored, chosen)) null else Change(chosen)

    /** The rows nobody has stated anything for yet. */
    fun missing(values: Iterable<List<String>?>): Int = values.count { it == null }

    /** The value to write: the letters, `[]` for "none", or `null` for "not stated". */
    data class Change(val codes: List<String>?)
}
