package com.galleryslim.share

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Draws a QR code as inline SVG so the laptop page needs no script library and works offline. */
object QrCodes {
    fun svg(payload: String, sizePx: Int = 320): String {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" width="$sizePx" height="$sizePx" shape-rendering="crispEdges">""")
        sb.append("""<rect width="100%" height="100%" fill="#fff"/><path fill="#111" d="""")
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) sb.append("M$x ${y}h1v1h-1z")
        sb.append("\"/></svg>")
        return sb.toString()
    }
}
