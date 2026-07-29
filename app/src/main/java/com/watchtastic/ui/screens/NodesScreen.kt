package com.watchtastic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.watchtastic.mesh.MeshConstants
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.formatDistance
import com.watchtastic.mesh.model.signalQualityOf
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.components.NodeAvatar
import com.watchtastic.ui.components.SignalBars
import com.watchtastic.ui.components.rememberNow
import com.watchtastic.ui.components.shortAgo
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette

/**
 * Everyone the radio has heard.
 *
 * Ordered by usefulness rather than alphabetically: favourites, then whoever is live
 * right now, then by how recently they were heard. Ignored nodes sink to the bottom.
 */
@Composable
fun NodesScreen(onOpenNode: (Int) -> Unit) {
    val graph = LocalAppGraph.current
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val myNodeNum by graph.store.myNodeNum.collectAsStateWithLifecycle()
    val imperial by graph.prefs.imperialUnits.collectAsStateWithLifecycle()

    val now by rememberNow()
    val myPosition = nodes[myNodeNum]?.position

    val ordered = remember(nodes, now) {
        nodes.values.sortedWith(
            compareBy<MeshNode> { it.isIgnored }
                .thenByDescending { it.isSelf }
                .thenByDescending { it.isFavorite }
                .thenByDescending { it.isOnline(now, MeshConstants.NODE_STALE_MS) }
                .thenByDescending { it.lastHeardSeconds },
        )
    }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Nodes (${nodes.size})") } }

            if (ordered.isEmpty()) {
                item {
                    EmptyState(
                        icon = WtIcons.Nodes,
                        title = "No nodes yet",
                        subtitle = "They appear as the radio hears them",
                    )
                }
            } else {
                items(ordered.size, key = { ordered[it].num }) { index ->
                    val node = ordered[index]
                    val distance = myPosition?.let { mine ->
                        node.position?.let { theirs -> mine.distanceTo(theirs) }
                    }
                    NodeRow(
                        node = node,
                        online = node.isOnline(now, MeshConstants.NODE_STALE_MS),
                        distanceLabel = distance?.let { formatDistance(it, imperial) },
                        onClick = { graph.haptics.select(); onOpenNode(node.num) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: MeshNode,
    online: Boolean,
    distanceLabel: String?,
    onClick: () -> Unit,
) {
    // A watch row fits roughly three facts. Recency only earns its place once a node has
    // gone quiet — while it's live, hops/distance/battery are the useful ones.
    val detail = buildList {
        if (node.isSelf) {
            add("This radio")
        } else {
            if (!online) add(shortAgo(node.lastHeardMs))
            // Hop count is meaningless for our own radio — it's zero by definition.
            node.hopsAway?.let {
                add(if (it == 0) "direct" else "$it hop${if (it == 1) "" else "s"}")
            }
        }
        distanceLabel?.let { add(it) }
        node.metrics.batteryLevel?.let { add(if (it > 100) "ext" else "$it%") }
    }.joinToString(" · ")

    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { NodeAvatar(node, size = 28) },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.displayLong,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (node.isFavorite) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        WtIcons.Star,
                        contentDescription = "Favourite",
                        tint = MeshPalette.Amber,
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (node.isMuted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        WtIcons.Mute,
                        contentDescription = "Muted",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        },
        secondaryLabel = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SignalBars(
                    if (online) signalQualityOf(node.snr, node.rssi) else
                        com.watchtastic.mesh.model.SignalQuality.None,
                )
                Text(detail, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
