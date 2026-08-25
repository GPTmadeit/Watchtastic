package com.watchtastic.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.MeshConstants
import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.mesh.model.bearingLabel
import com.watchtastic.mesh.model.formatDistance
import com.watchtastic.mesh.model.signalQualityOf
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.DetailRow
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.components.NodeAvatar
import com.watchtastic.ui.components.SignalBars
import com.watchtastic.ui.components.shortAgo
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette
import kotlinx.coroutines.launch

/** Everything known about one peer, and everything that can be done to it. */
@Composable
fun NodeDetailScreen(
    nodeNum: Int,
    onOpenChat: (String) -> Unit,
    onOpenCompass: () -> Unit,
    onRemoved: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val myNodeNum by graph.store.myNodeNum.collectAsStateWithLifecycle()
    val imperial by graph.prefs.imperialUnits.collectAsStateWithLifecycle()
    val traceRoutes by graph.repository.traceRoutes.collectAsStateWithLifecycle()

    val node = nodes[nodeNum]
    val myPosition = nodes[myNodeNum]?.position
    var confirmRemove by remember { mutableStateOf(false) }

    val listState = rememberTransformingLazyColumnState()

    if (node == null) {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                item { EmptyState(icon = WtIcons.Nodes, title = "Node not found") }
            }
        }
        return
    }

    val now = System.currentTimeMillis()
    val online = node.isOnline(now, MeshConstants.NODE_STALE_MS)
    val distance = myPosition?.let { mine -> node.position?.let { mine.distanceTo(it) } }
    val bearing = myPosition?.let { mine -> node.position?.let { mine.bearingTo(it) } }
    val trace = traceRoutes.firstOrNull { it.targetNum == nodeNum }

    val mapsIntent = remember(node.position) {
        node.position?.let { position ->
            val label = Uri.encode(node.displayLong)
            val uri = "geo:${position.latitude},${position.longitude}" +
                "?q=${position.latitude},${position.longitude}($label)"
            Intent(Intent.ACTION_VIEW, uri.toUri())
                .takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            // Messaging is the reason you opened a node, so it owns the edge button —
            // unless the node has told us it can't receive text.
            EdgeButton(
                onClick = {
                    graph.haptics.select()
                    onOpenChat(ConversationKey.direct(nodeNum))
                },
                enabled = !node.isUnmessagable && !node.isSelf,
            ) {
                Text(if (node.isSelf) "This radio" else "Message")
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NodeAvatar(node, size = 44)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = node.displayLong,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    Text(
                        text = node.userId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    SignalBars(
                        if (online) signalQualityOf(node.snr, node.rssi)
                        else com.watchtastic.mesh.model.SignalQuality.None,
                    )
                }
            }

            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Status", if (online) "Online" else "Last seen ${shortAgo(node.lastHeardMs)}")
                    DetailRow("Hops", node.hopsAway?.let { if (it == 0) "Direct" else "$it" } ?: "—")
                    DetailRow("SNR", if (node.snr != 0f) "%.1f dB".format(node.snr) else "—")
                    DetailRow("RSSI", if (node.rssi != 0) "${node.rssi} dBm" else "—")
                    DetailRow("Role", node.role.lowercase().replace('_', ' '))
                    DetailRow("Hardware", node.hwModel.lowercase().replace('_', ' '))
                    DetailRow("Channel", node.channelIndex.toString())
                    if (node.viaMqtt) DetailRow("Via", "MQTT")
                    if (node.hasPublicKey) DetailRow("Encryption", "Public key known")
                }
            }

            if (node.metrics.hasTelemetry) {
                item { ListSubHeader { Text("Telemetry") } }
                item {
                    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        node.metrics.batteryLevel?.let {
                            DetailRow("Battery", if (it > 100) "External power" else "$it%")
                        }
                        node.metrics.voltage?.let { DetailRow("Voltage", "%.2f V".format(it)) }
                        node.metrics.channelUtilization?.let {
                            DetailRow("Channel use", "%.1f%%".format(it))
                        }
                        node.metrics.airUtilTx?.let { DetailRow("Air TX", "%.1f%%".format(it)) }
                        node.metrics.temperature?.let { DetailRow("Temp", "%.1f °C".format(it)) }
                        node.metrics.relativeHumidity?.let {
                            DetailRow("Humidity", "%.0f%%".format(it))
                        }
                        node.metrics.uptimeSeconds?.let {
                            DetailRow("Uptime", formatUptime(it))
                        }
                        // Lightning leads on colour because it is the one reading here
                        // that should change what you do next.
                        node.metrics.lightningDistanceKm?.let {
                            DetailRow(
                                label = "Lightning",
                                value = formatDistance(it * 1000.0, imperial),
                                valueColor = MeshPalette.Amber,
                            )
                        }
                        node.metrics.lightningStrikes1h?.let {
                            DetailRow("Strikes (1h)", it.toString())
                        }
                    }
                }
            }

            if (node.position != null) {
                item { ListSubHeader { Text("Position") } }
                item {
                    Card(
                        onClick = { graph.haptics.select(); onOpenCompass() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        distance?.let {
                            DetailRow("Distance", formatDistance(it, imperial))
                        }
                        bearing?.let {
                            DetailRow("Bearing", "${it.toInt()}° ${bearingLabel(it)}")
                        }
                        DetailRow("Latitude", "%.5f".format(node.position.latitude))
                        DetailRow("Longitude", "%.5f".format(node.position.longitude))
                        node.position.altitudeMeters?.let { DetailRow("Altitude", "$it m") }
                        if (distance != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap for compass",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (trace != null) {
                item { ListSubHeader { Text("Route") } }
                item {
                    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        val outbound = trace.towards.joinToString(" → ") {
                            nodes[it.nodeNum]?.displayShort ?: MeshConstants.nodeIdOf(it.nodeNum)
                        }
                        DetailRow("Hops out", trace.towards.size.toString())
                        if (outbound.isNotEmpty()) {
                            Text(
                                text = outbound,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { ListSubHeader { Text("Actions") } }

            // Watchtastic's own map is an offline plot; for street context we hand the
            // coordinates to whatever mapping app the watch actually has. Hidden when
            // nothing can handle a geo: intent, rather than failing on tap.
            if (node.position != null && mapsIntent != null) {
                item {
                    ActionButton(WtIcons.Map, "Open in Maps") {
                        graph.haptics.select()
                        runCatching { context.startActivity(mapsIntent) }
                    }
                }
            }

            if (!node.isSelf) {
                item {
                    ActionButton(WtIcons.Location, "Request position") {
                        graph.haptics.select()
                        scope.launch { graph.repository.requestPosition(nodeNum) }
                    }
                }
                item {
                    ActionButton(WtIcons.Route, "Trace route") {
                        graph.haptics.select()
                        scope.launch { graph.repository.traceRoute(nodeNum) }
                    }
                }
                item {
                    ActionButton(
                        icon = WtIcons.Star,
                        label = if (node.isFavorite) "Remove favourite" else "Add favourite",
                    ) {
                        graph.haptics.select()
                        scope.launch { graph.repository.setFavorite(nodeNum, !node.isFavorite) }
                    }
                }
                item {
                    ActionButton(
                        icon = WtIcons.Mute,
                        label = if (node.isMuted) "Unmute" else "Mute",
                    ) {
                        graph.haptics.select()
                        scope.launch { graph.repository.toggleMuted(nodeNum) }
                    }
                }
                item {
                    ActionButton(
                        icon = WtIcons.Close,
                        label = if (node.isIgnored) "Stop ignoring" else "Ignore",
                    ) {
                        graph.haptics.select()
                        scope.launch { graph.repository.setIgnored(nodeNum, !node.isIgnored) }
                    }
                }
                item {
                    ActionButton(
                        icon = WtIcons.Trash,
                        label = "Remove node",
                        destructive = true,
                    ) {
                        graph.haptics.heavy()
                        confirmRemove = true
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    AlertDialog(
        visible = confirmRemove,
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove ${node.displayShort}?") },
        text = { Text("It will come back if the radio hears it again.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmRemove = false
                    scope.launch {
                        graph.repository.removeNode(nodeNum)
                        onRemoved()
                    }
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmRemove = false })
        },
    )
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MeshPalette.Danger else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        },
        label = { Text(label, maxLines = 1) },
    )
}

private fun formatUptime(seconds: Int): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
