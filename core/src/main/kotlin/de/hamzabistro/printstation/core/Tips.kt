package de.hamzabistro.printstation.core

import kotlin.math.roundToLong

/**
 * A tip paid on the card (20261128110000_card_tips.sql in hamza-bistro-web):
 * the customer pays more than the bill on S-POS ("mach 25"), the Sparkasse
 * pays it all out, and the difference is the tip of whoever took the
 * payment, handed out in cash from the drawer at the end of the shift. Not
 * revenue: the order's total stays the bill. The Kassensturz puts it into
 * the card money and takes it from the cash.
 */
object Tips {
    /** The most a card tip may be (orders_tip_check); more is HB507. */
    const val MAX = 50.0

    /** The migration that brings the tip; a database before it answers PGRST202/42703. */
    const val MIGRATION = "20261128110000_card_tips"

    private val AMOUNT = Regex("""^\d+([.,]\d{1,2})?$""")

    /**
     * The tip in what S-POS charged, as staff typed it ("25", "25,50 €"),
     * for a bill of [total]: 0.0 when they are the same, null when it is no
     * tip — not an amount, less than the bill, or more than [MAX] on top.
     * Worked out in cents, so 10,30 for 10,10 is 0,20, not 0,2000000001.
     */
    fun from(charged: String, total: Double): Double? {
        val text = charged.replace(Regex("""\s|€"""), "")
        if (!AMOUNT.matches(text)) return null
        val cents = (text.replace(',', '.').toDouble() * 100).roundToLong() - (total * 100).roundToLong()
        return if (cents < 0 || cents > (MAX * 100).roundToLong()) null else cents / 100.0
    }

    /** What a step or a call sends: the tip, only with a card and only when there is one. */
    fun sent(tip: Double?, card: Boolean): Double? = tip?.takeIf { card && it > 0.0 }

    /** What a tip field starts with: the bill, "22,50" — no tip yet. */
    fun start(total: Double): String = String.format(java.util.Locale.GERMANY, "%.2f", total)
}

/** The report's card tips over its days (staff_report, 20261128110000): the staff's, no revenue. */
@kotlinx.serialization.Serializable
data class ReportTips(val orders: Int = 0, val amount: Double = 0.0)
