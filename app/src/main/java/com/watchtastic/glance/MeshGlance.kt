package com.watchtastic.glance

import com.watchtastic.mesh.MeshConstants
import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.LinkState
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.othersThan
import com.watchtastic.ui.nav.Routes

/**
 * Everything the tile and the complication show, computed once.
 *
 * Both surfaces render from this one value so they can never disagree with each other —
 * or with the app, because the rules here are the same ones the home screen uses. It is a
 * pure function of store state, which keeps it testable without a watch.
 */
data class MeshGlance(
    /** One or two words for the link: "Connected", "Reconnecting", "No radio"… */
    val status: String,
    /** The radio's name, when there is one worth showing. */
    val radioName: String?,
    val isConnected: Boolean,
    /** True while the link is coming up or recovering, as opposed to down. */
    val isTransient: Boolean,
    val unread: Int,
    /**
     * Set when every unread message sits in a single conversation, so a tap can go
     * straight to it instead of making the wearer pick from a list of one.
     */
    val unreadConversation: String?,
    val onlineNodes: Int,
    val totalNodes: Int,
) {
    /**
     * The link in at most seven characters, for a short-text complication's title —
     * "Reconnecting" and "Not connected" both get clipped there.
     */
    val statusShort: String
        get() = when {
            isConnected -> "Unread"
            isTransient -> "Linking"
            else -> "Offline"
        }

    /** Compact count for a short-text complication; three characters is the honest limit. */
    val unreadShort: String get() = if (unread > 99) "99+" else unread.toString()

    /**
     * One line for a wide complication slot. Unread wins when there is any — it is the
     * thing the wearer can act on — and the link status stands in when there isn't.
     */
    val summary: String
        get() = buildList {
            add(if (unread > 0) "$unreadShort unread" else status)
            if (totalNodes > 0) add("$onlineNodes online")
        }.joinToString(" · ")

    /** What a screen reader says: everything, uncompressed, with plurals that agree. */
    val spokenSummary: String
        get() = "Watchtastic: $status, ${plural(unread, "unread message")}, " +
            "${plural(onlineNodes, "node")} online"

    /**
     * Where a general "open the app" tap should land: straight into the conversation
     * when exactly one is waiting, the list when several are, home otherwise.
     */
    val tapTarget: String
        get() = when {
            unread == 0 -> Routes.HOME
            else -> unreadConversation ?: Routes.CONVERSATIONS
        }

    private fun plural(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"

    companion object {
        fun of(
            link: LinkState,
            hasSavedRadio: Boolean,
            messages: List<ChatMessage>,
            lastRead: Map<String, Long>,
            nodes: Collection<MeshNode>,
            myNodeNum: Int,
            nowMs: Long,
        ): MeshGlance {
            val unreadMessages = messages.filter { it.isUnread(lastRead) }
            val unreadKeys = unreadMessages.mapTo(HashSet()) { it.conversation }

            val others = nodes.othersThan(myNodeNum)

            return MeshGlance(
                status = statusOf(link, hasSavedRadio),
                radioName = link.deviceLabel,
                isConnected = link.isUsable,
                isTransient = link is LinkState.Connecting || link is LinkState.Pairing ||
                    link is LinkState.Syncing || link is LinkState.Reconnecting ||
                    link is LinkState.Scanning,
                unread = unreadMessages.size,
                unreadConversation = unreadKeys.singleOrNull(),
                onlineNodes = others.count { it.isOnline(nowMs, MeshConstants.NODE_STALE_MS) },
                totalNodes = others.size,
            )
        }

        private fun statusOf(link: LinkState, hasSavedRadio: Boolean): String = when (link) {
            is LinkState.Connected -> "Connected"
            is LinkState.Syncing -> "Syncing"
            is LinkState.Connecting -> "Connecting"
            is LinkState.Pairing -> "Pairing"
            is LinkState.Reconnecting -> "Reconnecting"
            is LinkState.Failed -> "Disconnected"
            LinkState.Scanning -> "Scanning"
            LinkState.Idle -> if (hasSavedRadio) "Not connected" else "No radio"
        }
    }
}
