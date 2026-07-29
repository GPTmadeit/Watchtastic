package com.watchtastic.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.model.Conversation
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.components.NodeAvatar
import com.watchtastic.ui.components.StatusDot
import com.watchtastic.ui.components.shortAgo
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette

/** Every channel plus every direct thread, unread first, then most recent. */
@Composable
fun ConversationsScreen(onOpenChat: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val conversations by graph.store.conversations
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()

    var pendingClear by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Messages") } }

            if (conversations.isEmpty()) {
                item {
                    EmptyState(
                        icon = WtIcons.Chat,
                        title = "No conversations",
                        subtitle = "Connect a radio to see channels",
                    )
                }
            } else {
                items(conversations.size, key = { conversations[it].key }) { index ->
                    val conversation = conversations[index]
                    ConversationRow(
                        conversation = conversation,
                        avatarNode = conversation.nodeNum?.let { nodes[it] },
                        onClick = {
                            graph.haptics.select()
                            graph.store.markRead(conversation.key)
                            graph.notifier.clearConversation(conversation.key)
                            onOpenChat(conversation.key)
                        },
                        onLongClick = {
                            graph.haptics.heavy()
                            pendingClear = conversation.key to conversation.title
                        },
                    )
                }
                item {
                    Text(
                        text = "Long-press to clear a thread",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val clearTarget = pendingClear
    AlertDialog(
        visible = clearTarget != null,
        onDismissRequest = { pendingClear = null },
        title = { Text("Clear ${clearTarget?.second.orEmpty()}?") },
        text = { Text("Deletes these messages from the watch only.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    clearTarget?.let { (key, _) ->
                        graph.store.deleteConversation(key)
                        graph.notifier.clearConversation(key)
                    }
                    pendingClear = null
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { pendingClear = null })
        },
    )
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    avatarNode: com.watchtastic.mesh.model.MeshNode?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = "Clear messages",
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = {
            if (conversation.isChannel) {
                Icon(
                    imageVector = if (conversation.isMuted) WtIcons.Mute else WtIcons.Channel,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                NodeAvatar(avatarNode, size = 26)
            }
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.title, maxLines = 1, modifier = Modifier.weight(1f, false))
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.width(5.dp))
                    StatusDot(MeshPalette.MeshGreen, size = 7)
                }
            }
        },
        secondaryLabel = {
            val stamp = conversation.lastMessage?.let { " · ${shortAgo(it.timeMs)}" }.orEmpty()
            Text(
                text = conversation.subtitle + stamp,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}
