package dev.cmfopen.protocol

import dev.cmfopen.protocol.Payloads.SunTimes
import dev.cmfopen.protocol.Payloads.WeatherDay
import dev.cmfopen.protocol.Payloads.WeatherHour
import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherTest {
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    @Test fun layoutMatchesTheFirmware() {
        val p = Payloads.weather(
            city = "Marietta",
            days = listOf(
                WeatherDay(Payloads.Weather.SHOWERS, tempC = 8, highC = 11, lowC = 5, humidity = 93, aqi = 42, uv = 2, windKmh = 14),
                WeatherDay(Payloads.Weather.SUNNY, tempC = 12, highC = 15, lowC = 3),
            ),
            hours = listOf(WeatherHour(Payloads.Weather.SHOWERS, 8), WeatherHour(Payloads.Weather.CLOUDY, -2)),
            sun = listOf(SunTimes(1_764_000_000, 1_764_036_000)),
        )
        assertEquals(199, p.size)
        // today: condition, temp, max, min, humidity, aqi u16 BE, uv, wind
        assertEquals(listOf(4, 108, 111, 105, 93, 0, 42, 2, 14), (0 until 9).map { u8(p, it) })
        // tomorrow
        assertEquals(listOf(1, 112, 115, 103), (9 until 13).map { u8(p, it) })
        // days without data
        assertEquals(listOf(0, 1, 1, 1, 0, 0, 0, 0, 0), (18 until 27).map { u8(p, it) })
        // hourly: temp then condition; missing hours repeat "now"
        assertEquals(listOf(108, 4, 98, 2, 108, 4), (63 until 69).map { u8(p, it) })
        assertEquals("Marietta", Payloads.parseWeatherCity(p))
        // sunrise/sunset little-endian Unix seconds
        assertEquals(Bytes.hex(Bytes.u32le(1_764_000_000)) + Bytes.hex(Bytes.u32le(1_764_036_000)), Bytes.hex(p.copyOfRange(143, 151)))
    }

    @Test fun longCityNamesAreCutTo30Bytes() {
        val p = Payloads.weather("Llanfairpwllgwyngyllgogerychwyrndrobwll", 10)
        assertEquals(30, Payloads.parseWeatherCity(p).length)
        assertEquals(0, u8(p, 141))
    }
}
