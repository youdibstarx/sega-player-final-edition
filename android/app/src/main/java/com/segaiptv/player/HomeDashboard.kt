package com.segaiptv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HomeClockWeather() {
    val context = LocalContext.current
    val language = LocalAppLanguage.current.code
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    var place by remember { mutableStateOf(WeatherRepository.place(context)) }
    var weather by remember { mutableStateOf<WeatherReading?>(null) }
    var error by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(1000) } }
    LaunchedEffect(place) {
        weather = null; error = false
        val selected = place ?: return@LaunchedEffect
        while (true) {
            try { weather = WeatherRepository.fetch(context, selected); error = false }
            catch (e: Exception) { if (e is CancellationException) throw e; weather = null; error = true }
            delay(3 * 3600_000L)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(horizontalAlignment = Alignment.End) {
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 27.sp, fontWeight = FontWeight.Light)
            Text(now.format(DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.forLanguageTag(language))), fontSize = 10.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val reading = weather
            val label = when {
                place == null -> tr(R.string.weather_choose)
                error -> tr(R.string.weather_unavailable)
                reading == null -> tr(R.string.weather_loading)
                else -> "${WeatherRepository.icon(reading.symbol)} ${kotlin.math.round(reading.temperature).toInt()}°C · ${place!!.name}"
            }
            TvAction(label, Modifier.widthIn(max = 200.dp)) { choosing = true }
            if (place != null) Text(tr(R.string.weather_attribution), fontSize = 8.sp, color = Color.LightGray)
        }
    }
    if (choosing) WeatherPlaceDialog(onClose = { choosing = false }) {
        WeatherRepository.select(context, it); place = it; choosing = false
    }
}

@Composable
private fun WeatherPlaceDialog(onClose: () -> Unit, onSelect: (WeatherPlace?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    val latitude = lat.toDoubleOrNull()
    val longitude = lon.toDoubleOrNull()
    AlertDialog(onDismissRequest = onClose, title = { Text(tr(R.string.weather_choose)) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr(R.string.weather_privacy))
            WeatherRepository.places.forEach { p -> TvAction(p.name, Modifier.fillMaxWidth()) { onSelect(p) } }
            Text(tr(R.string.weather_custom))
            OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(tr(R.string.weather_city)) })
            OutlinedTextField(lat, { lat = it.take(16) }, singleLine = true, label = { Text(tr(R.string.weather_lat)) })
            OutlinedTextField(lon, { lon = it.take(16) }, singleLine = true, label = { Text(tr(R.string.weather_lon)) })
            if (name.isNotBlank() && latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0)
                TvAction(tr(R.string.order_save)) { onSelect(WeatherPlace(name.trim(),latitude,longitude)) }
            TvAction(tr(R.string.weather_disable)) { onSelect(null) }
        } }, confirmButton = { TextButton(onClick = onClose) { Text(tr(R.string.close)) } })
}

@Composable
internal fun SubscriptionTile(config: AppConfig, modifier: Modifier, onClick: () -> Unit) {
    TvActionCard(modifier, onClick) {
        Text("✦", color = appTheme.accent, fontSize = 44.sp)
        Text(tr(R.string.subscriptions_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(config.playlists.firstOrNull()?.name ?: tr(R.string.no_playlists), fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (config.playlists.size > 1) Text("+${config.playlists.size - 1}", fontSize = 11.sp, color = appTheme.accent)
    }
}

@Composable
private fun TvActionCard(modifier: Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Material button supplies focus, touch and D-pad semantics, with a single accessible target.
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        contentPadding = PaddingValues(10.dp)) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(appTheme.accent.copy(alpha = .12f),Color.Transparent))),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
    }
}

@Composable
internal fun SubscriptionStrip(config: AppConfig) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.widthIn(max = 175.dp)) {
            Text(tr(R.string.app_activation_expiry), fontSize = 10.sp, color = Color.LightGray)
            Text(config.expires.substringBefore('T').substringBefore(' ').ifBlank { tr(R.string.unspecified) }, fontSize = 12.sp)
        }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(config.playlists.take(30), key = { it.id }) { playlist ->
                SubscriptionCard(playlist, Modifier.width(225.dp), compact = true)
            }
        }
    }
}

@Composable
internal fun SubscriptionCard(playlist: Playlist, modifier: Modifier = Modifier, compact: Boolean = false,
    active: Boolean = false) {
    var info by remember(playlist.url) { mutableStateOf<SubscriptionInfo?>(null) }
    var now by remember { mutableLongStateOf(java.time.Instant.now().epochSecond) }
    val language = LocalAppLanguage.current.code
    LaunchedEffect(playlist.url) {
        while (true) { info = SubscriptionRepository.fetch(playlist); delay(15 * 60_000L) }
    }
    LaunchedEffect(Unit) { while (true) { now = java.time.Instant.now().epochSecond; delay(60_000) } }
    val value = info
    val expiry = value?.expiry
    val status = when {
        value == null -> tr(R.string.subscription_loading)
        !value.available -> tr(R.string.subscription_unknown)
        value.status.equals("Expired", true) || expiry != null && expiry <= now -> tr(R.string.subscription_expired)
        value.status.equals("Banned",true) || value.status.equals("Disabled",true) -> tr(R.string.inactive)
        value.unlimited -> tr(R.string.subscription_unlimited)
        expiry != null -> SubscriptionRepository.date(expiry, language) + " · " + tr(R.string.subscription_days, SubscriptionRepository.daysRemaining(expiry,now).toInt())
        else -> tr(R.string.subscription_unknown)
    }
    Row(modifier.background(appTheme.surface, RoundedCornerShape(12.dp)).padding(if (compact) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(if (compact) 34.dp else 46.dp)
            .background(appTheme.accent.copy(alpha = .18f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Text("▤", color = appTheme.accent, fontSize = if (compact) 20.sp else 26.sp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(playlist.name, Modifier.weight(1f, fill = false), fontSize = if (compact) 12.sp else 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (active) Text(tr(R.string.playlist_active), color = appTheme.accent, fontSize = 9.sp, maxLines = 1)
            }
            Text(tr(R.string.provider_expiry), fontSize = 9.sp, color = Color.LightGray)
            Text(status, fontSize = if (compact) 10.sp else 13.sp, maxLines = 2)
        }
    }
}
