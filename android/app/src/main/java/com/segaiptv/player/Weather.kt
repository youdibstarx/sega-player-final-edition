package com.segaiptv.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import java.util.Locale

internal data class WeatherPlace(val name: String, val lat: Double, val lon: Double)
internal data class WeatherReading(val temperature: Double, val symbol: String, val time: String)
internal object WeatherRepository {
    val places = listOf(
        WeatherPlace("Casablanca", 33.59, -7.62), WeatherPlace("Rabat",34.02,-6.84),
        WeatherPlace("Marrakech",31.63,-8.00), WeatherPlace("Tanger",35.76,-5.83),
        WeatherPlace("Fès",34.03,-5.00), WeatherPlace("Agadir",30.43,-9.60),
        WeatherPlace("Paris",48.86,2.35), WeatherPlace("Madrid",40.42,-3.70),
        WeatherPlace("London",51.51,-0.13), WeatherPlace("Riyadh",24.71,46.68))
    private val mutex = Mutex()
    private fun prefs(context: Context) = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    fun place(context: Context): WeatherPlace? = runCatching {
        val raw = prefs(context).getString("place", null) ?: return null
        val obj = JSONObject(raw)
        WeatherPlace(obj.getString("name"),obj.getDouble("lat"),obj.getDouble("lon"))
    }.getOrNull()
    fun select(context: Context, place: WeatherPlace?) {
        val p = prefs(context).edit()
        if (place == null) p.remove("place") else {
            require(place.name.isNotBlank() && place.name.length <= 80 && place.lat.isFinite() && place.lon.isFinite())
            require(place.lat in -90.0..90.0 && place.lon in -180.0..180.0)
            p.putString("place",JSONObject().put("name",place.name).put("lat",place.lat).put("lon",place.lon).toString())
        }
        p.apply()
    }
    fun parse(raw: String, now: Instant = Instant.now()): WeatherReading {
        val rows = JSONObject(raw).getJSONObject("properties").getJSONArray("timeseries")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }
            .minByOrNull { kotlin.math.abs(Instant.parse(it.getString("time")).epochSecond - now.epochSecond) }
            ?: error("Weather unavailable")
        require(kotlin.math.abs(Instant.parse(row.getString("time")).epochSecond - now.epochSecond) < 4 * 3600)
        val data = row.getJSONObject("data")
        return WeatherReading(data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature"),
            data.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code")
                ?: data.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code").orEmpty(), row.getString("time"))
    }
    suspend fun fetch(context: Context, place: WeatherPlace): WeatherReading = withContext(Dispatchers.IO) {
        mutex.withLock {
            val p = prefs(context)
            val location = String.format(Locale.ROOT,"%.2f,%.2f",place.lat,place.lon)
            val now = System.currentTimeMillis()
            val saved = p.getString("data", null)
            if (p.getString("location", "") == location && now - p.getLong("fetched",0) in 0 until 3 * 3600_000L && saved != null)
                return@withLock parse(saved)
            val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${location.substringBefore(',')}&lon=${location.substringAfter(',')}"
            val con = NetworkClient.open(URL(url))
            con.setRequestProperty("User-Agent", "SEGAPlayer/0.5.17 https://github.com/youdibstar/sega-player-final-edition")
            try {
                check(con.responseCode == 200)
                val raw = con.inputStream.bufferedReader().use { reader ->
                    val result = StringBuilder(); val buffer = CharArray(8192)
                    while (true) { val n = reader.read(buffer); if(n < 0) break
                        require(result.length+n <= 2*1024*1024); result.append(buffer,0,n) }
                    result.toString()
                }
                val reading = parse(raw)
                p.edit().putString("data",raw).putString("location",location).putLong("fetched",now).apply()
                reading
            } finally { con.disconnect() }
        }
    }
    fun icon(symbol: String): String = when {
        "thunder" in symbol -> "ϟ"; "snow" in symbol -> "❄"; "rain" in symbol -> "☂"
        "clearsky" in symbol -> if ("night" in symbol) "☾" else "☀"
        else -> "☁"
    }
}
