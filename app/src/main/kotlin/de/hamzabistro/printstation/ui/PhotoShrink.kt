package de.hamzabistro.printstation.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import de.hamzabistro.printstation.core.Photo
import de.hamzabistro.printstation.core.SquareCrop
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The picked photo as the menu wants it: the middle square, at most
 * [SquareCrop.MAX_PX] across, as WebP — 10–20 kB instead of a camera's
 * 4 MB. Decoded at a fraction of its size to begin with, so a 50-megapixel
 * photo does not have to fit in memory whole. Null when it is not a picture
 * this device can read.
 */
fun shrinkPhoto(resolver: ContentResolver, uri: Uri): Photo? {
    val bitmap =
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                // Pixels the app can read back, to crop and to encode.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val shortest = minOf(info.size.width, info.size.height)
                var sample = 1
                while (shortest / (sample * 2) >= SquareCrop.MAX_PX) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
    val crop = SquareCrop.of(bitmap.width, bitmap.height)
    if (crop.size == 0) return null
    val square = Bitmap.createBitmap(bitmap, crop.x, crop.y, crop.size, crop.size)
    val scaled = Bitmap.createScaledBitmap(square, crop.out, crop.out, true)
    val bytes =
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, SquareCrop.QUALITY, out)
            out.toByteArray()
        }
    return Photo(bytes, "image/webp")
}
