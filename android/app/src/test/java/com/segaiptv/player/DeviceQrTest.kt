package com.segaiptv.player

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceQrTest {
    @Test fun generatedQrDecodesToExactIdentifiers() {
        val mac = "02:00:00:00:00:01"
        val key = "ABC234"
        val matrix = deviceQrMatrix(mac, key)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
            RGBLuminanceSource(matrix.width, matrix.height, pixels)))).text
        val payload = JSONObject(decoded)
        assertEquals(mac, payload.getString("mac"))
        assertEquals(key, payload.getString("device_key"))
        assertEquals(2, payload.length())
        assertFalse(decoded.contains("http"))
    }
    @Test fun existingLongKeysArePreservedWithoutTruncation() {
        val key = "existing-legacy-device-key-0123456789"
        assertEquals(key, JSONObject(deviceQrPayload("02:00:00:00:00:02", key)).getString("device_key"))
    }
}
