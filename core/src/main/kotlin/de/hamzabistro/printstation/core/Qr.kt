package de.hamzabistro.printstation.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** A QR code as squares: [size] a side, without the white border. */
class Qr private constructor(val size: Int, private val squares: BooleanArray) {
    fun dark(row: Int, column: Int): Boolean = squares[row * size + column]

    companion object {
        /** [text] as a QR code, at the error correction the site's own codes have. */
        fun of(text: String): Qr {
            val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
            val size = matrix.width
            return Qr(size, BooleanArray(size * size) { i -> matrix.get(i % size, i / size).toInt() == 1 })
        }
    }
}
