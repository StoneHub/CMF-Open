package dev.cmfopen.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTest {
    @Test fun emptyEncryptedAckDecodesWithoutDecrypting() {
        // Seen on a Watch 3 Pro (fw 1.0.1.77) after every notification: 0065:0003 with no body.
        val dec = FrameDecoder().also { it.key = ByteArray(16) }
        val msgs = dec.feed(Bytes.fromHex("f500000065000100010003"))
        assertEquals(listOf(Message(Ops.APP_NOTIFICATION_ACK, ByteArray(0))), msgs)
    }

    @Test fun skipsStrayMagicBytesAndKeepsGoing() {
        val key = ByteArray(16) { 7 }
        val frame = Frame.encode(Ops.BATTERY, byteArrayOf(80, 1), key).single()
        val dec = FrameDecoder().also { it.key = key }
        val out = dec.feed(Bytes.concat(byteArrayOf(0xF5.toByte(), 0x7F, 0x7F), frame))
        assertEquals(Payloads.Battery(80, true), Payloads.parseBattery(out.single().payload))
    }

    @Test fun unknownOpcodesPassThrough() {
        val key = ByteArray(16) { 9 }
        val dec = FrameDecoder().also { it.key = key }
        val msg = dec.feed(Frame.encode(Ops.UNKNOWN_LANGUAGE, "en".toByteArray(), key).single()).single()
        assertEquals("en", String(msg.payload))
        assertEquals("UNKNOWN_LANGUAGE", Ops.nameOf(msg.op))
    }

    @Test fun largePayloadsSplitAndReassemble() {
        val key = ByteArray(16) { 3 }
        val payload = Payloads.weather("Somewhere", 12)
        val frames = Frame.encode(Ops.WEATHER_SET, payload, key, mtu = 64)
        assertTrue(frames.size > 1)
        frames.forEach { assertTrue(it.size <= 61) }
        val dec = FrameDecoder(plaintextCrcOnWire = true).also { it.key = key }
        assertEquals("Somewhere", Payloads.parseWeatherCity(frames.flatMap { dec.feed(it) }.single().payload))
    }

    @Test fun buttonsMapFromMessages() {
        assertEquals(WatchButton.Named.NEXT, WatchButton.from(Message(Ops.MUSIC_BUTTON, byteArrayOf(3))))
        assertEquals("raw:2a", WatchButton.from(Message(Ops.MUSIC_BUTTON, byteArrayOf(0x2A)))!!.id)
        assertEquals(WatchButton.Named.FIND_PHONE, WatchButton.from(Message(Ops.FIND_PHONE, byteArrayOf(1))))
    }
}
