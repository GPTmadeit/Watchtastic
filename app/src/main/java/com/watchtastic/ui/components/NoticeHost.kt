package com.watchtastic.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.watchtastic.ui.LocalAppGraph
import kotlinx.coroutines.delay

/**
 * Transient feedback for actions whose result arrives later than the tap.
 *
 * Requesting a position, tracing a route or writing radio config all complete
 * asynchronously — without this the wearer taps a button and nothing visibly happens,
 * which reads as a broken app. Deliberately a thin pill near the bottom rather than a
 * dialog: it must not steal the crown or block the list underneath.
 */
@Composable
fun BoxScope.NoticeHost() {
    val graph = LocalAppGraph.current
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // collectLatest so a newer notice replaces the one on screen instead of queueing
        // behind its dismissal timer.
        graph.repository.notices.collect { text ->
            notice = text
            delay(NOTICE_DURATION_MS)
            notice = null
        }
    }

    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Text(
            text = notice.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier
                // Inset generously: this sits low on a round, domed screen where the
                // usable width narrows fast.
                .padding(horizontal = 26.dp, vertical = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private const val NOTICE_DURATION_MS = 2_600L

/**
 * A clock that ticks slowly, so "3m ago" and online/offline counts stay honest without
 * anything having to invalidate them by hand.
 */
@Composable
fun rememberNow(intervalMs: Long = 30_000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), intervalMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
