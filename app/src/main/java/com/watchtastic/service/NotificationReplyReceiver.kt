package com.watchtastic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.watchtastic.di.AppGraph
import com.watchtastic.mesh.model.LinkState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

        /** Bounded: the broadcast is held open meanwhile, and the system won't wait long. */
        private const val LOAD_WAIT_MS = 3_000L
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
                // Notifications outlive the process, so a reply can be what starts it.
                // Sending before the snapshot loaded put the reply into an empty store,
                // and the load then replaced the store wholesale — the reply vanished.
                withTimeoutOrNull(LOAD_WAIT_MS) { graph.store.awaitLoaded() }

                val result = graph.repository.sendText(conversation, text)
                val receipt = result.getOrNull()
                if (receipt != null) {
                    if (receipt.queued) {
                        // Accepted into the outbox: a lighter tick than "sent", so the
                        // wrist can tell the two apart.
                        graph.haptics.tick()
                        // Nothing is trying to reach the radio in a cold process, so
                        // nothing would ever flush the outbox. Start the link service,
                        // which picks the saved radio back up.
                        if (graph.repository.link.value == LinkState.Idle) {
                            MeshService.start(context)
                        }
                    } else {
                        graph.haptics.sent()
                    }
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
