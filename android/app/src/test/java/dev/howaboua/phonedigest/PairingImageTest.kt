package dev.howaboua.phonedigest

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class PairingImageTest {
    private fun qr(text: String): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400)
        return IntArray(400 * 400) { if (matrix[it % 400, it / 400]) 0xff000000.toInt() else 0xffffffff.toInt() }
    }
    @Test fun screenshotDecoderPreservesPayloadAndRejectsBlankOrMultipleCodes() {
        val payload = """{"type":"phone-inbox","version":1,"endpoint":"https://example.com","phoneToken":"fake"}"""
        assertEquals(payload, PairingImage.decode(400, 400, qr(payload)))
        assertThrows(IllegalArgumentException::class.java) { PairingImage.decode(400, 400, IntArray(160000) { -1 }) }
        val first = qr(payload)
        val second = qr("another QR")
        val pixels = IntArray(800 * 400) { i -> val x = i % 800; if (x < 400) first[i / 800 * 400 + x] else second[i / 800 * 400 + x - 400] }
        assertThrows(IllegalArgumentException::class.java) { PairingImage.decode(800, 400, pixels) }
    }
}
