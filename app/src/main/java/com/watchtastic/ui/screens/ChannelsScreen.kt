package com.watchtastic.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.icons.WtIcons
import kotlinx.coroutines.launch

/**
 * The radio's channel slots.
 *
 * Read-only apart from muting: creating and re-keying channels means handling PSKs and
 * QR/URL exchange, which needs a screen and a camera the watch doesn't have. Those stay
 * on the phone or desktop client; the watch uses what the radio already has.
 */
@Composable
fun ChannelsScreen(onOpenChat: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val channels by graph.store.channels.collectAsStateWithLifecycle()
    val muted by graph.prefs.mutedChannels.collectAsStateWithLifecycle()

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Channels") } }

            val enabled = channels.filter { it.isEnabled }
            if (enabled.isEmpty()) {
                item {
                    EmptyState(
                        icon = WtIcons.Channel,
                        title = "No channels",
                        subtitle = "Connect a radio to load them",
                    )
                }
            } else {
                items(enabled.size, key = { enabled[it].index }) { index ->
                    val channel = enabled[index]
                    val isMuted = channel.index in muted
                    Button(
                        onClick = {
                            graph.haptics.select()
                            onOpenChat(ConversationKey.channel(channel.index))
                        },
                        onLongClick = {
                            graph.haptics.heavy()
                            graph.prefs.setChannelMuted(channel.index, !isMuted)
                        },
                        onLongClickLabel = if (isMuted) "Unmute" else "Mute",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = {
                            Icon(
                                imageVector = if (isMuted) WtIcons.Mute else WtIcons.Channel,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        label = { Text(channel.displayName, maxLines = 1) },
                        secondaryLabel = {
                            val bits = buildList {
                                add(channel.role.lowercase())
                                if (channel.hasKey) add("encrypted")
                                if (isMuted) add("muted here")
                            }
                            Text(bits.joinToString(" · "), maxLines = 1)
                        },
                    )
                }
                item {
                    Text(
                        text = "Long-press to mute on this watch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
