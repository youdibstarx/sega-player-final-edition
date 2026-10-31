package com.segaiptv.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Landscape activation/offline screen; all identifiers and actions stay on one page. */
@Composable
internal fun DeviceActivationScreen(identity: DeviceIdentity, error: String,
    onRefresh: () -> Unit, copyMac: () -> Unit, copyKey: () -> Unit) {
    val focus = remember { FocusRequester() }
    val bitmap = remember(identity.virtualMac, identity.key) {
        val matrix = deviceQrMatrix(identity.virtualMac, identity.key)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        val compact = maxHeight < 330.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("▶ SEGA PLAYER V3", color = v3Gold, fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 18.sp else 23.sp, modifier = Modifier.weight(1f))
                LanguagePicker()
            }
            Text(tr(if (error.isBlank()) R.string.activation else R.string.offline),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
                    Text(tr(R.string.activation_help), color = Color.LightGray, fontSize = 11.sp, maxLines = 2)
                    IdentityLine(tr(R.string.virtual_mac), identity.virtualMac, copyMac)
                    IdentityLine(tr(R.string.device_key), identity.key, copyKey)
                }
                BoxWithConstraints(Modifier.weight(.48f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val qrSize = minOf(maxWidth, maxHeight - 18.dp, 196.dp).coerceAtLeast(32.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Image(bitmap, tr(R.string.qr_description), Modifier.size(qrSize), filterQuality = FilterQuality.None)
                        Text(tr(R.string.scan_qr), color = Color.LightGray, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            if (error.isNotBlank()) Text(statusText(error), color = Color(0xFFFFC5AD), fontSize = 11.sp, maxLines = 2)
            TvAction("↻ " + tr(R.string.check_activation),
                Modifier.fillMaxWidth().focusRequester(focus), onClick = onRefresh)
            Text(tr(R.string.virtual_mac_note), color = Color.LightGray, fontSize = 10.sp)
        }
    }
}

@Composable
private fun IdentityLine(label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(v3Panel).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.LightGray, fontSize = 10.sp)
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        TvAction("⧉ " + tr(R.string.copy), onClick = onCopy)
    }
}
