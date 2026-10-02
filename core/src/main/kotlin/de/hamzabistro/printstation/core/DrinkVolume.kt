package de.hamzabistro.printstation.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * A drink's size, for the "0,33 l · 6,52 €/l" under it (PAngV § 4,
 * hamza-bistro-web#69). Stored as whole millilitres in
 * `menu_items.volume_ml` (20261002140000_drink_volume.sql), null for food
 * and for a drink nobody has sized; the database refuses anything outside
 * 1–[MAX_ML], and so does the form, as /menu-admin does.
 */
object DrinkVolume {
    /** Ten litres: past it is a price typed into the wrong box. */
    const val MAX_ML = 10_000

    /** What the "Füllmenge (ml)" field says: blank is no size, a whole number is that many millilitres. */
    fun parse(text: String): Typed? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Typed(null)
        return trimmed.toIntOrNull()?.let(::Typed)
    }

    /** Whether the database takes it: null, or 1–[MAX_ML]. Never 0, which would say a size that is none. */
    fun allowed(ml: Int?): Boolean = ml == null || ml in 1..MAX_ML

    /**
     * "0,33 l · 6,52 €/l", or null with nothing to print: food, a drink not
     * sized, or a price that leaves nothing once the Pfand is off. The site's
     * unitPriceLine (src/app/unit-price.ts) in German: the price per litre is
     * worked out from the price without the deposit, which is refunded and so
     * no part of the Grundpreis, and rounded to the cent half up.
     */
    fun line(volumeMl: Int?, price: Double, deposit: Double): String? {
        if (volumeMl == null || volumeMl <= 0) return null
        val net = price - deposit
        if (!(net > 0)) return null
        val litres = DecimalFormat("0.###", DecimalFormatSymbols(Locale.GERMANY)).format(volumeMl / 1000.0)
        val perLitre = Math.round(net * 1000 / volumeMl * 100 + Math.ulp(1.0)) / 100.0
        val euros = NumberFormat.getCurrencyInstance(Locale.GERMANY).apply { currency = Currency.getInstance("EUR") }.format(perLitre)
        return "$litres l · $euros/l"
    }

    /** The field read: [ml] null for "no size". */
    data class Typed(val ml: Int?)
}
