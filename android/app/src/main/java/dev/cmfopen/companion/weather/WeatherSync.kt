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
import dev.cmfopen.protocol.Bytes
import dev.cmfopen.protocol.Payloads
import dev.cmfopen.protocol.Payloads.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fills every weather screen on the watch (now, hourly, 7-day, humidity, UV, air quality,
 * sunrise/sunset) from Open-Meteo, which is free and needs no key.
 *
 * Power: it only runs while the watch is connected, uses the phone's last known location (never
 * turns on GPS), refetches at most every 30 minutes, and only writes to the watch when the
 * forecast actually changed. A reconnect within 30 minutes resends the cached forecast instead
 * of fetching again.
 */
class WeatherSync(private val context: Context, private val prefs: Prefs, private val watch: WatchManager) {
    private var job: Job? = null

    data class Report(val place: String, val tempC: Int, val description: String, val at: Long)

    @Volatile var last: Report? = null
        private set

    private var cached: ByteArray? = null
    private var cachedAt = 0L
    private var sentToSession: Any? = null
    private var sentPayload: ByteArray? = null

    /** (Re)start the refresh loop. Called on every connect. */
    fun refreshSoon() {
        job?.cancel()
        job = watch.scope.launch {
            delay(2_000)
            while (isActive && watch.connected) {
                if (prefs.weatherEnabled && !watch.apps.usesWeatherTile) {
                    runCatching { syncOnce(force = false) }.onFailure { DebugLog.warn("weather: ${it.message}") }
                }
                delay(REFRESH_MS)
            }
        }
    }

    /** Fetch (or reuse a fresh cache) and send. [force] always fetches, e.g. after the city changed. */
    suspend fun syncOnce(force: Boolean = true) {
        val fresh = !force && cached != null && System.currentTimeMillis() - cachedAt < REFRESH_MS - 60_000
        val payload = if (fresh) cached!! else fetch() ?: return
        // Skip the write if this connection already has exactly this forecast.
        val session = watch.withSession("weather") { it }
        if (session != null && session === sentToSession && payload.contentEquals(sentPayload)) {
            DebugLog.info("weather: unchanged, not resent")
            return
        }
        if (watch.withSession("weather") { it.setWeather(payload) } != null) {
            sentToSession = session
            sentPayload = payload
        }
    }

    private suspend fun fetch(): ByteArray? {
        val (lat, lon, place) = resolvePlace() ?: run {
            DebugLog.warn("weather: no location (grant location or set a city)")
            return null
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m,uv_index,is_day" +
            "&hourly=temperature_2m,weather_code,relative_humidity_2m,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,wind_speed_10m_max" +
            "&forecast_days=7&timezone=auto&timeformat=unixtime"
        val j = JSONObject(get(url))
        val cur = j.getJSONObject("current")
        val tempC = cur.getDouble("temperature_2m").roundToInt()
        val code = cur.getInt("weather_code")
        val isDay = cur.optInt("is_day", 1) == 1

        val hourly = j.getJSONObject("hourly")
        val hTime = hourly.getJSONArray("time")
        val nowS = System.currentTimeMillis() / 1000
        val first = (0 until hTime.length()).firstOrNull { hTime.getLong(it) + 3600 > nowS } ?: 0
        val hours = (first until minOf(first + 24, hTime.length())).map { i ->
            Payloads.WeatherHour(
                condition(hourly.getJSONArray("weather_code").getInt(i), hourly.getJSONArray("is_day").optInt(i, 1) == 1),
                hourly.getJSONArray("temperature_2m").getDouble(i).roundToInt(),
            )
        }

        // Daily humidity isn't a daily variable, so average the hourly values per day.
        val daily = j.getJSONObject("daily")
        val dTime = daily.getJSONArray("time")
        val humidity = hourly.getJSONArray("relative_humidity_2m")
        fun dayHumidity(dayStart: Long): Int {
            val vals = (0 until hTime.length()).filter { hTime.getLong(it) in dayStart until dayStart + 86_400 }.mapNotNull { humidity.optDouble(it).takeIf { v -> !v.isNaN() } }
            return if (vals.isEmpty()) 0 else vals.average().roundToInt()
        }
        val aqi = runCatching { airQuality(lat, lon) }.getOrNull() ?: 0
        val days = (0 until minOf(7, dTime.length())).map { i ->
            val hi = daily.getJSONArray("temperature_2m_max").getDouble(i).roundToInt()
            val lo = daily.getJSONArray("temperature_2m_min").getDouble(i).roundToInt()
            Payloads.WeatherDay(
                condition = if (i == 0) condition(code, isDay) else condition(daily.getJSONArray("weather_code").getInt(i), true),
                tempC = if (i == 0) tempC else hi,
                highC = hi,
                lowC = lo,
                humidity = if (i == 0) cur.optDouble("relative_humidity_2m", 0.0).roundToInt() else dayHumidity(dTime.getLong(i)),
                aqi = if (i == 0) aqi else 0,
                uv = (if (i == 0) cur.optDouble("uv_index", 0.0) else daily.getJSONArray("uv_index_max").optDouble(i, 0.0)).roundToInt(),
                windKmh = (if (i == 0) cur.optDouble("wind_speed_10m", 0.0) else daily.getJSONArray("wind_speed_10m_max").optDouble(i, 0.0)).roundToInt(),
            )
        }
        val sun = (0 until minOf(7, dTime.length())).map { i ->
            Payloads.SunTimes(daily.getJSONArray("sunrise").optLong(i), daily.getJSONArray("sunset").optLong(i))
        }

        val payload = Payloads.weather(place, tempC, days[0].condition, days, hours, sun)
        cached = payload
        cachedAt = System.currentTimeMillis()
        last = Report(place, tempC, describe(code), cachedAt)
        DebugLog.info("weather: $place $tempC°C ${describe(code)}, ${days.size} days, ${hours.size} hours, AQI $aqi (${Bytes.hex(payload).take(18)}…)")
        return payload
    }

    private suspend fun airQuality(lat: Double, lon: Double): Int? {
        val j = JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi"))
        return j.optJSONObject("current")?.optDouble("us_aqi")?.takeIf { !it.isNaN() }?.roundToInt()
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
        private const val REFRESH_MS = 30 * 60_000L

        /** WMO weather code (Open-Meteo) -> watch icon id. Ids per Gadgetbridge's CMF mapping. */
        fun condition(wmo: Int, isDay: Boolean = true): Int = when (wmo) {
            0 -> if (isDay) Weather.SUNNY else Weather.NIGHT_STARS
            1 -> if (isDay) Weather.SUN_CLOUD else Weather.NIGHT_MOON
            2 -> if (isDay) Weather.SUN_CLOUD else Weather.NIGHT_CLOUDY
            3 -> Weather.OVERCAST
            45, 48 -> Weather.FOG
            in 51..55, in 61..65, in 80..82 -> Weather.SHOWERS
            56, 57, 66, 67 -> Weather.SLEET
            in 71..77, 85, 86 -> Weather.SNOW
            in 95..99 -> Weather.THUNDER
            else -> Weather.CLOUDY
        }

        fun describe(wmo: Int): String = when (wmo) {
            0 -> "Clear"
            1, 2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            in 51..55 -> "Drizzle"
            in 61..65, in 80..82 -> "Rain"
            56, 57, 66, 67 -> "Freezing rain"
            in 71..77, 85, 86 -> "Snow"
            in 95..99 -> "Thunderstorm"
            else -> "Cloudy"
        }

    }
}
