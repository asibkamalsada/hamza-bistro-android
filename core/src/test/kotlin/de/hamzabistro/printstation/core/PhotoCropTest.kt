package de.hamzabistro.printstation.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** The site's own cases (photo.spec.ts): a wrong crop is still a valid photo — of the tablecloth. */
class PhotoCropTest {
    @Test
    fun `takes the middle of a landscape photo, not the left edge`() {
        assertEquals(SquareCrop(500, 0, 3000, SquareCrop.MAX_PX), SquareCrop.of(4000, 3000))
    }

    @Test
    fun `takes the middle of a portrait photo, not the ceiling`() {
        assertEquals(SquareCrop(0, 500, 3000, SquareCrop.MAX_PX), SquareCrop.of(3000, 4000))
    }

    @Test
    fun `shrinks a phone photo to the size the menu draws`() {
        assertEquals(SquareCrop.MAX_PX, SquareCrop.of(4032, 3024).out)
    }

    @Test
    fun `leaves a small photo alone rather than enlarging it`() {
        assertEquals(90, SquareCrop.of(90, 120).out)
    }

    @Test
    fun `crops nothing off a square photo`() {
        assertEquals(SquareCrop(0, 0, 500, SquareCrop.MAX_PX), SquareCrop.of(500, 500))
    }

    @Test
    fun `keeps an odd-sized photo's crop on whole pixels`() {
        assertEquals(1, SquareCrop.of(101, 100).x)
    }
}
