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
import androidx.core.content.ContextCompat
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
        const val LINK_CHANNEL_ID = "mesh_link"
        const val MESSAGE_CHANNEL_ID = "mesh_messages"
        const val NODE_CHANNEL_ID = "mesh_nodes"
        const val ONGOING_NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_BASE = 2000
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
            // Our own Haptics class owns the buzz vocabulary, so the channel stays
            // silent here rather than doubling up with the system pattern.
            enableVibration(false)
            setShowBadge(true)
        }

        // Node discovery is interesting, not urgent — a separate channel so it can be
        // silenced without also silencing messages.
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
    fun postMessage(message: ChatMessage, senderName: String, conversationTitle: String) {
        if (!canPost) return
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setSubText(conversationTitle)
            .setWhen(message.timeMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Haptics are played explicitly so every surface uses the same vocabulary.
            .setSilent(true)
            .setContentIntent(openAppIntent(message.conversation))
            .build()

        runCatching {
            // Keyed per conversation so a chatty channel replaces rather than stacks.
            manager.notify(
                MESSAGE_NOTIFICATION_BASE + message.conversation.hashCode().and(0xFFFF),
                notification,
            )
        }
    }

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
