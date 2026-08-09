package com.watchtastic.mesh

import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.mesh.model.SignalQuality
import com.watchtastic.mesh.model.signalQualityOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Node numbers are uint32 on the wire but Int in Kotlin, so anything above 2^31 arrives
 * negative. That is correct — the bits round-trip — but it means every id helper has to
 * survive negative input.
 */
class AddressingTest {

    @Test
    fun `node ids render as eight lowercase hex digits`() {
        assertEquals("!433d1e91", MeshConstants.nodeIdOf(0x433d1e91))
    }

    @Test
    fun `node numbers above two to the thirty-first survive the round trip`() {
        // 2233445566 as an unsigned uint32 is this negative Int.
        val big = -2061521730
        val id = MeshConstants.nodeIdOf(big)
        assertEquals(8, id.removePrefix("!").length)
        assertEquals(big, MeshConstants.nodeNumOf(id))
    }

    @Test
    fun `node id parsing rejects malformed input instead of guessing`() {
        assertNull(MeshConstants.nodeNumOf("!abc"))
        assertNull(MeshConstants.nodeNumOf(""))
        assertNull(MeshConstants.nodeNumOf("!zzzzzzzz"))
    }

    @Test
    fun `broadcast address is the all-ones uint32`() {
        assertEquals(-1, MeshConstants.BROADCAST_ADDR)
    }

    @Test
    fun `conversation keys round-trip for channels and direct threads`() {
        val channel = ConversationKey.channel(2)
        assertTrue(ConversationKey.isChannel(channel))
        assertEquals(2, ConversationKey.channelIndex(channel))

        val direct = ConversationKey.direct(-2061521730)
        assertTrue(!ConversationKey.isChannel(direct))
        assertEquals(-2061521730, ConversationKey.nodeNum(direct))
    }

    @Test
    fun `signal quality buckets follow SNR`() {
        assertEquals(SignalQuality.None, signalQualityOf(snr = 0f, rssi = 0))
        assertEquals(SignalQuality.Poor, signalQualityOf(snr = -18f, rssi = -120))
        assertEquals(SignalQuality.Excellent, signalQualityOf(snr = 9f, rssi = -50))
    }

    @Test
    fun `text payload limit stays inside the LoRa frame`() {
        // DATA_PAYLOAD_LEN is 233; the user-facing cap must leave envelope room.
        assertTrue(MeshConstants.MAX_TEXT_BYTES <= 233)
    }
}
