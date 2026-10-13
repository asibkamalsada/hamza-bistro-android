package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KoelnerPhonetikTest {
    @Test
    fun `the published examples come out as published`() {
        assertEquals("657", KoelnerPhonetik.encode("Müller"))
        assertEquals("65752682", KoelnerPhonetik.encode("Müller-Lüdenscheidt"))
        assertEquals("3412", KoelnerPhonetik.encode("Wikipedia"))
        assertEquals("17863", KoelnerPhonetik.encode("Breschnew"))
    }

    @Test
    fun `what sounds the same is the same`() {
        for ((heard, written) in listOf("Herting" to "Härting", "Carl" to "Karl", "Meier" to "Mayer", "Tschochersche" to "Zschochersche", "Kolwiz" to "Kollwitz")) {
            assertEquals(KoelnerPhonetik.encode(written), KoelnerPhonetik.encode(heard), "$heard / $written")
        }
        assertNotEquals(KoelnerPhonetik.encode("Heine"), KoelnerPhonetik.encode("Härting"))
    }

    @Test
    fun `c is k or z by its neighbours`() {
        // At the start before A, H, K, L, O, Q, R, U, X; inside a word not before L or R, and never after S or Z.
        assertEquals("457", KoelnerPhonetik.encode("Clara"))
        assertEquals("885", KoelnerPhonetik.encode("Cäcilie"))
        assertEquals("08", KoelnerPhonetik.encode("Asche"))
        assertEquals("448", KoelnerPhonetik.encode("Chicks"))
        // X is KS, and only S after another K.
        assertEquals("048", KoelnerPhonetik.encode("Axe"))
        assertEquals("0486", KoelnerPhonetik.encode("Ackxen"))
    }

    @Test
    fun `only letters count`() {
        assertEquals("", KoelnerPhonetik.encode(""))
        assertEquals("", KoelnerPhonetik.encode("18. 5"))
        assertEquals(KoelnerPhonetik.encode("KarlHeine"), KoelnerPhonetik.encode("karl-heine 9"))
        // ß is a held s.
        assertEquals(KoelnerPhonetik.encode("Strasse"), KoelnerPhonetik.encode("Straße"))
    }
}
