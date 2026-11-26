package com.watchtastic.service

import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.MsgStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageAlertPolicyTest {

    private var now = 1_000_000L
    private val policy = MessageAlertPolicy { now }

    private fun msg(conversation: String, pki: Boolean = false, outgoing: Boolean = false) =
        ChatMessage(
            id = 1, conversation = conversation, fromNum = 7, toNum = 9, channel = 0,
            text = "hi", timeMs = 0, outgoing = outgoing, status = MsgStatus.Received,
            pkiEncrypted = pki,
        )

    private fun node(favorite: Boolean) = MeshNode(
        num = 7, userId = "!00000007", longName = "n", shortName = "n", isFavorite = favorite,
    )

    // ------------------------------------------------------------------- trust

    @Test
    fun `a PKI-encrypted direct message is verified`() {
        assertEquals(SenderTrust.Verified, SenderTrust.of(msg("dm:7", pki = true), node(false)))
    }

    @Test
    fun `a plain direct message from a stranger is unknown`() {
        assertEquals(SenderTrust.Unknown, SenderTrust.of(msg("dm:7"), node(false)))
        assertEquals(SenderTrust.Unknown, SenderTrust.of(msg("dm:7"), null))
    }

    @Test
    fun `a favourite is added`() {
        assertEquals(SenderTrust.Added, SenderTrust.of(msg("dm:7"), node(true)))
        assertEquals(SenderTrust.Added, SenderTrust.of(msg("ch:0"), node(true)))
    }

    @Test
    fun `a channel message is never verified, whatever its flags say`() {
        assertEquals(SenderTrust.Unknown, SenderTrust.of(msg("ch:0", pki = true), node(false)))
    }

    // ---------------------------------------------------------------- decisions

    @Test
    fun `known senders buzz, rate-limited`() {
        assertEquals(AlertDecision.Buzz, policy.decide(SenderTrust.Added, knownOnly = true))
        now += 1_000
        assertEquals(AlertDecision.Silent, policy.decide(SenderTrust.Verified, knownOnly = true))
        now += MessageAlertPolicy.KNOWN_BUZZ_INTERVAL_MS
        assertEquals(AlertDecision.Buzz, policy.decide(SenderTrust.Added, knownOnly = true))
    }

    @Test
    fun `known-only mode lets unknown senders in silently`() {
        assertEquals(AlertDecision.Silent, policy.decide(SenderTrust.Unknown, knownOnly = true))
    }

    @Test
    fun `without known-only an unknown sender may buzz once in a while`() {
        assertEquals(AlertDecision.Buzz, policy.decide(SenderTrust.Unknown, knownOnly = false))
        now += MessageAlertPolicy.UNKNOWN_CARD_INTERVAL_MS
        assertEquals(AlertDecision.Silent, policy.decide(SenderTrust.Unknown, knownOnly = false))
        now += MessageAlertPolicy.UNKNOWN_BUZZ_INTERVAL_MS
        assertEquals(AlertDecision.Buzz, policy.decide(SenderTrust.Unknown, knownOnly = false))
    }

    @Test
    fun `a burst from unknown senders yields one card, the rest are dropped`() {
        assertEquals(AlertDecision.Silent, policy.decide(SenderTrust.Unknown, knownOnly = true))
        repeat(50) {
            now += 100
            assertEquals(AlertDecision.Drop, policy.decide(SenderTrust.Unknown, knownOnly = true))
        }
    }

    @Test
    fun `a flood of strangers cannot use up a known sender's allowance`() {
        repeat(20) { policy.decide(SenderTrust.Unknown, knownOnly = false) }
        assertEquals(AlertDecision.Buzz, policy.decide(SenderTrust.Added, knownOnly = false))
    }
}
