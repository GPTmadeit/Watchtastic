package com.watchtastic.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.BuildConfig
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.DetailRow
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette
import com.watchtastic.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Software updates, pulled from the shared release folder.
 *
 * Three deliberate steps — check, download, install — rather than one "update" button.
 * On a watch over Wi-Fi, a several-megabyte download is a thing the wearer should choose
 * to start, and the install is confirmed by the system on top of that.
 */
@Composable
fun UpdateScreen() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by graph.updates.state.collectAsStateWithLifecycle()

    // Check once on open: arriving at this screen *is* the request to check.
    LaunchedEffect(Unit) {
        if (state is UpdateState.Idle) graph.updates.check()
    }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Update") } }

            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Installed", BuildConfig.VERSION_NAME)
                    DetailRow("Source", "Shared Drive folder")
                }
            }

            when (val current = state) {
                is UpdateState.Checking -> item { Busy("Checking for updates…") }

                is UpdateState.UpToDate -> {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                WtIcons.Check,
                                contentDescription = null,
                                tint = MeshPalette.MeshGreen,
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "You're on the latest build",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item { CheckAgainButton() }
                }

                is UpdateState.Available -> {
                    item {
                        Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Version ${current.build.version} available",
                                style = MaterialTheme.typography.titleSmall,
                                color = MeshPalette.MeshGreen,
                            )
                            Text(
                                current.build.fileName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                graph.haptics.select()
                                scope.launch { graph.updates.download(current.build) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Download", maxLines = 1) },
                        )
                    }
                }

                is UpdateState.Downloading -> item {
                    Busy(
                        text = "Downloading ${(current.progress * 100).toInt()}%",
                        progress = current.progress,
                    )
                }

                is UpdateState.ReadyToInstall -> {
                    item {
                        Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Ready to install ${current.build.version}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Signature verified against this build.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Installing needs a per-app permission that the manifest entry alone
                    // doesn't grant. Without this the wearer taps Install, gets a bare
                    // "not allowed from this source" screen, and has nowhere obvious to go.
                    if (!graph.updates.canInstall) {
                        item {
                            Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "Allow this app to install updates first.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    graph.haptics.select()
                                    runCatching {
                                        // Launched from the Activity context, so no
                                        // NEW_TASK flag — that would spawn a second task
                                        // and misrepresent the app in Wear recents.
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                "package:${context.packageName}".toUri(),
                                            ),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Open permission", maxLines = 1) },
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                graph.haptics.heavy()
                                scope.launch { graph.updates.install() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (graph.updates.canInstall) {
                                ButtonDefaults.buttonColors()
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                            label = { Text("Install", maxLines = 1) },
                        )
                    }
                }

                is UpdateState.Installing -> item { Busy("Waiting for confirmation…") }

                is UpdateState.Failed -> {
                    item {
                        Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                current.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item { CheckAgainButton() }
                }

                UpdateState.Idle -> item { CheckAgainButton() }
            }

            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun CheckAgainButton() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            graph.haptics.select()
            scope.launch { graph.updates.check() }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = {
            Icon(WtIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        label = { Text("Check again", maxLines = 1) },
    )
}

@Composable
private fun Busy(text: String, progress: Float? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(40.dp),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
