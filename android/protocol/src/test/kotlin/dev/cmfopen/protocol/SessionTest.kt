package dev.cmfopen.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionTest {
    @Test fun pairsThenReconnectsWithTheStoredKey() = runBlocking {
        val sim = SimWatch()
        val first = Session(sim, timeoutMs = 2_000)
        var prompted = false
        val key = first.pair(onPrompt = { prompted = true })
        assertTrue(prompted)
        assertTrue(sim.timeSynced)
        assertEquals("1.0.1.77", first.firmwareVersion())
        assertEquals(Payloads.Battery(87, false), first.battery())

        sim.disconnect()
        val again = Session(sim, timeoutMs = 2_000)
        again.login(key)
        assertEquals(87, again.battery().level)
    }

    @Test fun notificationsSurviveTheEmptyAckAndUnknownChatter() = runBlocking {
        val sim = SimWatch()
        val s = Session(sim, timeoutMs = 2_000)
        val unsolicited = ArrayList<Message>()
        s.onMessage { synchronized(unsolicited) { unsolicited += it } }
        s.pair()
        repeat(3) { s.notify("Text from Sam", "See you at 6 — bring the charger") }
        // A request after the acks proves the decoder is still in sync.
        assertEquals(87, s.battery().level)
        delay(100)
        assertEquals(3, sim.notifications.size)
        assertEquals("Text from Sam", sim.notifications[0].title)
        val ops = synchronized(unsolicited) { unsolicited.map { it.op }.toSet() }
        assertTrue(Ops.APP_NOTIFICATION_ACK in ops && Ops.UNKNOWN_LANGUAGE in ops, "got $ops")
    }

    @Test fun watchButtonsArriveAsUnsolicitedMessages() = runBlocking {
        val sim = SimWatch()
        val s = Session(sim, timeoutMs = 2_000)
        val pressed = ArrayList<WatchButton>()
        s.onMessage { m -> WatchButton.from(m)?.let { synchronized(pressed) { pressed += it } } }
        s.pair()
        sim.press(3)
        sim.press(0x42)
        delay(200)
        assertEquals(listOf("next", "raw:42"), synchronized(pressed) { pressed.map { it.id } })
    }

    @Test fun wrongSecretFailsCleanly() = runBlocking {
        val s = Session(SimWatch(), timeoutMs = 1_000)
        assertFailsWith<Exception> { s.pair(secret = ByteArray(16)) }
        Unit
    }
}
