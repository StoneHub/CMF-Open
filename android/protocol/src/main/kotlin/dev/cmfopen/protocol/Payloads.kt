package dev.cmfopen.protocol

/**
 * Payload builders and parsers, byte-compatible with src/cmfkit/protocol/payloads.py.
 * Strings are UTF-8, byte-truncated to the field size and zero-padded, as the firmware reads them.
 */
object Payloads {
    val POLL = byteArrayOf(0xA5.toByte())

    const val NOTIFICATION_TITLE_MAX = 20
    const val NOTIFICATION_BODY_MAX = 128
    const val WEATHER_CITY_MAX = 32
    const val MUSIC_FIELD_MAX = 64
    const val CONTACT_NAME_MAX = 32
    const val CONTACT_PHONE_MAX = 25
    const val CONTACTS_MAX = 20

    /** Notification icon ids the firmware knows. Everything else shows the generic icon. */
    object Icons {
        const val WHATSAPP = 8
        const val TELEGRAM = 12
        const val INSTAGRAM = 18
        const val GMAIL = 27
        const val GENERIC = 0xFF
    }

    const val MUSIC_STOPPED = 0
    const val MUSIC_PAUSED = 1
    const val MUSIC_PLAYING = 2

    private fun nowS() = System.currentTimeMillis() / 1000

    fun timeSync(nowS: Long = nowS(), utcOffsetS: Int = java.util.TimeZone.getDefault().getOffset(nowS * 1000) / 1000): ByteArray =
        Bytes.concat(Bytes.u32be(nowS), Bytes.u32be(utcOffsetS * 1000))

    fun notification(title: String, body: String, icon: Int = Icons.GENERIC, whenS: Long = nowS()): ByteArray {
        val t = Bytes.clip(title, NOTIFICATION_TITLE_MAX)
        return Bytes.concat(
            byteArrayOf(icon.toByte(), 0), Bytes.u32be(whenS), byteArrayOf(t.size.toByte()), t,
            Bytes.clip(body, NOTIFICATION_BODY_MAX),
        )
    }

    data class Notification(val icon: Int, val title: String, val body: String)

    fun parseNotification(p: ByteArray): Notification {
        val tlen = p.u8(6)
        return Notification(p.u8(0), String(p, 7, tlen, Charsets.UTF_8), String(p, 7 + tlen, p.size - 7 - tlen, Charsets.UTF_8))
    }

    fun musicInfo(track: String, artist: String, state: Int = MUSIC_PLAYING, volume: Int = 8, volumeMax: Int = 16): ByteArray =
        Bytes.concat(byteArrayOf(state.toByte(), volume.toByte(), volumeMax.toByte()), Bytes.fixed(track, MUSIC_FIELD_MAX), Bytes.fixed(artist, MUSIC_FIELD_MAX))

    data class MusicInfo(val state: Int, val track: String, val artist: String)

    fun parseMusicInfo(p: ByteArray) = MusicInfo(p.u8(0), Bytes.str(p.copyOfRange(3, 67)), Bytes.str(p.copyOfRange(67, 131)))

    data class Contact(val name: String, val phone: String = "")

    fun contacts(entries: List<Contact>): ByteArray =
        Bytes.concat(*entries.take(CONTACTS_MAX).map { Bytes.concat(Bytes.fixed(it.name, CONTACT_NAME_MAX), Bytes.fixed(it.phone, CONTACT_PHONE_MAX)) }.toTypedArray())

    fun parseContacts(p: ByteArray): List<Contact> = (0..p.size - 57 step 57).map {
        Contact(Bytes.str(p.copyOfRange(it, it + 32)), Bytes.str(p.copyOfRange(it + 32, it + 57)))
    }

    private fun temp(c: Int) = ((c + 100).coerceIn(0, 255)).toByte()

    /** Watch weather icon ids (from Gadgetbridge's field-tested CMF support). */
    object Weather {
        const val NONE = 0
        const val SUNNY = 1
        const val CLOUDY = 2
        const val OVERCAST = 3
        const val SHOWERS = 4
        const val SNOW = 5
        const val FOG = 6
        const val THUNDER = 9
        const val SLEET = 14
        const val HOT = 19
        const val COLD = 20
        const val WIND = 21
        const val NIGHT_MOON = 22
        const val NIGHT_STARS = 23
        const val NIGHT_CLOUDY = 24
        const val HAZE = 25
        const val SUN_CLOUD = 26
    }

    /**
     * One day of the forecast. [tempC] is "now" for today; the watch ignores it for later days.
     * [aqi] is the US AQI, [uv] the UV index, [windKmh] is not shown by the watch.
     */
    data class WeatherDay(
        val condition: Int,
        val tempC: Int,
        val highC: Int,
        val lowC: Int,
        val humidity: Int = 0,
        val aqi: Int = 0,
        val uv: Int = 0,
        val windKmh: Int = 0,
    )

    data class WeatherHour(val condition: Int, val tempC: Int)

    /** Sunrise and sunset for one day, as Unix seconds. */
    data class SunTimes(val riseS: Long, val setS: Long)

    /**
     * WEATHER_SET (FFFF:906B), 199 bytes, as the Watch Pro 2 / 3 Pro firmware reads it:
     *
     * * 7 days x 9 bytes: condition, temp, max, min, humidity %, AQI u16 BE, UV, wind (temps are C+100).
     *   Day 0 is today; days without data are `00 01 01 01 00 0000 00 00`.
     * * 24 hours x 2 bytes: temp, condition, starting with the current hour.
     * * city: UTF-8, at most 30 bytes, zero-padded to 32.
     * * 7 x (sunrise, sunset): Unix seconds, little-endian.
     *
     * Layout per Gadgetbridge's CMF support (field-tested); the earlier cmfkit guess had
     * min/max swapped, condition/temp swapped in the hourly records, and relative sun times.
     */
    fun weather(
        city: String,
        tempC: Int = 20,
        condition: Int = Weather.SUNNY,
        days: List<WeatherDay> = emptyList(),
        hours: List<WeatherHour> = emptyList(),
        sun: List<SunTimes> = emptyList(),
    ): ByteArray {
        val dayList = days.ifEmpty { listOf(WeatherDay(condition, tempC, tempC + 3, tempC - 3)) }
        val dayBytes = (0 until 7).map { i ->
            val d = dayList.getOrNull(i)
            if (d == null) byteArrayOf(0, 1, 1, 1, 0, 0, 0, 0, 0)
            else Bytes.concat(
                byteArrayOf(d.condition.toByte(), temp(d.tempC), temp(d.highC), temp(d.lowC), d.humidity.coerceIn(0, 100).toByte()),
                Bytes.u16be(d.aqi.coerceIn(0, 0xFFFF)),
                byteArrayOf(d.uv.coerceIn(0, 255).toByte(), d.windKmh.coerceIn(0, 255).toByte()),
            )
        }
        val now = WeatherHour(dayList[0].condition, dayList[0].tempC)
        val hourBytes = (0 until 24).map { i -> (hours.getOrNull(i) ?: now).let { byteArrayOf(temp(it.tempC), it.condition.toByte()) } }
        val sunBytes = (0 until 7).map { i ->
            val t = sun.getOrNull(i) ?: SunTimes(0, 0)
            Bytes.concat(Bytes.u32le(t.riseS), Bytes.u32le(t.setS))
        }
        val out = Bytes.concat(*dayBytes.toTypedArray(), *hourBytes.toTypedArray(), Bytes.fixed(String(Bytes.clip(city, 30), Charsets.UTF_8), WEATHER_CITY_MAX), *sunBytes.toTypedArray())
        check(out.size == 199) { "weather payload must be 199 bytes" }
        return out
    }

    /** Units shown on the watch (UNIT_LENGTH FFFF:9067 and UNIT_TEMPERATURE FFFF:9068). */
    fun units(imperial: Boolean) = byteArrayOf(1, if (imperial) 1 else 0)

    fun parseWeatherCity(p: ByteArray) = Bytes.str(p.copyOfRange(111, 143))

    data class Battery(val level: Int, val charging: Boolean)

    fun parseBattery(p: ByteArray) = Battery(p.u8(0), p.size > 1 && p[1].toInt() != 0)

    fun parseSerial(p: ByteArray) = String(p, 1, p.u8(0), Charsets.US_ASCII)

    fun parseFirmwareVersion(p: ByteArray) =
        if (p.size >= 4) (0 until 4).joinToString(".") { p.u8(it).toString() } else Bytes.hex(p)

    fun photoDialInit(size: Int, style: Int = 0, x: Int = 56, y: Int = 77, color565: Int = 0xFFFF): ByteArray = Bytes.concat(
        byteArrayOf(1), Bytes.u32be(size), byteArrayOf(-1, -1, -1, -1, 1, 1, 1),
        Bytes.u16be(style), Bytes.u16be(x), Bytes.u16be(y), Bytes.u16be(color565), ByteArray(8) { -1 },
    )

    fun structuredDialInit(size: Int, oldDialId: Long): ByteArray =
        Bytes.concat(byteArrayOf(1), Bytes.u32be(size), Bytes.u32be(oldDialId), byteArrayOf(1))

    data class ChunkRequest(val offset: Int, val length: Int)

    fun chunkRequest(p: ByteArray) = ChunkRequest(Bytes.readU32be(p, 0).toInt(), Bytes.readU32be(p, 4).toInt())

    fun buildChunkRequest(offset: Int, length: Int, progress: Int = 0) =
        Bytes.concat(Bytes.u32be(offset), Bytes.u32be(length), byteArrayOf(progress.toByte()))

    const val DIAL_CMD_LIST = 0x01
    const val DIAL_CMD_DELETE = 0x02

    fun dialList() = byteArrayOf(DIAL_CMD_LIST.toByte())
    fun dialDelete(id: Long) = Bytes.concat(byteArrayOf(DIAL_CMD_DELETE.toByte()), Bytes.u32be(id))

    data class Dial(val id: Long, val type: Int, val active: Boolean)

    fun parseDialList(p: ByteArray): List<Dial> {
        if (p.size < 2) return emptyList()
        val out = ArrayList<Dial>()
        var off = 2
        repeat(p.u8(1)) {
            if (off + 6 > p.size) return out
            out += Dial(Bytes.readU32be(p, off), p.u8(off + 4), p.u8(off + 5) == 1)
            off += 6
        }
        return out
    }
}
