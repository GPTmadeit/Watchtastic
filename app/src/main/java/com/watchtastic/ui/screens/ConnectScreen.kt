package com.watchtastic.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.model.DiscoveredRadio
import com.watchtastic.mesh.model.LinkState
import com.watchtastic.service.MeshService
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.components.SignalBars
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.mesh.model.SignalQuality
import com.watchtastic.ui.theme.MeshPalette
import kotlinx.coroutines.flow.catch

/**
 * Finding and pairing a radio.
 *
 * The scan is filtered on the Meshtastic service UUID, so this list only ever contains
 * radios — no scrolling past headphones and TVs to find yours.
 */
@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val link by graph.repository.link.collectAsStateWithLifecycle()

    val required = remember {
        buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            required.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Notifications are a nice-to-have; Bluetooth is not. Only the two BLE grants
        // gate the scan.
        hasPermissions = result[Manifest.permission.BLUETOOTH_SCAN] == true &&
            result[Manifest.permission.BLUETOOTH_CONNECT] == true
    }

    var radios by remember { mutableStateOf<List<DiscoveredRadio>>(emptyList()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val savedAddress by graph.prefs.radioAddress.collectAsStateWithLifecycle()

    // Scan whenever the handshake isn't mid-flight — including while already connected.
    // Only pause during connect/pair/sync, where a concurrent scan genuinely slows the
    // link down. Previously this also stopped once connected, which meant the one screen
    // for choosing a radio showed an empty list to anyone who already had one, and the
    // only way to reach a second radio was Forget Radio. Keyed on a plain boolean so the
    // Syncing progress ticks don't restart the scan on every frame.
    val handshaking = link is LinkState.Connecting ||
        link is LinkState.Pairing ||
        link is LinkState.Syncing
    val shouldScan = hasPermissions && !handshaking

    LaunchedEffect(shouldScan) {
        if (!shouldScan) return@LaunchedEffect
        scanError = null
        graph.repository.scanner.scan()
            .catch { scanError = it.message ?: "Scan failed" }
            .collect { radios = it }
    }

    // Leave only when *this screen* asked for a connection. Firing on any Connected state
    // meant opening Connect while already linked bounced straight back out.
    var awaitingConnect by remember { mutableStateOf(false) }
    LaunchedEffect(link, awaitingConnect) {
        if (awaitingConnect && link is LinkState.Connected) {
            awaitingConnect = false
            onConnected()
        }
    }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Connect") } }

            when {
                !hasPermissions -> item {
                    PermissionPrompt { permissionLauncher.launch(required.toTypedArray()) }
                }

                link is LinkState.Pairing -> item {
                    BusyCard(
                        title = "Pairing",
                        detail = "Enter the PIN shown on your radio's screen.",
                    )
                }

                link is LinkState.Connecting -> item {
                    BusyCard(title = "Connecting", detail = (link as LinkState.Connecting).deviceName)
                }

                link is LinkState.Syncing -> item {
                    val syncing = link as LinkState.Syncing
                    BusyCard(
                        title = "Syncing",
                        detail = "Downloading node database…",
                        progress = syncing.progress,
                    )
                }

                else -> {
                    if (link is LinkState.Failed) {
                        item { FailureCard((link as LinkState.Failed).reason) }
                    }
                    if (!graph.repository.scanner.isBluetoothOn) {
                        item {
                            EmptyState(
                                icon = WtIcons.Bluetooth,
                                title = "Bluetooth is off",
                                subtitle = "Turn it on in watch settings",
                            )
                        }
                    } else if (scanError != null) {
                        item { EmptyState(icon = WtIcons.Bluetooth, title = scanError!!) }
                    } else if (radios.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ScanPulse()
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Looking for radios…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        if (link is LinkState.Connected) {
                            item {
                                Text(
                                    text = "Tap another radio to switch",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        items(radios.size, key = { radios[it].address }) { index ->
                            val radio = radios[index]
                            RadioRow(
                                radio = radio,
                                isCurrent = radio.address == savedAddress && link is LinkState.Connected,
                            ) {
                                graph.haptics.select()
                                // Permissions are guaranteed here — the scan that produced
                                // this row required them — so this is the first safe
                                // moment to bring the link service up.
                                MeshService.start(context)
                                awaitingConnect = true
                                graph.repository.connect(radio.address, radio.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioRow(radio: DiscoveredRadio, isCurrent: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCurrent) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        icon = {
            Icon(
                imageVector = WtIcons.Radio,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        },
        label = { Text(radio.name, maxLines = 1) },
        secondaryLabel = {
            Text(
                text = when {
                    isCurrent -> "Connected · ${radio.rssi} dBm"
                    radio.bonded -> "Paired · ${radio.rssi} dBm"
                    else -> "${radio.rssi} dBm"
                },
                maxLines = 1,
            )
        },
    )
}

/**
 * Three rings expanding out of the app mark, a third of a cycle apart.
 *
 * A generic spinner says "busy"; this says "transmitting and listening", which is
 * literally what a BLE scan is doing. The mark sits still at the centre so the screen
 * still identifies the app while it waits.
 */
@Composable
private fun ScanPulse() {
    val transition = rememberInfiniteTransition(label = "scan")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanPhase",
    )
    val ringColor = MeshPalette.MeshGreen

    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxRadius = size.minDimension / 2f
            repeat(3) { index ->
                // Stagger by a third of a cycle, wrapping so rings are evenly spaced.
                val ringPhase = (phase + index / 3f) % 1f
                drawCircle(
                    color = ringColor.copy(alpha = (1f - ringPhase) * 0.5f),
                    radius = maxRadius * (0.22f + ringPhase * 0.78f),
                    style = Stroke(width = 2f),
                )
            }
        }
        Icon(
            imageVector = WtIcons.Mesh,
            contentDescription = null,
            tint = ringColor,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Watchtastic needs Bluetooth to reach your radio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onGrant,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Grant access") },
        )
    }
}

@Composable
private fun BusyCard(title: String, detail: String, progress: Float? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FailureCard(reason: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SignalBars(SignalQuality.None)
        Spacer(Modifier.height(6.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MeshPalette.Danger,
            textAlign = TextAlign.Center,
        )
    }
}
