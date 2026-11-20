package com.watchtastic.ui.nav

import android.net.Uri

/**
 * Every destination in the app.
 *
 * Kept as a flat list rather than nested graphs: on Wear the back stack *is* the
 * navigation model — a right-edge swipe pops it — so extra hierarchy would only add
 * places for the wearer to get lost.
 */
object Routes {
    const val CONNECT = "connect"
    const val HOME = "home"
    const val CONVERSATIONS = "conversations"
    const val NODES = "nodes"
    const val CHANNELS = "channels"
    const val STATUS = "status"
    const val SETTINGS = "settings"
    const val RADIO_CONFIG = "radio"
    const val MAP = "map"
    const val WAYPOINTS = "waypoints"
    const val QUICK_REPLIES = "quickreplies"
    const val UPDATE = "update"

    const val CHAT = "chat/{key}"
    const val NODE_DETAIL = "node/{num}"
    const val COMPASS = "compass/{num}"

    const val ARG_KEY = "key"
    const val ARG_NUM = "num"

    /** Conversation keys contain a colon, so they are encoded into the path. */
    fun chat(conversationKey: String) = "chat/${Uri.encode(conversationKey)}"

    fun nodeDetail(nodeNum: Int) = "node/$nodeNum"

    fun compass(nodeNum: Int) = "compass/$nodeNum"

    /**
     * Top-level screens an outside surface (tile, complication) may open directly.
     *
     * An allowlist rather than "any route": the extra arrives in an Intent, and only
     * destinations that need no arguments are safe to land on cold.
     */
    private val DEEP_LINKABLE = setOf(CONVERSATIONS, NODES, HOME)

    /**
     * Resolves a deep-link extra. Anything that isn't a known top-level screen is a
     * conversation key, which is what notifications have always sent — conversation keys
     * carry a `ch:`/`dm:` prefix, so the two can never collide.
     */
    fun forDeepLink(value: String): String =
        if (value in DEEP_LINKABLE) value else chat(value)

    fun isTopLevel(route: String): Boolean = route in DEEP_LINKABLE
}
