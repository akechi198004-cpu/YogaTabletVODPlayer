package com.hm.video.remoteinput

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

object QrCode {
    /** Local-only QR generation; returns null so the caller can fall back to the manual URL. */
    fun bitmap(text: String, sizePx: Int): Bitmap? {
        if (text.isBlank() || sizePx <= 0) return null
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                // The URL is plain ASCII, so leave the default ISO-8859-1 encoding: it avoids an
                // ECI header that some older phone scanners refuse to decode.
                put(EncodeHintType.MARGIN, 1)
            }
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val row = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
