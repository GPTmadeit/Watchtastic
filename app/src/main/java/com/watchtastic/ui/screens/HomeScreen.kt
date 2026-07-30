package com.watchtastic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.MeshConstants
import com.watchtastic.mesh.model.LinkState
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.PulseDot
import com.watchtastic.ui.components.rememberNow
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette

/**
 * The hub.
 *
 * One card answering "is my mesh alive?", then the five places worth going. Everything
 * here is reachable with one crown flick and one tap, because on a watch the third
 * interaction is where people give up and pull out their phone.
 */
@Composable
fun HomeScreen(
    onOpenConversations: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenWaypoints: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnect: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val link by graph.repository.link.collectAsStateWithLifecycle()
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val messages by graph.store.messages.collectAsStateWithLifecycle()
    val lastRead by graph.store.lastRead.collectAsStateWithLifecycle()

    val now by rememberNow()
    val online = nodes.values.count { it.isOnline(now, MeshConstants.NODE_STALE_MS) }
    val unread = messages
        .filterNot { it.outgoing || it.isReaction }
        .count { it.timeMs > (lastRead[it.conversation] ?: 0L) }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                LinkCard(
                    link = link,
                    online = online,
                    total = nodes.size,
                    onClick = { if (!link.isUsable) onOpenConnect() },
                )
            }

            item {
                HubButton(
                    icon = WtIcons.Chat,
                    label = "Messages",
                    secondary = if (unread > 0) "$unread unread" else null,
                    highlight = unread > 0,
                    onClick = { graph.haptics.select(); onOpenConversations() },
                )
            }

            item {
                HubButton(
                    icon = WtIcons.Nodes,
                    label = "Nodes",
                    secondary = "$online of ${nodes.size} online",
                    onClick = { graph.haptics.select(); onOpenNodes() },
                )
            }

            item {
                HubButton(
                    icon = WtIcons.Channel,
                    label = "Channels",
                    onClick = { graph.haptics.select(); onOpenChannels() },
                )
            }

            item {
                HubButton(
                    icon = WtIcons.Map,
                    label = "Map",
                    secondary = "Nodes around you",
                    onClick = { graph.haptics.select(); onOpenMap() },
                )
            }

            item {
                HubButton(
                    icon = WtIcons.Waypoint,
                    label = "Waypoints",
                    onClick = { graph.haptics.select(); onOpenWaypoints() },
                )
            }

            item {
                HubButton(
                    icon = WtIcons.Signal,
                    label = "Radio status",
                    onClick = { graph.haptics.select(); onOpenStatus() },
                )
            }

            item {
                HubButton(
                    icon = WtIcons.Settings,
                    label = "Settings",
                    onClick = { graph.haptics.select(); onOpenSettings() },
                )
            }
        }
    }
}

@Composable
private fun LinkCard(
    link: LinkState,
    online: Int,
    total: Int,
    onClick: () -> Unit,
) {
    val inFlight = link is LinkState.Connecting ||
        link is LinkState.Syncing ||
        link is LinkState.Pairing ||
        link is LinkState.Reconnecting ||
        link is LinkState.Scanning

    val (dotColor, title) = when (link) {
        is LinkState.Connected -> MeshPalette.MeshGreen to "Connected"
        is LinkState.Syncing -> MeshPalette.Amber to "Syncing ${(link.progress * 100).toInt()}%"
        is LinkState.Connecting -> MeshPalette.Amber to "Connecting"
        is LinkState.Pairing -> MeshPalette.Amber to "Pairing"
        is LinkState.Reconnecting -> MeshPalette.Amber to "Reconnecting"
        is LinkState.Failed -> MeshPalette.Danger to "Disconnected"
        LinkState.Scanning -> MeshPalette.Amber to "Scanning"
        LinkState.Idle -> MeshPalette.SlateDim to "Not connected"
    }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = WtIcons.Mesh,
                contentDescription = null,
                tint = MeshPalette.MeshGreen,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    // Pulses only while something is actually in flight, so a steady dot
                    // genuinely means "settled" rather than "possibly stuck".
                    PulseDot(color = dotColor, active = inFlight)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                }
                Text(
                    text = link.deviceLabel ?: "Tap to pick a radio",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (total > 0) {
                    Text(
                        text = "$online of $total nodes heard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun HubButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    secondary: String? = null,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        // Tonal, not filled: six stacked primary-green buttons is a wall of colour on an
        // OLED watch. Green stays reserved for state — here, the icon of a row that has
        // something waiting.
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlight) {
                    MeshPalette.MeshGreen
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        },
        label = { Text(label, maxLines = 1) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1) } },
    )
    // No trailing Spacer here: a TransformingLazyColumn item must emit exactly one
    // layout node, and a second sibling silently collapses the whole row. Row spacing
    // comes from the column's own verticalArrangement.
}
