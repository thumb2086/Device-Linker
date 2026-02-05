package com.dlinker.app.crypto

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder

object QrCodeUtils {
    fun generateQrCode(text: String, size: Int = 512): Bitmap? {
        return try {
            val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val encoder = BarcodeEncoder()
            encoder.createBitmap(matrix)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
