package dev.cmfopen.companion.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import dev.cmfopen.companion.DebugLog
import dev.cmfopen.companion.Prefs
import dev.cmfopen.companion.WatchManager
import dev.cmfopen.protocol.Payloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

/**
 * Weather for the watch's weather tile from Open-Meteo (free, no key), every 30 minutes while
 * connected. Uses the phone's last known location, or a city set in the app.
 */
class WeatherSync(private val context: Context, private val prefs: Prefs, private val watch: WatchManager) {
    private var job: Job? = null

    data class Report(val place: String, val tempC: Int, val description: String, val at: Long)

    @Volatile var last: Report? = null
        private set

    /** (Re)start the refresh loop with an immediate first fetch. */
    fun refreshSoon() {
        job?.cancel()
        job = watch.scope.launch {
            delay(2_000)
            while (isActive && watch.connected) {
                if (prefs.weatherEnabled && !watch.apps.usesWeatherTile) {
                    runCatching { syncOnce() }.onFailure { DebugLog.warn("weather: ${it.message}") }
                }
                delay(30 * 60_000L)
            }
        }
    }

    suspend fun syncOnce() {
        val (lat, lon, place) = resolvePlace() ?: run {
            DebugLog.warn("weather: no location (grant location or set a city)")
            return
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=7&timezone=auto"
        val j = JSONObject(get(url))
        val cur = j.getJSONObject("current")
        val tempC = Math.round(cur.getDouble("temperature_2m")).toInt()
        val code = cur.getInt("weather_code")
        val daily = j.getJSONObject("daily")
        val days = (0 until daily.getJSONArray("time").length()).map { i ->
            val lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt()
            val hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt()
            Payloads.WeatherDay(condition(daily.getJSONArray("weather_code").getInt(i)), if (i == 0) tempC else (lo + hi) / 2, lo, hi)
        }
        val hourly = j.getJSONObject("hourly")
        val start = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val n = hourly.getJSONArray("time").length()
        val hours = (start until minOf(start + 24, n)).map { i ->
            Payloads.WeatherHour(condition(hourly.getJSONArray("weather_code").getInt(i)), Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)).toInt())
        }
        val payload = Payloads.weather(place, tempC, condition(code), days, hours)
        watch.withSession("weather") { it.setWeather(payload) }
        last = Report(place, tempC, describe(code), System.currentTimeMillis())
        DebugLog.info("weather: $place $tempC°C ${describe(code)}")
    }

    private suspend fun resolvePlace(): Triple<Double, Double, String>? = withContext(Dispatchers.IO) {
        val city = prefs.weatherCity
        if (city.isNotBlank()) {
            val hit = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${URLEncoder.encode(city, "UTF-8")}"))
                .optJSONArray("results")?.optJSONObject(0) ?: throw IllegalStateException("can't find \"$city\"")
            return@withContext Triple(hit.getDouble("latitude"), hit.getDouble("longitude"), hit.getString("name"))
        }
        val loc = lastLocation() ?: return@withContext null
        val name = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: "Here"
        Triple(loc.latitude, loc.longitude, name)
    }

    private fun lastLocation(): Location? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /**
         * WMO weather code -> watch icon id. The watch's icon ids are NOT confirmed (docs/02 marks
         * the non-city weather fields as guesses); adjust here once checked on the wrist.
         */
        fun condition(wmo: Int): Int = when {
            wmo <= 1 -> 1 // clear
            wmo == 2 -> 2 // partly cloudy
            wmo == 3 -> 3 // overcast
            wmo <= 48 -> 4 // fog
            wmo <= 67 || wmo in 80..82 -> 5 // drizzle / rain / showers
            wmo <= 77 || wmo in 85..86 -> 6 // snow
            else -> 7 // thunderstorm
        }

        fun describe(wmo: Int): String = when {
            wmo <= 1 -> "Clear"
            wmo == 2 -> "Partly cloudy"
            wmo == 3 -> "Overcast"
            wmo <= 48 -> "Fog"
            wmo <= 67 || wmo in 80..82 -> "Rain"
            wmo <= 77 || wmo in 85..86 -> "Snow"
            else -> "Thunderstorm"
        }
    }
}
