package com.watchtastic.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.wear.compose.material3.Dialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Picker
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberPickerState
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.DetailRow
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette
import kotlinx.coroutines.launch
import org.meshtastic.proto.ConfigProtos

private enum class PickKind { Region, Preset, Role }

/**
 * Changes that live on the radio rather than the watch.
 *
 * Long enumerations — 37 regions, 17 modem presets — are exactly what the crown is for,
 * so those open a full-screen [Picker] that snaps and ticks per detent. Numeric ranges
 * use a [Slider] with its own increment targets, which stays usable with a gloved thumb.
 *
 * Every write here reboots the radio, so each one is a deliberate, confirmed action
 * rather than a live-updating control.
 */
@Composable
fun RadioConfigScreen() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val info by graph.store.radioInfo.collectAsStateWithLifecycle()
    val link by graph.repository.link.collectAsStateWithLifecycle()

    var picking by remember { mutableStateOf<PickKind?>(null) }
    var confirmReboot by remember { mutableStateOf(false) }
    var confirmShutdown by remember { mutableStateOf(false) }
    var hopLimit by remember(info.hopLimit) { mutableFloatStateOf(info.hopLimit.toFloat()) }

    val regions = remember {
        ConfigProtos.Config.LoRaConfig.RegionCode.values()
            .filter { it != ConfigProtos.Config.LoRaConfig.RegionCode.UNRECOGNIZED }
    }
    val presets = remember {
        ConfigProtos.Config.LoRaConfig.ModemPreset.values()
            .filter { it != ConfigProtos.Config.LoRaConfig.ModemPreset.UNRECOGNIZED }
    }
    val roles = remember {
        ConfigProtos.Config.DeviceConfig.Role.values()
            .filter { it != ConfigProtos.Config.DeviceConfig.Role.UNRECOGNIZED }
    }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Radio config") } }

            if (!link.isUsable) {
                item {
                    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Connect to a radio to change its settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { ListSubHeader { Text("LoRa") } }

            item {
                ConfigButton(
                    label = "Region",
                    value = info.region.ifBlank { "UNSET" },
                    enabled = link.isUsable,
                ) { graph.haptics.select(); picking = PickKind.Region }
            }
            item {
                ConfigButton(
                    label = "Modem preset",
                    value = info.modemPreset.lowercase().replace('_', ' '),
                    enabled = link.isUsable,
                ) { graph.haptics.select(); picking = PickKind.Preset }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    DetailRow("Hop limit", hopLimit.toInt().toString())
                    Slider(
                        value = hopLimit,
                        onValueChange = { graph.haptics.tick(); hopLimit = it },
                        steps = 5,
                        valueRange = 1f..7f,
                        enabled = link.isUsable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (hopLimit.toInt() != info.hopLimit) {
                        Button(
                            onClick = {
                                graph.haptics.heavy()
                                scope.launch {
                                    graph.repository.updateLoRaConfig(hopLimit = hopLimit.toInt())
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Apply hop limit", maxLines = 1) },
                        )
                    }
                }
            }

            item {
                SwitchButton(
                    checked = info.txEnabled,
                    onCheckedChange = { enabled ->
                        graph.haptics.heavy()
                        scope.launch { graph.repository.updateLoRaConfig(txEnabled = enabled) }
                    },
                    enabled = link.isUsable,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Transmit", maxLines = 1) },
                    secondaryLabel = {
                        Text(if (info.txEnabled) "Radio can send" else "Receive only", maxLines = 1)
                    },
                )
            }

            item { ListSubHeader { Text("Device") } }
            item {
                ConfigButton(
                    label = "Role",
                    value = info.role.lowercase().replace('_', ' '),
                    enabled = link.isUsable,
                ) { graph.haptics.select(); picking = PickKind.Role }
            }

            item { ListSubHeader { Text("Power") } }
            item {
                Button(
                    onClick = { graph.haptics.heavy(); confirmReboot = true },
                    enabled = link.isUsable,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(WtIcons.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    label = { Text("Reboot radio", maxLines = 1) },
                )
            }
            item {
                Button(
                    onClick = { graph.haptics.heavy(); confirmShutdown = true },
                    enabled = link.isUsable,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    icon = {
                        Icon(
                            WtIcons.Power,
                            contentDescription = null,
                            tint = MeshPalette.Danger,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    label = { Text("Shut down radio", maxLines = 1) },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    // ------------------------------------------------------- crown pickers

    val kind = picking
    if (kind != null) {
        val options = when (kind) {
            PickKind.Region -> regions.map { it.name }
            PickKind.Preset -> presets.map { it.name.lowercase().replace('_', ' ') }
            PickKind.Role -> roles.map { it.name.lowercase().replace('_', ' ') }
        }
        val currentIndex = when (kind) {
            PickKind.Region -> regions.indexOfFirst { it.name == info.region }
            PickKind.Preset -> presets.indexOfFirst { it.name == info.modemPreset }
            PickKind.Role -> roles.indexOfFirst { it.name == info.role }
        }.coerceAtLeast(0)

        CrownPicker(
            title = when (kind) {
                PickKind.Region -> "Region"
                PickKind.Preset -> "Modem preset"
                PickKind.Role -> "Device role"
            },
            options = options,
            initialIndex = currentIndex,
            onDismiss = { picking = null },
            onSelect = { index ->
                picking = null
                graph.haptics.heavy()
                scope.launch {
                    when (kind) {
                        PickKind.Region ->
                            graph.repository.updateLoRaConfig(region = regions[index])

                        PickKind.Preset ->
                            graph.repository.updateLoRaConfig(preset = presets[index])

                        PickKind.Role -> graph.repository.setDeviceRole(roles[index])
                    }
                }
            },
        )
    }

    AlertDialog(
        visible = confirmReboot,
        onDismissRequest = { confirmReboot = false },
        title = { Text("Reboot radio?") },
        text = { Text("The link drops for a few seconds.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmReboot = false
                    scope.launch { graph.repository.reboot() }
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmReboot = false })
        },
    )

    AlertDialog(
        visible = confirmShutdown,
        onDismissRequest = { confirmShutdown = false },
        title = { Text("Shut down radio?") },
        text = { Text("You'll need to power it on by hand.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmShutdown = false
                    scope.launch { graph.repository.shutdown() }
                },
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmShutdown = false })
        },
    )
}

@Composable
private fun ConfigButton(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        label = { Text(label, maxLines = 1) },
        secondaryLabel = { Text(value, maxLines = 1) },
    )
}

/**
 * A full-screen list picker driven by the rotating crown.
 *
 * [Picker] snaps to whole options and emits a haptic detent per step, which is what makes
 * scrubbing 37 regions on a 1.2" screen tolerable — the wearer feels each entry go past
 * instead of hunting with a fingertip that covers three rows.
 */
@Composable
private fun CrownPicker(
    title: String,
    options: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    Dialog(visible = true, onDismissRequest = onDismiss) {
        key(title, options.size) {
            val pickerState = rememberPickerState(
                initialNumberOfOptions = options.size.coerceAtLeast(1),
                initiallySelectedIndex = initialIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)),
                shouldRepeatOptions = false,
            )

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Picker(
                        state = pickerState,
                        contentDescription = { title },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { index ->
                        Text(
                            text = options.getOrElse(index) { "" },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                    Button(
                        onClick = { onSelect(pickerState.selectedOptionIndex) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Set", maxLines = 1) },
                    )
                }
            }
        }
    }
}
