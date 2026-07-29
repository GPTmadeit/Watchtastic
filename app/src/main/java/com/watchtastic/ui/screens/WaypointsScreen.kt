package com.watchtastic.ui.screens

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.model.Waypoint
import com.watchtastic.mesh.model.bearingLabel
import com.watchtastic.mesh.model.formatDistance
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.input.rememberTextInput
import kotlinx.coroutines.launch

/**
 * Waypoints shared across the mesh.
 *
 * Dropping one uses the watch's own GNSS fix rather than the radio's, which is the whole
 * point of having the client on your wrist — you can mark where *you* are standing, not
 * where the radio in your pack happens to be.
 */
@Composable
fun WaypointsScreen() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()

    val waypoints by graph.store.waypoints.collectAsStateWithLifecycle()
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val myNodeNum by graph.store.myNodeNum.collectAsStateWithLifecycle()
    val imperial by graph.prefs.imperialUnits.collectAsStateWithLifecycle()
    val channels by graph.store.channels.collectAsStateWithLifecycle()
    val watchFix by graph.location.lastFix.collectAsStateWithLifecycle()

    // Prefer the watch's own fix; fall back to whatever the radio last reported.
    val here = watchFix ?: nodes[myNodeNum]?.position
    val primaryChannel = remember(channels) {
        channels.firstOrNull { it.isEnabled }?.index ?: 0
    }

    var pendingDelete by remember { mutableStateOf<Waypoint?>(null) }
    var locating by remember { mutableStateOf(false) }

    val dropWaypoint = rememberTextInput { name ->
        scope.launch {
            // Take a fix on demand rather than requiring the wearer to have switched on
            // continuous position sharing first — marking a spot and broadcasting your
            // location are separate decisions.
            locating = here == null
            val position = here ?: graph.location.requestSingleFix()
            locating = false
            if (position == null) {
                graph.haptics.failed()
                return@launch
            }
            graph.repository.sendWaypoint(primaryChannel, name, "", position)
            graph.haptics.sent()
        }
    }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = {
                    graph.haptics.select()
                    dropWaypoint("Waypoint name")
                },
                // Enabled even with no fix yet: we go and get one.
                enabled = !locating && graph.location.hasPermission,
            ) {
                Text(if (locating) "Locating…" else "Drop here")
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Waypoints") } }

            if (waypoints.isEmpty()) {
                item {
                    EmptyState(
                        icon = WtIcons.Waypoint,
                        title = "No waypoints",
                        subtitle = if (here == null) {
                            "Turn on GPS sharing to drop one"
                        } else {
                            "Drop one, or wait for the mesh"
                        },
                    )
                }
            } else {
                items(waypoints.size, key = { waypoints[it].id }) { index ->
                    val waypoint = waypoints[index]
                    val distance = here?.let {
                        it.distanceTo(
                            com.watchtastic.mesh.model.NodePosition(
                                waypoint.latitude,
                                waypoint.longitude,
                            ),
                        )
                    }
                    val bearing = here?.let {
                        it.bearingTo(
                            com.watchtastic.mesh.model.NodePosition(
                                waypoint.latitude,
                                waypoint.longitude,
                            ),
                        )
                    }

                    Button(
                        onClick = { graph.haptics.select() },
                        onLongClick = {
                            graph.haptics.heavy()
                            pendingDelete = waypoint
                        },
                        onLongClickLabel = "Delete",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = {
                            Icon(
                                WtIcons.Waypoint,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        label = {
                            Text(waypoint.name.ifBlank { "Waypoint" }, maxLines = 1)
                        },
                        secondaryLabel = {
                            val author = when {
                                waypoint.createdByNum == 0 -> null
                                waypoint.createdByNum == myNodeNum -> "You"
                                else -> nodes[waypoint.createdByNum]?.displayShort
                            }
                            val detail = buildList {
                                distance?.let { add(formatDistance(it, imperial)) }
                                bearing?.let { add("${it.toInt()}° ${bearingLabel(it)}") }
                                author?.let { add(it) }
                            }.joinToString(" · ")
                            Text(detail.ifBlank { "No position reference" }, maxLines = 1)
                        },
                    )
                }
                item {
                    Text(
                        text = "Long-press to remove",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    val target = pendingDelete
    AlertDialog(
        visible = target != null,
        onDismissRequest = { pendingDelete = null },
        title = { Text("Remove waypoint?") },
        text = { Text("Only removes it from this watch.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    target?.let { graph.store.deleteWaypoint(it.id) }
                    pendingDelete = null
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { pendingDelete = null })
        },
    )
}
