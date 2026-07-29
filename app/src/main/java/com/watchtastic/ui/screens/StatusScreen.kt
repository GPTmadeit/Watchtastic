package com.watchtastic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.MeshConstants
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.DetailRow
import com.watchtastic.ui.components.StatusDot
import com.watchtastic.ui.theme.MeshPalette

/** Everything about the radio itself, plus how the mesh around it is behaving. */
@Composable
fun StatusScreen() {
    val graph = LocalAppGraph.current
    val info by graph.store.radioInfo.collectAsStateWithLifecycle()
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val myNodeNum by graph.store.myNodeNum.collectAsStateWithLifecycle()
    val link by graph.repository.link.collectAsStateWithLifecycle()
    val messages by graph.store.messages.collectAsStateWithLifecycle()

    val self = nodes[myNodeNum]
    val now = System.currentTimeMillis()
    val online = nodes.values.count { it.isOnline(now, MeshConstants.NODE_STALE_MS) }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = { graph.haptics.select(); graph.repository.requestResync() }) {
                Text("Resync")
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Radio status") } }

            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusDot(
                            if (link.isUsable) MeshPalette.MeshGreen else MeshPalette.Danger,
                        )
                        Text(
                            text = self?.displayLong ?: "Unknown radio",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = self?.userId ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { ListSubHeader { Text("Device") } }
            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Firmware", info.firmwareVersion.ifBlank { "—" })
                    DetailRow("Hardware", info.hwModel.lowercase().replace('_', ' '))
                    DetailRow("Role", info.role.lowercase().replace('_', ' '))
                    DetailRow("Reboots", info.rebootCount.toString())
                    DetailRow("Bluetooth", if (info.hasBluetooth) "yes" else "no")
                    DetailRow("Wi-Fi", if (info.hasWifi) "yes" else "no")
                    DetailRow("PKI", if (info.hasPKC) "supported" else "no")
                }
            }

            item { ListSubHeader { Text("LoRa") } }
            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Region", info.region.ifBlank { "—" })
                    DetailRow("Preset", info.modemPreset.lowercase().replace('_', ' '))
                    DetailRow("Hop limit", info.hopLimit.toString())
                    DetailRow("Transmit", if (info.txEnabled) "enabled" else "disabled")
                    DetailRow("Channels", info.numChannels.toString())
                }
            }

            item { ListSubHeader { Text("Mesh") } }
            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Nodes known", nodes.size.toString())
                    DetailRow("Online now", online.toString())
                    DetailRow("Messages held", messages.size.toString())
                    self?.metrics?.batteryLevel?.let {
                        DetailRow("Battery", if (it > 100) "External power" else "$it%")
                    }
                    self?.metrics?.voltage?.let { DetailRow("Voltage", "%.2f V".format(it)) }
                    self?.metrics?.channelUtilization?.let {
                        DetailRow("Channel use", "%.1f%%".format(it))
                    }
                    self?.metrics?.airUtilTx?.let { DetailRow("Air TX", "%.1f%%".format(it)) }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
