package dev.howaboua.phonedigest

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

object PairingImage {
    fun read(resolver: ContentResolver, uri: Uri): String {
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                require(output.size() + size <= 16 * 1024 * 1024) { "Choose a QR image smaller than 16 MB." }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("Could not open the QR image.")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inSampleSize = 1 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Choose a readable QR image." }
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Could not read the QR image.")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decode(bitmap.width, bitmap.height, pixels)
        } finally { bitmap.recycle() }
    }

    fun decode(width: Int, height: Int, pixels: IntArray): String {
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val codes = try { QRCodeMultiReader().decodeMultiple(image, mapOf(DecodeHintType.TRY_HARDER to true)) }
        catch (_: com.google.zxing.NotFoundException) { throw IllegalArgumentException("No QR code found. Choose a clear pairing QR image.") }
        require(codes.size == 1) { "Choose an image containing just one pairing QR code." }
        return codes.single().text
    }
}
