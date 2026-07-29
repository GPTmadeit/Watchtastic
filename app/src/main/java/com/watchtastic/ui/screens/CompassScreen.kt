package com.watchtastic.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.model.bearingLabel
import com.watchtastic.mesh.model.formatDistance
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.components.rememberHeadingDegrees
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * Points at a node.
 *
 * This is the screen a phone can't beat: the watch is already on the wrist you'd raise
 * to look along a bearing, so the arrow is read the way a compass is read — by turning
 * your body until it points up. A short tick fires when the wearer swings onto the
 * bearing, so alignment can be felt without staring at the display.
 *
 * True north comes from the rotation-vector sensor (fused compass + gyro), which is
 * steadier than the raw magnetometer and is what Wear's own compass surfaces use.
 */
@Composable
fun CompassScreen(nodeNum: Int) {
    val graph = LocalAppGraph.current
    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val myNodeNum by graph.store.myNodeNum.collectAsStateWithLifecycle()
    val imperial by graph.prefs.imperialUnits.collectAsStateWithLifecycle()

    val node = nodes[nodeNum]
    val myPosition = nodes[myNodeNum]?.position

    val heading by rememberHeadingDegrees()
    var wasAligned by remember { mutableStateOf(false) }

    ScreenScaffold {
        if (node?.position == null || myPosition == null) {
            EmptyState(
                icon = WtIcons.Compass,
                title = "No position",
                subtitle = if (myPosition == null) {
                    "This radio doesn't know where it is"
                } else {
                    "${node?.displayShort ?: "That node"} hasn't shared a position"
                },
                modifier = Modifier.fillMaxSize().padding(top = 40.dp),
            )
            return@ScreenScaffold
        }

        val bearing = myPosition.bearingTo(node.position)
        val distance = myPosition.distanceTo(node.position)
        // Where the target sits relative to where the wearer is facing.
        val relative = ((bearing - heading) + 360f) % 360f

        val aligned = relative < 6f || relative > 354f
        // Fire the alignment tick as an effect, not inline: composition can run any
        // number of times per frame, and buzzing on each pass would stutter the wrist.
        LaunchedEffect(aligned) {
            if (aligned && !wasAligned) graph.haptics.tick()
            wasAligned = aligned
        }

        // Smoothed so sensor jitter doesn't make the needle twitch.
        val needle by animateFloatAsState(targetValue = relative, label = "needle")

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CompassDial(
                relativeBearing = needle,
                headingToNorth = -heading,
                highlighted = aligned,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = node.displayShort,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatDistance(distance, imperial),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "${bearing.toInt()}° ${bearingLabel(bearing)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aligned) MeshPalette.MeshGreen else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CompassDial(
    relativeBearing: Float,
    headingToNorth: Float,
    highlighted: Boolean,
) {
    val needleColor = if (highlighted) MeshPalette.MeshGreen else MeshPalette.MeshGreen600
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val northColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Bezel ticks every 30°, drawn just inside the physical edge. The Pixel Watch's
        // display is domed, so nothing meaningful goes closer than ~8% from the rim.
        val inset = radius * 0.08f
        repeat(12) { index ->
            val angle = Math.toRadians((index * 30).toDouble()).toFloat()
            val outer = radius - inset
            val inner = outer - if (index % 3 == 0) radius * 0.09f else radius * 0.05f
            drawLine(
                color = tickColor,
                start = center + Offset(sin(angle) * inner, -cos(angle) * inner),
                end = center + Offset(sin(angle) * outer, -cos(angle) * outer),
                strokeWidth = if (index % 3 == 0) 3f else 2f,
                cap = StrokeCap.Round,
            )
        }

        // North marker rotates with the wearer, so the dial behaves like a real compass.
        rotate(degrees = headingToNorth, pivot = center) {
            val r = radius - inset - radius * 0.02f
            drawCircle(
                color = northColor,
                radius = 4f,
                center = center + Offset(0f, -r),
            )
        }

        // The pointer rides the outer band rather than sweeping from the centre: the
        // middle of the dial belongs to the distance readout, and a full-length needle
        // draws straight through it at most bearings.
        rotate(degrees = relativeBearing, pivot = center) {
            val tipY = center.y - (radius - inset - radius * 0.03f)
            val baseY = center.y - (radius - inset - radius * 0.24f)
            val halfWidth = radius * 0.12f
            val path = Path().apply {
                moveTo(center.x, tipY)
                lineTo(center.x - halfWidth, baseY)
                // Shallow notch in the trailing edge, so the arrow reads as a pointer
                // rather than a wedge.
                lineTo(center.x, baseY - radius * 0.07f)
                lineTo(center.x + halfWidth, baseY)
                close()
            }
            drawPath(path = path, color = needleColor)
        }

        if (highlighted) {
            drawCircle(
                color = needleColor.copy(alpha = 0.35f),
                radius = radius - inset,
                center = center,
                style = Stroke(width = 3f),
            )
        }
    }
}
