package com.lanqi.weiassist.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

internal object QrDecoder {
    // UTF-8 for codes that don't declare a charset; ZXing's guess can read short Chinese text as Shift-JIS.
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")

    /** The text of the QR code in a camera frame's brightness plane ([luma] is packed, width × height), or null. */
    fun decode(luma: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text.takeIf { it.isNotEmpty() }
        } catch (_: ReaderException) {
            null
        }
    }
}
