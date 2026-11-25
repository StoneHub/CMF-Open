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

    private fun temp(c: Int) = ((c + 100) and 0xFF).toByte()

    /** One day of the weather record. Only the temperature encoding is confirmed; field order is a best guess. */
    data class WeatherDay(val condition: Int, val tempC: Int, val lowC: Int, val highC: Int)
    data class WeatherHour(val condition: Int, val tempC: Int)

    /**
     * WEATHER_SET (FFFF:906B), 199 bytes: 7 x 9-byte days, 24 x 2-byte hours, city[32], 7 x sunrise/sunset.
     * The city string and temperature encoding are confirmed on hardware; the rest is a best guess.
     */
    fun weather(
        city: String,
        tempC: Int = 20,
        condition: Int = 1,
        days: List<WeatherDay> = emptyList(),
        hours: List<WeatherHour> = emptyList(),
    ): ByteArray {
        val today = WeatherDay(condition, tempC, tempC - 3, tempC + 3)
        val dayList = (days.ifEmpty { listOf(today) } + List(7) { days.lastOrNull() ?: today }).take(7)
        val hourList = (hours + List(24) { hours.lastOrNull() ?: WeatherHour(condition, tempC) }).take(24)
        val dayBytes = dayList.map { byteArrayOf(it.condition.toByte(), temp(it.tempC), temp(it.lowC), temp(it.highC)).copyOf(9) }
        val hourBytes = hourList.map { byteArrayOf(it.condition.toByte(), temp(it.tempC)) }
        val sun = Bytes.concat(Bytes.u32le(6 * 3600L), Bytes.u32le(18 * 3600L))
        val out = Bytes.concat(*dayBytes.toTypedArray(), *hourBytes.toTypedArray(), Bytes.fixed(city, WEATHER_CITY_MAX), *Array(7) { sun })
        check(out.size == 199) { "weather payload must be 199 bytes" }
        return out
    }

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
