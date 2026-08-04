package com.watchtastic.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.watchtastic.BuildConfig
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.input.rememberTextInput
import com.watchtastic.ui.theme.MeshPalette
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onOpenRadioConfig: () -> Unit,
    onOpenQuickReplies: () -> Unit,
    onOpenUpdate: () -> Unit,
    onForgetRadio: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()

    val autoConnect by graph.prefs.autoConnect.collectAsStateWithLifecycle()
    val shareLocation by graph.prefs.shareLocation.collectAsStateWithLifecycle()
    val haptics by graph.prefs.haptics.collectAsStateWithLifecycle()
    val notifyChannels by graph.prefs.notifyChannels.collectAsStateWithLifecycle()
    val notifyNewNodes by graph.prefs.notifyNewNodes.collectAsStateWithLifecycle()
    val imperial by graph.prefs.imperialUnits.collectAsStateWithLifecycle()
    val okToMqtt by graph.prefs.okToMqtt.collectAsStateWithLifecycle()
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val myNodeNum by graph.store.myNodeNum.collectAsStateWithLifecycle()
    val radioName by graph.prefs.radioName.collectAsStateWithLifecycle()
    val canned by graph.prefs.cannedMessages.collectAsStateWithLifecycle()
    val link by graph.repository.link.collectAsStateWithLifecycle()

    val self = nodes[myNodeNum]
    var confirmForget by remember { mutableStateOf(false) }
    var confirmResetDb by remember { mutableStateOf(false) }
    var pendingLongName by remember { mutableStateOf<String?>(null) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> graph.prefs.setShareLocation(granted) }

    // Renaming takes two prompts: the long name, then the four-character short name the
    // mesh actually shows on other people's screens.
    val askShortName = rememberTextInput { short ->
        val long = pendingLongName
        pendingLongName = null
        if (long != null) {
            scope.launch { graph.repository.setOwner(long, short) }
        }
    }
    val askLongName = rememberTextInput { long ->
        pendingLongName = long
        askShortName("Short name (max 4)")
    }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Settings") } }

            item { ListSubHeader { Text("Identity") } }
            item {
                Button(
                    onClick = {
                        graph.haptics.select()
                        askLongName("Your node name")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(WtIcons.Radio, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    label = { Text("Node name", maxLines = 1) },
                    secondaryLabel = {
                        Text(
                            text = self?.let { "${it.displayLong} (${it.displayShort})" }
                                ?: "Not connected",
                            maxLines = 1,
                        )
                    },
                )
            }

            item { ListSubHeader { Text("Connection") } }
            item {
                SwitchButton(
                    checked = autoConnect,
                    onCheckedChange = {
                        graph.haptics.tick()
                        graph.prefs.setAutoConnect(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Auto-connect", maxLines = 1) },
                    secondaryLabel = { Text(radioName ?: "No radio saved", maxLines = 1) },
                )
            }
            item {
                Button(
                    onClick = { graph.haptics.select(); onOpenRadioConfig() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(WtIcons.Signal, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    label = { Text("Radio configuration", maxLines = 1) },
                )
            }
            item {
                // Drop the link without forgetting the radio — for saving battery on a
                // long day when the mesh isn't needed.
                Button(
                    onClick = {
                        graph.haptics.select()
                        if (link.isUsable) {
                            graph.repository.disconnect()
                        } else {
                            graph.repository.resumeSavedRadio()
                        }
                    },
                    enabled = radioName != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(
                            WtIcons.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    label = {
                        Text(if (link.isUsable) "Disconnect" else "Connect now", maxLines = 1)
                    },
                )
            }

            item { ListSubHeader { Text("Position") } }
            item {
                SwitchButton(
                    checked = shareLocation,
                    onCheckedChange = { wanted ->
                        graph.haptics.tick()
                        if (wanted && !graph.location.hasPermission) {
                            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else {
                            graph.prefs.setShareLocation(wanted)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Share watch GPS", maxLines = 1) },
                    secondaryLabel = {
                        Text("Give the radio this watch's fix", maxLines = 1)
                    },
                )
            }
            item {
                SwitchButton(
                    checked = imperial,
                    onCheckedChange = { graph.haptics.tick(); graph.prefs.setImperialUnits(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Imperial units", maxLines = 1) },
                    secondaryLabel = { Text(if (imperial) "miles, feet" else "km, metres") },
                )
            }
            item {
                // Marks outgoing packets as approved for MQTT relay. Off means a gateway
                // on the mesh will carry everyone else's traffic but not yours.
                SwitchButton(
                    checked = okToMqtt,
                    onCheckedChange = { graph.haptics.tick(); graph.prefs.setOkToMqtt(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Allow MQTT relay", maxLines = 1) },
                    secondaryLabel = {
                        Text(
                            text = if (okToMqtt) {
                                "Your messages reach gateways"
                            } else {
                                "Gateways will skip your messages"
                            },
                            maxLines = 1,
                        )
                    },
                )
            }

            item { ListSubHeader { Text("Messaging") } }
            item {
                Button(
                    onClick = { graph.haptics.select(); onOpenQuickReplies() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(
                            WtIcons.QuickReply,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    label = { Text("Quick replies", maxLines = 1) },
                    secondaryLabel = { Text("${canned.size} saved", maxLines = 1) },
                )
            }
            item {
                SwitchButton(
                    checked = notifyChannels,
                    onCheckedChange = {
                        graph.haptics.tick()
                        graph.prefs.setNotifyChannels(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Channel messages", maxLines = 1) },
                    secondaryLabel = {
                        Text(if (notifyChannels) "Notify for all" else "Direct only", maxLines = 1)
                    },
                )
            }
            item {
                SwitchButton(
                    checked = notifyNewNodes,
                    onCheckedChange = {
                        graph.haptics.tick()
                        graph.prefs.setNotifyNewNodes(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New nodes", maxLines = 1) },
                    secondaryLabel = {
                        Text("Alert when a node first appears", maxLines = 1)
                    },
                )
            }
            item {
                SwitchButton(
                    checked = haptics,
                    onCheckedChange = {
                        graph.prefs.setHaptics(it)
                        if (it) graph.haptics.select()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Haptics", maxLines = 1) },
                    secondaryLabel = { Text("Buzz on mesh events", maxLines = 1) },
                )
            }

            item { ListSubHeader { Text("Maintenance") } }
            item {
                Button(
                    onClick = { graph.haptics.select(); onOpenUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(
                            WtIcons.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    label = { Text("Software update", maxLines = 1) },
                    secondaryLabel = { Text("Version ${BuildConfig.VERSION_NAME}", maxLines = 1) },
                )
            }
            item {
                Button(
                    onClick = { graph.haptics.heavy(); confirmResetDb = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(WtIcons.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    label = { Text("Clear node database", maxLines = 1) },
                )
            }
            item {
                Button(
                    onClick = { graph.haptics.heavy(); confirmForget = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    icon = {
                        Icon(
                            WtIcons.Bluetooth,
                            contentDescription = null,
                            tint = MeshPalette.Danger,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    label = { Text("Forget radio", maxLines = 1) },
                )
            }

            item {
                Text(
                    text = "Watchtastic ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }

    AlertDialog(
        visible = confirmResetDb,
        onDismissRequest = { confirmResetDb = false },
        title = { Text("Clear node database?") },
        text = { Text("The radio rebuilds it as it hears nodes again.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmResetDb = false
                    scope.launch { graph.repository.resetNodeDb() }
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmResetDb = false })
        },
    )

    AlertDialog(
        visible = confirmForget,
        onDismissRequest = { confirmForget = false },
        title = { Text("Forget this radio?") },
        text = { Text("Messages and nodes stored on the watch are deleted.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmForget = false
                    graph.repository.forgetRadio()
                    onForgetRadio()
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmForget = false })
        },
    )
}
