package com.lanqi.weiassist.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val size = 400

    private fun frame(text: String): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        return ByteArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0 else -1 }
    }

    @Test fun readsAQrCode() = assertEquals("https://example.com/a?b=1", QrDecoder.decode(frame("https://example.com/a?b=1"), size, size))

    @Test fun readsChinese() = assertEquals("小维助手，你好", QrDecoder.decode(frame("小维助手，你好"), size, size))

    @Test fun aFrameWithoutACodeIsNull() = assertNull(QrDecoder.decode(ByteArray(size * size) { -1 }, size, size))
}
