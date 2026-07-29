package com.watchtastic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.MsgStatus
import com.watchtastic.mesh.model.SignalQuality
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette
import kotlin.math.absoluteValue

/**
 * Gives every node a stable colour derived from its number.
 *
 * On a screen this small, colour is a faster identifier than a truncated name — the same
 * peer is always the same hue in the node list, in chat and on the compass.
 */
fun nodeColor(nodeNum: Int): Color {
    val hue = (nodeNum.absoluteValue % 360).toFloat()
    return Color.hsl(hue, saturation = 0.45f, lightness = 0.62f)
}

/** Circular badge showing a node's four-character short name. */
@Composable
fun NodeAvatar(
    node: MeshNode?,
    modifier: Modifier = Modifier,
    fallback: String = "??",
    size: Int = 32,
) {
    val label = node?.displayShort?.take(4) ?: fallback
    val color = nodeColor(node?.num ?: 0)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            // Meshtastic short names are up to four characters and are how people
            // identify each other, so the glyphs scale with the badge rather than
            // clipping "RDGE" down to "RD" in list rows.
            fontSize = (size * 0.30f).sp,
            lineHeight = (size * 0.34f).sp,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

/** Four ascending bars, lit according to link quality. */
@Composable
fun SignalBars(quality: SignalQuality, modifier: Modifier = Modifier) {
    val lit = when (quality) {
        SignalQuality.None -> 0
        SignalQuality.Poor -> 1
        SignalQuality.Fair -> 2
        SignalQuality.Good -> 3
        SignalQuality.Excellent -> 4
    }
    val tint = when (quality) {
        SignalQuality.None -> MaterialTheme.colorScheme.outline
        SignalQuality.Poor -> MeshPalette.Danger
        SignalQuality.Fair -> MeshPalette.Amber
        else -> MeshPalette.MeshGreen
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(4) { index ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((4 + index * 3).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < lit) tint else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

/** Small labelled dot, used for online/offline and similar binary state. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Compact key/value row for detail screens. */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * What a screen shows when there is nothing to show.
 *
 * Centred and short: on a round display, a paragraph of explanatory text would be
 * clipped at the corners and unreadable anyway.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Delivery state, rendered as a glyph rather than a word to save width. */
@Composable
fun MessageStatusIcon(status: MsgStatus, modifier: Modifier = Modifier) {
    val (icon, tint) = when (status) {
        MsgStatus.Queued -> WtIcons.Hops to MaterialTheme.colorScheme.outline
        MsgStatus.Sent -> WtIcons.Check to MaterialTheme.colorScheme.outline
        MsgStatus.Delivered -> WtIcons.Check to MeshPalette.MeshGreen
        MsgStatus.Failed -> WtIcons.Close to MeshPalette.Danger
        MsgStatus.Received -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = tint,
        modifier = modifier.size(12.dp),
    )
}

/** "3m", "4h", "2d" — the most information that fits in a corner of a watch row. */
fun shortAgo(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timeMs <= 0L) return "—"
    val seconds = ((nowMs - timeMs) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 604_800 -> "${seconds / 86_400}d"
        else -> "${seconds / 604_800}w"
    }
}
