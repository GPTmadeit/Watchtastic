package com.watchtastic.glance

import com.watchtastic.mesh.MeshConstants
import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.LinkState
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.MsgStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile and complication both render from [MeshGlance], so its rules are the ones a
 * wearer sees on the watch face: what counts as unread, what counts as online, and where
 * a tap should land.
 */
class MeshGlanceTest {

    private val now = 1_800_000_000_000L
    private val self = 0x1234

    private fun msg(
        conversation: String,
        timeMs: Long,
        outgoing: Boolean = false,
        reaction: Boolean = false,
    ) = ChatMessage(
        id = timeMs.toInt(),
        conversation = conversation,
        fromNum = 7,
        toNum = self,
        channel = 0,
        text = "hi",
        timeMs = timeMs,
        outgoing = outgoing,
        status = if (outgoing) MsgStatus.Sent else MsgStatus.Received,
        isReaction = reaction,
    )

    private fun node(num: Int, heardAgoMs: Long?) = MeshNode(
        num = num,
        userId = MeshConstants.nodeIdOf(num),
        longName = "Node $num",
        shortName = "N$num",
        lastHeardSeconds = heardAgoMs?.let { ((now - it) / 1000).toInt() } ?: 0,
    )

    private fun glance(
        link: LinkState = LinkState.Connected("Pack radio"),
        saved: Boolean = true,
        messages: List<ChatMessage> = emptyList(),
        lastRead: Map<String, Long> = emptyMap(),
        nodes: List<MeshNode> = emptyList(),
    ) = MeshGlance.of(link, saved, messages, lastRead, nodes, self, now)

    @Test
    fun `only received prose newer than last read is unread`() {
        val g = glance(
            messages = listOf(
                msg("ch:0", 100),
                msg("ch:0", 200),
                msg("ch:0", 300, outgoing = true),
                msg("ch:0", 400, reaction = true),
            ),
            lastRead = mapOf("ch:0" to 150L),
        )
        assertEquals(1, g.unread)
    }

    @Test
    fun `a single unread conversation is offered as the tap target`() {
        val g = glance(messages = listOf(msg("dm:7", 100), msg("dm:7", 200)))
        assertEquals("dm:7", g.unreadConversation)
    }

    @Test
    fun `unread spread across conversations falls back to the list`() {
        val g = glance(messages = listOf(msg("dm:7", 100), msg("ch:0", 200)))
        assertNull(g.unreadConversation)
        assertEquals(2, g.unread)
    }

    @Test
    fun `own radio never counts as a node online`() {
        val g = glance(
            nodes = listOf(
                node(self, heardAgoMs = 1_000),
                node(2, heardAgoMs = 60_000),
                node(3, heardAgoMs = MeshConstants.NODE_STALE_MS + 1),
                node(4, heardAgoMs = null),
            ),
        )
        assertEquals(1, g.onlineNodes)
        assertEquals(3, g.totalNodes)
    }

    @Test
    fun `short count caps at three characters`() {
        assertEquals("0", glance().unreadShort)
        assertEquals("99+", glance().copy(unread = 250).unreadShort)
    }

    @Test
    fun `status distinguishes never set up from dropped`() {
        assertEquals("No radio", glance(link = LinkState.Idle, saved = false).status)
        assertEquals("Not connected", glance(link = LinkState.Idle, saved = true).status)
        assertEquals("Disconnected", glance(link = LinkState.Failed("gone")).status)
    }

    @Test
    fun `recovering links are transient, dead ones are not`() {
        assertTrue(glance(link = LinkState.Reconnecting("r", 2)).isTransient)
        assertTrue(glance(link = LinkState.Syncing("r", 0.5f)).isTransient)
        assertFalse(glance(link = LinkState.Failed("x")).isTransient)
        assertFalse(glance().isTransient)
        assertTrue(glance().isConnected)
    }

    @Test
    fun `short status fits a short-text complication title`() {
        val states = listOf(
            LinkState.Idle, LinkState.Scanning, LinkState.Connecting("r"),
            LinkState.Pairing("r"), LinkState.Syncing("r", 0f), LinkState.Connected("r"),
            LinkState.Reconnecting("r", 1), LinkState.Failed("a long reason"),
        )
        for (state in states) {
            val title = glance(link = state).statusShort
            assertTrue("'$title' for $state", title.length <= 7)
        }
    }

    @Test
    fun `wide summary leads with unread, falls back to status`() {
        val nodes = listOf(node(2, heardAgoMs = 1_000), node(3, heardAgoMs = null))
        assertEquals(
            "2 unread · 1 online",
            glance(messages = listOf(msg("dm:7", 1), msg("dm:7", 2)), nodes = nodes).summary,
        )
        assertEquals("Connected · 1 online", glance(nodes = nodes).summary)
        // No nodes known at all: don't claim "0 online" about a mesh we haven't seen.
        assertEquals("No radio", glance(link = LinkState.Idle, saved = false).summary)
    }

    @Test
    fun `spoken summary uses agreeing plurals`() {
        val one = glance(messages = listOf(msg("dm:7", 1)), nodes = listOf(node(2, 1_000)))
        assertEquals("Watchtastic: Connected, 1 unread message, 1 node online", one.spokenSummary)
        assertEquals("Watchtastic: Connected, 0 unread messages, 0 nodes online", glance().spokenSummary)
    }

    @Test
    fun `tap lands on the waiting conversation, the list, or home`() {
        assertEquals("dm:7", glance(messages = listOf(msg("dm:7", 1))).tapTarget)
        assertEquals(
            "conversations",
            glance(messages = listOf(msg("dm:7", 1), msg("ch:0", 2))).tapTarget,
        )
        assertEquals("home", glance().tapTarget)
    }

    @Test
    fun `sync progress does not change the glance`() {
        // GlanceUpdater refreshes only when the glance differs; config download emits a
        // link state per frame, and none of those should cost a tile refresh.
        assertEquals(
            glance(link = LinkState.Syncing("r", 0.1f)),
            glance(link = LinkState.Syncing("r", 0.9f)),
        )
    }
}
