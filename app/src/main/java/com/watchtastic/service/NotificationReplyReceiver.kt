package com.watchtastic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.watchtastic.di.AppGraph
import kotlinx.coroutines.launch

/**
 * Sends a reply typed or dictated straight into the notification.
 *
 * A broadcast receiver rather than an activity so the wearer never leaves whatever they
 * were doing — the reply goes out over LoRa and the card updates in place.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "com.watchtastic.action.REPLY"
        private const val TAG = "NotificationReply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val conversation = intent.getStringExtra(Notifier.EXTRA_CONVERSATION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifier.KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return

        val graph = AppGraph.from(context)

        // Sending is suspending and a receiver's onReceive must not block, so hold the
        // broadcast open until the radio has actually taken the packet.
        val pending = goAsync()
        graph.scope.launch {
            try {
                val result = graph.repository.sendText(conversation, text)
                if (result.isSuccess) {
                    graph.haptics.sent()
                    // Replying is reading: clear the thread and drop the card.
                    graph.store.markRead(conversation)
                    graph.notifier.clearConversation(conversation)
                } else {
                    graph.haptics.failed()
                    Log.w(TAG, "reply failed: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "reply threw", e)
            } finally {
                pending.finish()
            }
        }
    }
}
