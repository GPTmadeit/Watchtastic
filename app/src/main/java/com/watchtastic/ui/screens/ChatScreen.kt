package com.watchtastic.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.MeshConstants
import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.mesh.model.MsgStatus
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.components.MessageStatusIcon
import com.watchtastic.ui.components.NodeAvatar
import com.watchtastic.ui.components.shortAgo
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.input.rememberTextInput
import kotlinx.coroutines.launch

/** Tapbacks worth having on a wrist: enough to answer without typing, few enough to tap. */
private val REACTIONS = listOf("👍", "❤️", "😂", "✅", "❓", "‼️", "👀", "🙏")

/**
 * One conversation.
 *
 * Three ways to answer, in descending order of speed, because on a watch the cost of
 * replying is what decides whether the wearer replies at all:
 *  - a tapback (one tap, no keyboard),
 *  - a canned phrase (two taps),
 *  - dictation or the system keyboard (the edge button).
 */
@Composable
fun ChatScreen(conversationKey: String, onOpenNode: (Int) -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val key = remember(conversationKey) { Uri.decode(conversationKey) }

    val allMessages by graph.store.messages.collectAsStateWithLifecycle()
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val channels by graph.store.channels.collectAsStateWithLifecycle()
    val myNodeNum by graph.store.myNodeNum.collectAsStateWithLifecycle()
    val canned by graph.prefs.cannedMessages.collectAsStateWithLifecycle()

    val conversationMessages = remember(allMessages, key) {
        allMessages.filter { it.conversation == key }
    }
    val body = remember(conversationMessages) {
        conversationMessages.filterNot { it.isReaction }
    }
    val reactions = remember(conversationMessages) {
        conversationMessages.filter { it.isReaction && it.replyId != 0 }.groupBy { it.replyId }
    }

    val title = remember(key, nodes, channels) {
        if (ConversationKey.isChannel(key)) {
            val index = ConversationKey.channelIndex(key) ?: 0
            channels.firstOrNull { it.index == index }?.displayName ?: "Channel $index"
        } else {
            val num = ConversationKey.nodeNum(key)
            num?.let { nodes[it]?.displayLong } ?: "Direct"
        }
    }

    // Opening the thread is what marks it read — and clears its notification.
    LaunchedEffect(key, body.size) {
        graph.store.markRead(key)
        graph.notifier.clearConversation(key)
    }

    var reactTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var showCanned by remember { mutableStateOf(false) }
    var confirmAlert by remember { mutableStateOf(false) }

    val channelIndex = remember(key) { ConversationKey.channelIndex(key) ?: 0 }

    val send: (String) -> Unit = { text ->
        scope.launch {
            val result = graph.repository.sendText(key, text)
            if (result.isSuccess) graph.haptics.sent() else graph.haptics.failed()
        }
    }
    val launchInput = rememberTextInput(onResult = send)

    val listState = rememberTransformingLazyColumnState()

    // Follow the conversation as it grows, the way a messaging app should.
    LaunchedEffect(body.size) {
        if (body.isNotEmpty()) listState.animateScrollToItem(body.size)
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = {
                    graph.haptics.select()
                    launchInput("Message $title")
                },
                buttonSize = EdgeButtonSize.Medium,
            ) {
                Icon(WtIcons.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reply")
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text(title, maxLines = 1) } }

            if (body.isEmpty()) {
                item {
                    EmptyState(
                        icon = WtIcons.Chat,
                        title = "No messages yet",
                        subtitle = "Say something to the mesh",
                    )
                }
            }

            items(body.size, key = { body[it].id }) { index ->
                val message = body[index]
                MessageBubble(
                    message = message,
                    senderName = nodes[message.fromNum]?.displayShort
                        ?: MeshConstants.nodeIdOf(message.fromNum),
                    senderNode = nodes[message.fromNum],
                    showSender = !message.outgoing && ConversationKey.isChannel(key),
                    reactions = reactions[message.id].orEmpty().map { it.text },
                    onLongClick = {
                        graph.haptics.heavy()
                        reactTarget = message
                    },
                    onSenderClick = {
                        if (message.fromNum != myNodeNum && message.fromNum != 0) {
                            onOpenNode(message.fromNum)
                        }
                    },
                )
            }

            // Broadcast-only actions. Both address a whole channel, so they'd be
            // meaningless — and on the alert's part antisocial — inside a direct thread.
            if (ConversationKey.isChannel(key)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(
                            6.dp,
                            Alignment.CenterHorizontally,
                        ),
                    ) {
                        // Text only: at half the width of a 1.2" round screen, an icon
                        // plus "Position" leaves room for "Posi…", which is worse than
                        // no icon at all.
                        FilledTonalButton(
                            onClick = {
                                graph.haptics.select()
                                scope.launch {
                                    graph.repository.broadcastPosition(channelIndex)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    "Position",
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                        )
                        FilledTonalButton(
                            onClick = { graph.haptics.heavy(); confirmAlert = true },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    "Alert",
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    FilledTonalButton(
                        onClick = { graph.haptics.select(); showCanned = true },
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                WtIcons.QuickReply,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = { Text("Quick", maxLines = 1) },
                    )
                    FilledTonalButton(
                        onClick = {
                            graph.haptics.select()
                            reactTarget = body.lastOrNull { !it.outgoing } ?: body.lastOrNull()
                        },
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                WtIcons.React,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = { Text("React", maxLines = 1) },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- overlays

    AlertDialog(
        visible = showCanned,
        onDismissRequest = { showCanned = false },
        title = { Text("Quick reply") },
    ) {
        items(canned.size) { index ->
            Button(
                onClick = {
                    showCanned = false
                    send(canned[index])
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(canned[index], maxLines = 2) },
            )
        }
    }

    // An alert rings buzzers on every node in range, so it gets a confirmation step —
    // a mis-tap here is felt by other people, not just the wearer.
    AlertDialog(
        visible = confirmAlert,
        onDismissRequest = { confirmAlert = false },
        title = { Text("Send alert?") },
        text = { Text("Buzzes every node on $title.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmAlert = false
                    scope.launch {
                        graph.repository.sendAlert(channelIndex, "Alert from $title")
                        graph.haptics.alert()
                    }
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmAlert = false })
        },
    )

    val target = reactTarget
    AlertDialog(
        visible = target != null,
        onDismissRequest = { reactTarget = null },
        title = { Text("React") },
    ) {
        items((REACTIONS.size + 3) / 4) { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                for (column in 0 until 4) {
                    val emojiIndex = row * 4 + column
                    if (emojiIndex >= REACTIONS.size) break
                    val emoji = REACTIONS[emojiIndex]
                    Button(
                        onClick = {
                            val message = target ?: return@Button
                            reactTarget = null
                            scope.launch {
                                graph.repository.sendReaction(key, message.id, emoji)
                                graph.haptics.sent()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ContentPadding,
                        label = {
                            Text(emoji, textAlign = TextAlign.Center, maxLines = 1)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    senderName: String,
    senderNode: com.watchtastic.mesh.model.MeshNode?,
    showSender: Boolean,
    reactions: List<String>,
    onLongClick: () -> Unit,
    onSenderClick: () -> Unit,
) {
    // Outgoing messages sit in the brand green container, incoming in neutral surface —
    // so who said what is legible before a single word is read.
    val colors = if (message.outgoing) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors()
    }

    Card(
        onClick = onLongClick,
        onLongClick = onLongClick,
        onLongClickLabel = "React",
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (message.outgoing) 16.dp else 0.dp,
                end = if (message.outgoing) 0.dp else 16.dp,
            ),
    ) {
        if (showSender) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp),
            ) {
                NodeAvatar(senderNode, size = 18)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }

        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (reactions.isNotEmpty()) {
                Text(
                    text = reactions.joinToString(""),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = shortAgo(message.timeMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            MessageStatusIcon(message.status)
        }

        if (message.status == MsgStatus.Failed && message.failureReason != null) {
            Text(
                text = message.failureReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
    // Single layout node per list item — see HomeScreen.HubButton. Spacing between
    // bubbles is the column's verticalArrangement.
}
