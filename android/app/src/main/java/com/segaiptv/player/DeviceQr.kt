package com.segaiptv.player

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/** Offline JSON payload. No URL, network service, device registration or identity mutation. */
internal fun deviceQrPayload(mac: String, key: String): String =
    JSONObject().put("mac", mac).put("device_key", key).toString()

internal fun deviceQrMatrix(mac: String, key: String): BitMatrix =
    QRCodeWriter().encode(deviceQrPayload(mac, key), BarcodeFormat.QR_CODE, 512, 512,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
