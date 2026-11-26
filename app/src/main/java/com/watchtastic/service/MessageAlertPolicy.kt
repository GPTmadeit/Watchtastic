package com.watchtastic.service

import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.mesh.model.MeshNode

/** How much the app can vouch for who sent a message. */
enum class SenderTrust {
    /**
     * A direct message that arrived PKI-encrypted. The sender's key authenticated it, so
     * the sender number cannot have been forged.
     */
    Verified,

    /** The wearer added this node as a favourite. */
    Added,

    /** Anyone else. Channel traffic from a non-favourite always lands here: its sender number is not authenticated. */
    Unknown,
    ;

    val isKnown: Boolean get() = this != Unknown

    companion object {
        fun of(message: ChatMessage, sender: MeshNode?): SenderTrust = when {
            message.outgoing -> Unknown
            sender?.isFavorite == true -> Added
            !ConversationKey.isChannel(message.conversation) && message.pkiEncrypted -> Verified
            else -> Unknown
        }
    }
}

/** What to do with an incoming message's notification. */
enum class AlertDecision {
    /** Post the card and buzz. */
    Buzz,

    /** Post the card without vibrating. */
    Silent,

    /** Post nothing; the message is still in its conversation, unread. */
    Drop,
}

/**
 * Decides how loudly a message may announce itself, based on who it is from.
 *
 * Sender numbers on a public channel are unauthenticated, so a stranger can rotate
 * through them and every message looks like it is from someone new. Known senders get
 * their own allowance, which unknown traffic cannot use up. Unknown senders are
 * limited to a trickle of notification cards — each new sender would otherwise add one
 * more card to the shade — and, if [knownOnly] is set, never buzz at all.
 *
 * Nothing is hidden: a dropped or silent notification is still a message in the
 * conversation list with an unread badge.
 */
class MessageAlertPolicy(clock: () -> Long = System::currentTimeMillis) {

    private val knownBuzz = AlertThrottle(KNOWN_BUZZ_INTERVAL_MS, clock)
    private val unknownCard = AlertThrottle(UNKNOWN_CARD_INTERVAL_MS, clock)
    private val unknownBuzz = AlertThrottle(UNKNOWN_BUZZ_INTERVAL_MS, clock)

    fun decide(trust: SenderTrust, knownOnly: Boolean): AlertDecision =
        if (trust.isKnown) {
            if (knownBuzz.tryAcquire()) AlertDecision.Buzz else AlertDecision.Silent
        } else if (!unknownCard.tryAcquire()) {
            AlertDecision.Drop
        } else if (knownOnly) {
            AlertDecision.Silent
        } else if (unknownBuzz.tryAcquire()) {
            AlertDecision.Buzz
        } else {
            AlertDecision.Silent
        }

    companion object {
        const val KNOWN_BUZZ_INTERVAL_MS = 5_000L
        const val UNKNOWN_CARD_INTERVAL_MS = 15_000L
        const val UNKNOWN_BUZZ_INTERVAL_MS = 30_000L
    }
}
