package de.hamzabistro.printstation.core

/**
 * A phone number as the phone reports it ("+491761234567"), as it is typed
 * in the shop ("0176 1234567") and as a customer wrote it on the site: one
 * caller, however it is written.
 */
object PhoneNumbers {
    /**
     * The number as its digits with the country in front, as
     * `public.normalise_phone` in hamza-bistro-web makes it, rule for rule:
     * "00" in front is the "+" spelled out, one "0" in front is a German
     * number without its country, and the "(0)" people write after "+49"
     * goes. Empty for a text without a digit.
     */
    fun normalise(phone: String): String {
        val digits = phone.filter { it in '0'..'9' }
        val abroad =
            when {
                digits.startsWith("00") -> digits.drop(2)
                digits.startsWith("0") -> "49" + digits.drop(1)
                else -> digits
            }
        return if (abroad.startsWith("490")) "49" + abroad.drop(3) else abroad
    }

    /**
     * The number as it is said and dialled here: "0176 1234567" for a German
     * mobile, "03411234567" for a landline (where the prefix ends, only a
     * list of every town's would say), "+43660123" for one from abroad. This
     * is what goes into an order's phone field.
     */
    fun national(phone: String): String {
        val digits = normalise(phone)
        if (!digits.startsWith("49")) return if (digits.isEmpty()) phone.trim() else "+$digits"
        val national = "0" + digits.drop(2)
        return if (MOBILE.matches(national)) national.take(4) + " " + national.drop(4) else national
    }

    /** Enough digits to be somebody's number, so its earlier addresses are worth asking for. */
    fun complete(phone: String): Boolean = normalise(phone).length >= COMPLETE_FROM

    /** German mobile prefixes are four digits: 015x, 016x, 017x. */
    private val MOBILE = Regex("01[567]\\d{5,}")

    /** The shortest German numbers still in use have eight digits with their prefix; with "49" for the "0", nine. */
    private const val COMPLETE_FROM = 9
}
