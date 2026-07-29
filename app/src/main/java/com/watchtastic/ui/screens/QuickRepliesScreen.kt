package com.watchtastic.ui.screens

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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.input.rememberTextInput

/**
 * The canned phrases offered in chat.
 *
 * Worth editing on-device: the useful set is personal and situational — a hiking party
 * and a race marshal want different two-tap answers, and neither wants to dictate them
 * fresh every time.
 */
@Composable
fun QuickRepliesScreen() {
    val graph = LocalAppGraph.current
    val replies by graph.prefs.cannedMessages.collectAsStateWithLifecycle()

    val addReply = rememberTextInput { text ->
        graph.prefs.setCannedMessages(replies + text)
        graph.haptics.select()
    }

    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = { graph.haptics.select(); addReply("New quick reply") }) {
                Text("Add")
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { ListHeader { Text("Quick replies") } }

            if (replies.isEmpty()) {
                item {
                    EmptyState(
                        icon = WtIcons.QuickReply,
                        title = "No quick replies",
                        subtitle = "Add phrases you send often",
                    )
                }
            } else {
                items(replies.size, key = { replies[it] + it }) { index ->
                    Button(
                        onClick = { graph.haptics.tick() },
                        onLongClick = {
                            graph.haptics.heavy()
                            graph.prefs.setCannedMessages(
                                replies.filterIndexed { i, _ -> i != index },
                            )
                        },
                        onLongClickLabel = "Delete",
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(replies[index], maxLines = 2) },
                    )
                }
                item {
                    Text(
                        text = "Long-press to delete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
