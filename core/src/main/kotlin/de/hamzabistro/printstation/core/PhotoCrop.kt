package de.hamzabistro.printstation.core

/**
 * The menu draws a dish's photo in a small square, so a camera's 4 MB photo
 * is a hundred times more than any customer sees. The app crops and shrinks
 * it before it is uploaded, as the site's menu admin does (photo.ts).
 */
data class SquareCrop(
    /** Where the square starts in the source. */
    val x: Int,
    val y: Int,
    /** How big it is in the source. */
    val size: Int,
    /** How big it comes out. */
    val out: Int,
) {
    companion object {
        /** Twice the widest the menu draws a photo, with room for a larger layout. */
        const val MAX_PX = 256

        /** WebP at this quality is 10–20 kB for a dish. */
        const val QUALITY = 82

        /**
         * The middle square of a [width] × [height] photo, scaled to fit [max]
         * — never up, which only adds bytes. The middle rather than the top:
         * a plate photographed upright has the food in the middle, and the
         * menu's own crop takes the middle too.
         */
        fun of(width: Int, height: Int, max: Int = MAX_PX): SquareCrop {
            val size = minOf(width, height)
            return SquareCrop(
                x = Math.round((width - size) / 2.0).toInt(),
                y = Math.round((height - size) / 2.0).toInt(),
                size = size,
                out = minOf(size, max),
            )
        }
    }
}
