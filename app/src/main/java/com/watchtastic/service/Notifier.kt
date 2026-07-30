package com.watchtastic.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.watchtastic.mesh.model.ConversationKey
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.watchtastic.MainActivity
import com.watchtastic.R
import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.LinkState

/**
 * Notifications, including the Wear "ongoing activity" chip.
 *
 * The ongoing chip is what makes a background radio link feel native on a watch: while
 * Watchtastic holds a connection, the watch face shows a small live status the wearer can
 * tap to jump straight back in, the same way a workout or a timer behaves.
 */
// Every notify() below is guarded by the `canPost` permission check; lint can't follow
// the indirection through that property.
@SuppressLint("MissingPermission")
class Notifier(private val context: Context) {

    companion object {
        /** Two taps, echoing the "incoming" signature in [com.watchtastic.platform.Haptics]. */
        private val MESSAGE_VIBRATION = longArrayOf(0, 40, 90, 110)

        const val LINK_CHANNEL_ID = "mesh_link"

        /**
         * Bumped to `_v2` deliberately. A channel's importance and vibration are fixed at
         * creation — the system ignores later edits, and recreating a deleted channel
         * restores its old settings — so anyone upgrading from 1.2.0 would have kept the
         * silent, never-popping message channel forever. A new id is the only way to
         * hand existing installs the alerting behaviour.
         */
        const val MESSAGE_CHANNEL_ID = "mesh_messages_v2"
        private const val LEGACY_MESSAGE_CHANNEL_ID = "mesh_messages"

        /** Set by the reply action; read back out of the RemoteInput bundle. */
        const val KEY_REPLY_TEXT = "watchtastic_reply"
        const val EXTRA_CONVERSATION = "com.watchtastic.extra.CONVERSATION"
        const val NODE_CHANNEL_ID = "mesh_nodes"
        const val ONGOING_NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_BASE = 2000
        private const val SELF_PERSON_KEY = "self"
        private const val NODE_NOTIFICATION_BASE = 3000
    }

    private val manager = NotificationManagerCompat.from(context)

    private val canPost: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels() {
        val link = NotificationChannel(
            LINK_CHANNEL_ID,
            context.getString(R.string.chan_link_name),
            // Silent: the ongoing chip is ambient status, not an interruption.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.chan_link_desc)
            setShowBadge(false)
        }

        val messages = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            context.getString(R.string.chan_msg_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.chan_msg_desc)
            // The channel has to actually alert for Android to raise a heads-up card.
            // A silent notification is filed into the stream and never interrupts, no
            // matter how high its importance — which is why messages previously arrived
            // without popping up. The buzz therefore belongs to the channel here rather
            // than to our own Haptics class, or the wearer would feel it twice.
            enableVibration(true)
            vibrationPattern = MESSAGE_VIBRATION
            setShowBadge(true)
        }

        // Node discovery is interesting, not urgent. DEFAULT importance files it into the
        // notification stream as a small card without seizing the screen, and the channel
        // stays silent so our own light haptic is the only thing felt.
        val nodesFound = NotificationChannel(
            NODE_CHANNEL_ID,
            context.getString(R.string.chan_node_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.chan_node_desc)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(link)
        manager.createNotificationChannel(messages)
        manager.createNotificationChannel(nodesFound)

        // Retire the silent channel so upgraders aren't left with a dead duplicate
        // sitting in the system notification settings.
        runCatching { manager.deleteNotificationChannel(LEGACY_MESSAGE_CHANNEL_ID) }
    }

    private fun openAppIntent(route: String? = null): PendingIntent {
        // SINGLE_TOP only: MainActivity is already singleTask, and CLEAR_TOP would make
        // the app misrepresent itself in the Wear recents carousel.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            context,
            route?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Builds the foreground-service notification, wrapped as an ongoing activity. */
    fun buildLinkNotification(state: LinkState, unread: Int): Notification {
        val (title, body) = describe(state)
        val touchIntent = openAppIntent()

        val builder = NotificationCompat.Builder(context, LINK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(touchIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (unread > 0) builder.setNumber(unread)

        val status = Status.Builder().addTemplate(body).build()
        OngoingActivity.Builder(context, ONGOING_NOTIFICATION_ID, builder)
            .setAnimatedIcon(R.drawable.ic_notification)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(touchIntent)
            .setStatus(status)
            .build()
            .apply(context)

        return builder.build()
    }

    fun updateLink(state: LinkState, unread: Int) {
        if (!canPost) return
        runCatching {
            manager.notify(ONGOING_NOTIFICATION_ID, buildLinkNotification(state, unread))
        }
    }

    /** Posts an incoming mesh message. [senderName] is already resolved for display. */
    /**
     * Posts an incoming mesh message as a full heads-up alert.
     *
     * Uses [NotificationCompat.MessagingStyle] rather than plain title/text: it is what
     * Wear renders as a conversation, it stacks successive messages from the same thread
     * into one card, and it carries the sender through as a [Person] so the watch shows
     * who is talking. Crucially there is no `setSilent` here — a silent notification is
     * filed away instead of interrupting, which is what stopped these popping up before.
     */
    fun postMessage(message: ChatMessage, senderName: String, conversationTitle: String) {
        if (!canPost) return

        val sender = Person.Builder()
            .setName(senderName)
            .setKey(message.fromNum.toString())
            .build()

        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("You").setKey(SELF_PERSON_KEY).build(),
        ).addMessage(message.text, message.timeMs, sender)

        // A channel is a group conversation; a direct message is not. Wear uses this to
        // decide whether to prefix each line with the speaker's name.
        val isGroup = ConversationKey.isChannel(message.conversation)
        if (isGroup) {
            style.setGroupConversation(true).conversationTitle = conversationTitle
        }

        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setWhen(message.timeMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Wear reads this to decide the card can expand over the current screen.
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setContentIntent(openAppIntent(message.conversation))
            .addAction(replyAction(message.conversation))
            .build()

        runCatching {
            // Keyed per conversation so a chatty channel replaces rather than stacks.
            manager.notify(notificationIdFor(message.conversation), notification)
        }
    }

    /**
     * Reply straight from the notification, without opening the app.
     *
     * This is the part that makes a mesh message feel like a text on a watch: the wearer
     * dictates a reply into the system's own input surface and it goes out over LoRa.
     */
    private fun replyAction(conversationKey: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()

        val intent = Intent(context, NotificationReplyReceiver::class.java)
            .setAction(NotificationReplyReceiver.ACTION_REPLY)
            .putExtra(EXTRA_CONVERSATION, conversationKey)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            conversationKey.hashCode(),
            intent,
            // MUTABLE is required: the system fills the wearer's text into this intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Reply",
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    fun notificationIdFor(conversationKey: String): Int =
        MESSAGE_NOTIFICATION_BASE + conversationKey.hashCode().and(0xFFFF)

    /** A node the mesh has never shown us before just turned up. */
    fun postNewNode(nodeNum: Int, name: String, detail: String) {
        if (!canPost) return
        val notification = NotificationCompat.Builder(context, NODE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New node: $name")
            .setContentText(detail)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()

        runCatching {
            manager.notify(NODE_NOTIFICATION_BASE + nodeNum.and(0xFFFF), notification)
        }
    }

    fun clearConversation(conversationKey: String) {
        runCatching {
            manager.cancel(MESSAGE_NOTIFICATION_BASE + conversationKey.hashCode().and(0xFFFF))
        }
    }

    private fun describe(state: LinkState): Pair<String, String> = when (state) {
        is LinkState.Connected -> "Mesh connected" to state.deviceName
        is LinkState.Syncing -> "Syncing" to "${(state.progress * 100).toInt()}% from ${state.deviceName}"
        is LinkState.Connecting -> "Connecting" to state.deviceName
        is LinkState.Pairing -> "Pairing" to "Confirm the PIN on ${state.deviceName}"
        is LinkState.Reconnecting -> "Reconnecting" to "${state.deviceName} — attempt ${state.attempt}"
        is LinkState.Failed -> "Disconnected" to state.reason
        LinkState.Scanning -> "Scanning" to "Looking for radios"
        LinkState.Idle -> "Watchtastic" to "Not connected"
    }
}
