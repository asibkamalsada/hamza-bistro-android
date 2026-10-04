package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionsTest {
    @Test
    fun `compares part by part, as numbers`() {
        assertEquals(-1, AppVersions.compare("0.2.9", "0.2.57"))
        assertEquals(-1, AppVersions.compare("0.2.57", "0.3.0"))
        assertEquals(1, AppVersions.compare("0.3.0", "0.2.9"))
        assertEquals(0, AppVersions.compare("0.3", "0.3.0"))
        assertEquals(-1, AppVersions.compare("0.3", "0.3.1"))
    }

    @Test
    fun `leaves a suffix out of the comparison`() {
        assertEquals(0, AppVersions.compare("0.2.57-debug", "0.2.57"))
        assertEquals(0, AppVersions.compare("0.2.57+abc", "0.2.57-rc.1"))
        assertEquals(-1, AppVersions.compare("0.2.9-debug", "0.2.57"))
    }

    @Test
    fun `garbage is not a version`() {
        for (garbage in listOf(null, "", " ", "abc", "0.2.x", "1..2", ".1", "1.", "-debug", "v0.2.57", "0.2.99999999999")) {
            assertNull(AppVersions.parse(garbage), garbage)
            assertNull(AppVersions.compare(garbage, "0.2.57"), garbage)
        }
        assertEquals(listOf(0, 2, 57), AppVersions.parse(" 0.2.57 "))
    }

    @Test
    fun `outdated below the newest on the list, never when unknown`() {
        val all = listOf("0.2.57", "0.2.9", null, "junk", "0.2.57-debug")
        assertTrue(AppVersions.outdated("0.2.9", all))
        assertFalse(AppVersions.outdated("0.2.57", all))
        assertFalse(AppVersions.outdated("0.2.57-debug", all))
        assertFalse(AppVersions.outdated(null, all))
        assertFalse(AppVersions.outdated("junk", all))
        assertFalse(AppVersions.outdated("0.2.9", listOf("0.2.9", null)))
    }
}
