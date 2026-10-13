package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneNumbersTest {
    /** The same cases as IC1 in hamza-bistro-web's supabase/rehearsal/checks.sql: the two must agree. */
    @Test
    fun `a number is the same caller with +49, 0049, (0) or a plain 0 in front`() {
        for (written in listOf("+49 176 1234567", "0049 176 1234567", "0176 1234567", "0176/123-45 67", "+49 (0) 176 1234567", "0049 0176 1234567")) {
            assertEquals("491761234567", PhoneNumbers.normalise(written), written)
        }
        assertEquals("49341123456", PhoneNumbers.normalise("0341 123456"))
        assertEquals("43660123", PhoneNumbers.normalise("+43 660 123"))
        assertEquals("43660123", PhoneNumbers.normalise("0043 660 123"))
        // "490" inside a number stays.
        assertEquals("491764901234", PhoneNumbers.normalise("0176 4901234"))
        assertEquals("", PhoneNumbers.normalise(""))
        assertEquals("", PhoneNumbers.normalise("unterdrückt"))
        assertEquals("", PhoneNumbers.normalise("+"))
        // Digits of another script are not digits to the database either.
        assertEquals("", PhoneNumbers.normalise("٠١٧٦"))
    }

    @Test
    fun `a number is shown the way it is dialled here`() {
        assertEquals("0176 1234567", PhoneNumbers.national("+491761234567"))
        assertEquals("0176 1234567", PhoneNumbers.national("0049 176 1234567"))
        assertEquals("0176 1234567", PhoneNumbers.national("01761234567"))
        assertEquals("0151 23456789", PhoneNumbers.national("+4915123456789"))
        assertEquals("0160 1234567", PhoneNumbers.national("+491601234567"))
        // A landline: where its prefix ends is not known.
        assertEquals("0341123456", PhoneNumbers.national("+49341123456"))
        assertEquals("+43660123", PhoneNumbers.national("0043 660 123"))
        assertEquals("anonym", PhoneNumbers.national(" anonym "))
    }

    @Test
    fun `a number is complete with the digits a short German one has`() {
        assertFalse(PhoneNumbers.complete(""))
        assertFalse(PhoneNumbers.complete("0176"))
        assertFalse(PhoneNumbers.complete("0341 123"))
        assertTrue(PhoneNumbers.complete("0341 1234"))
        assertTrue(PhoneNumbers.complete("0176 1234567"))
        assertTrue(PhoneNumbers.complete("+49 176 1234567"))
    }

    @Test
    fun `a text goes to a mobile, not to a German landline`() {
        for (mobile in listOf("0176 1234567", "+49 151 23456789", "0049 160 1234567", "+49 (0) 172 1234567")) assertTrue(PhoneNumbers.textable(mobile), mobile)
        // Landlines, and the numbers that are neither: free, shared-cost, the network's own.
        for (other in listOf("0341 1234567", "+49 30 123456", "0800 1234567", "0180 5123456", "032 123456789")) assertFalse(PhoneNumbers.textable(other), other)
        // From abroad, only the caller's network knows: a whole number is tried.
        assertTrue(PhoneNumbers.textable("+43 660 1234567"))
        assertFalse(PhoneNumbers.textable("+43 660"))
        assertFalse(PhoneNumbers.textable(""))
        assertFalse(PhoneNumbers.textable("0176"))
    }

    @Test
    fun `a text is sent to the number with its country in front`() {
        assertEquals("+491761234567", PhoneNumbers.international("0176 1234567"))
        assertEquals("+491761234567", PhoneNumbers.international("+49 (0) 176 1234567"))
        assertEquals("+436601234567", PhoneNumbers.international("0043 660 1234567"))
    }
}
