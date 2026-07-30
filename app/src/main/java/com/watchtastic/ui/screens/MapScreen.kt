package com.watchtastic.ui.screens

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.NodePosition
import com.watchtastic.mesh.model.formatDistance
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.components.EmptyState
import com.watchtastic.ui.components.nodeColor
import com.watchtastic.ui.icons.WtIcons
import com.watchtastic.ui.theme.MeshPalette
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Range rings the crown steps through, in metres. */
private val RANGES = listOf(250f, 500f, 1_000f, 2_000f, 5_000f, 10_000f, 25_000f, 50_000f, 100_000f)

/** Anything plotted: a node or a waypoint, already reduced to polar coordinates. */
private data class Blip(
    val label: String,
    val distanceMeters: Float,
    val bearingDegrees: Float,
    val color: Color,
    val nodeNum: Int?,
    val isWaypoint: Boolean,
)

/**
 * A map of the mesh around you.
 *
 * Deliberately **not** a tiled street map. A tile map needs network and an API key in
 * exactly the situation Meshtastic exists for — a valley with no cell service — so it
 * would be dead weight when it matters most. This draws instead from what the mesh
 * already told us: every node and waypoint plotted by true bearing and distance from
 * your position, entirely offline, on range rings the crown zooms through.
 *
 * For street context on a specific node, the node detail screen hands its coordinates to
 * whatever mapping app the watch has via a `geo:` intent.
 */
@Composable
fun MapScreen(onOpenNode: (Int) -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current

    val nodes by graph.store.nodes.collectAsStateWithLifecycle()
    val waypoints by graph.store.waypoints.collectAsStateWithLifecycle()
    val myNodeNum by graph.store.myNodeNum.collectAsStateWithLifecycle()
    val imperial by graph.prefs.imperialUnits.collectAsStateWithLifecycle()
    val watchFix by graph.location.lastFix.collectAsStateWithLifecycle()

    val here = watchFix ?: nodes[myNodeNum]?.position

    // Ask for a fix on open if we have nothing — the map is useless without an origin.
    var locating by remember { mutableStateOf(false) }
    LaunchedEffect(here == null) {
        if (here == null && graph.location.hasPermission) {
            locating = true
            graph.location.requestSingleFix()
            locating = false
        }
    }

    var headingUp by remember { mutableStateOf(true) }
    var heading by remember { mutableFloatStateOf(0f) }

    DisposableEffect(headingUp) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                heading = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null && headingUp) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { manager?.unregisterListener(listener) }
    }

    val blips = remember(nodes, waypoints, here, myNodeNum) {
        buildBlips(here, nodes, waypoints, myNodeNum)
    }

    // Default zoom fits the farthest thing; the crown then takes over.
    val autoIndex = remember(blips) {
        val farthest = blips.maxOfOrNull { it.distanceMeters } ?: 0f
        RANGES.indexOfFirst { it >= farthest * 1.15f }.takeIf { it >= 0 } ?: RANGES.lastIndex
    }
    var manualIndex by remember { mutableStateOf<Int?>(null) }
    val zoomIndex = (manualIndex ?: autoIndex).coerceIn(0, RANGES.lastIndex)
    val range = RANGES[zoomIndex]

    val rotation by animateFloatAsState(
        targetValue = if (headingUp) heading else 0f,
        label = "mapRotation",
    )

    val textMeasurer = rememberTextMeasurer()

    // A slow radar sweep and a beacon pulse. Both are decorative, but they answer a real
    // question at a glance — "is this screen live, or frozen on stale data?" — which
    // matters when the honest answer is often "nothing has moved in ten minutes".
    val motion = rememberInfiniteTransition(label = "mapMotion")
    val sweep by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val beacon by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "beacon",
    )

    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val northColor = MaterialTheme.colorScheme.onSurface

    // A plain scaffold: the map doesn't scroll, and every ScreenScaffold overload that
    // offers an edge-button slot is tied to a scroll state. The orientation toggle is
    // placed directly instead.
    ScreenScaffold {
        if (here == null) {
            EmptyState(
                icon = WtIcons.Map,
                title = if (locating) "Getting a fix…" else "No position",
                subtitle = if (graph.location.hasPermission) {
                    "Neither the watch nor the radio knows where you are yet"
                } else {
                    "Allow location to place yourself on the map"
                },
                modifier = Modifier.fillMaxSize().padding(top = 44.dp),
            )
            return@ScreenScaffold
        }

        Box(
            Modifier
                .fillMaxSize()
                // The crown zooms the range rings. This is the natural gesture for a
                // map and needs no screen real estate, which is the whole argument for
                // using it here rather than pinch.
                .onRotaryScrollEvent { event ->
                    val step = if (event.verticalScrollPixels > 0) 1 else -1
                    val next = (zoomIndex + step).coerceIn(0, RANGES.lastIndex)
                    if (next != zoomIndex) {
                        manualIndex = next
                        graph.haptics.tick()
                    } else {
                        graph.haptics.boundary()
                    }
                    true
                }
                // Takes focus whenever this screen is the active one, which is what makes
                // the crown deliver rotary events here.
                .requestFocusOnHierarchyActive()
                .focusable()
                .pointerInput(blips, range, rotation) {
                    detectTapGestures { tap ->
                        // PointerInputScope.size is an IntSize, not a Size.
                        val shortest = minOf(size.width, size.height).toFloat()
                        val radius = shortest / 2f * PLOT_FRACTION
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val hit = blips
                            .filter { it.nodeNum != null && it.distanceMeters <= range }
                            .minByOrNull { blip ->
                                val p = project(blip, rotation, radius, range, center)
                                hypot(tap.x - p.x, tap.y - p.y)
                            }
                        if (hit?.nodeNum != null) {
                            val p = project(hit, rotation, radius, range, center)
                            if (hypot(tap.x - p.x, tap.y - p.y) < TAP_SLOP_PX) {
                                graph.haptics.select()
                                onOpenNode(hit.nodeNum)
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f * PLOT_FRACTION

                drawRangeRings(center, radius, ringColor)
                drawRadarSweep(center, radius, sweep)

                // North marker rides the rim and rotates opposite the map.
                rotate(degrees = -rotation, pivot = center) {
                    val r = size.minDimension / 2f * 0.93f
                    drawCircle(northColor, radius = 3.5f, center = center + Offset(0f, -r))
                }

                // Markers first, then the centre marker, then every label. Labels last
                // because a blip close in — a waypoint at your feet — would otherwise be
                // captioned underneath your own position dot.
                val labels = mutableListOf<Pair<Blip, Offset>>()
                blips.forEach { blip ->
                    val clamped = blip.distanceMeters > range
                    val point = project(blip, rotation, radius, range, center)
                    if (blip.isWaypoint) {
                        drawWaypoint(point, clamped)
                    } else {
                        drawNode(point, blip.color, clamped)
                    }
                    if (!clamped) labels += blip to point
                }

                // You, at the centre — with a beacon ring breathing outward.
                drawCircle(
                    color = MeshPalette.MeshGreen.copy(alpha = (1f - beacon) * 0.40f),
                    radius = 6f + beacon * 22f,
                    center = center,
                    style = Stroke(width = 2f),
                )
                drawCircle(MeshPalette.MeshGreen.copy(alpha = 0.22f), radius = 11f, center = center)
                drawCircle(MeshPalette.MeshGreen, radius = 4.5f, center = center)

                // Nearest first, and drop any caption that would land on one already
                // placed. A crowded mesh degrades to fewer, readable labels rather than
                // a pile of overlapping text — the blip itself is still there and still
                // tappable.
                val placed = mutableListOf<Rect>()
                val ordered = labels.sortedWith(
                    // Nodes win captions over waypoints: knowing *who* is out there
                    // matters more than re-reading a waypoint you named yourself.
                    compareBy<Pair<Blip, Offset>> { it.first.isWaypoint }
                        .thenBy { it.first.distanceMeters },
                )
                ordered.forEach { (blip, point) ->
                    val layout = textMeasurer.measure(
                        AnnotatedString(blip.label.take(MAX_LABEL_CHARS)),
                        style = TextStyle(
                            fontSize = 9.sp,
                            color = blip.color,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                    )
                    // Caption below normally, but above when the blip sits on top of the
                    // centre marker, so the two never stack.
                    val below = hypot(point.x - center.x, point.y - center.y) > CENTRE_CLEARANCE_PX
                    val topLeft = Offset(
                        point.x - layout.size.width / 2f,
                        if (below) {
                            point.y + NODE_RADIUS_PX + 2f
                        } else {
                            point.y - NODE_RADIUS_PX - 2f - layout.size.height
                        },
                    )
                    val rect = Rect(
                        topLeft.x,
                        topLeft.y,
                        topLeft.x + layout.size.width,
                        topLeft.y + layout.size.height,
                    ).inflate(2f)
                    if (placed.any { it.overlaps(rect) }) return@forEach
                    placed += rect
                    drawText(textLayoutResult = layout, topLeft = topLeft)
                }
            }

            // Scale readout, kept off the plot itself.
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatDistance(range.toDouble(), imperial),
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                )
                Text(
                    text = "${blips.count { !it.isWaypoint }} nodes",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }

            CompactButton(
                onClick = { graph.haptics.select(); headingUp = !headingUp },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = {
                    Text(
                        text = if (headingUp) "Heading up" else "North up",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

// ------------------------------------------------------------------ geometry

private const val PLOT_FRACTION = 0.80f
private const val NODE_RADIUS_PX = 7f
private const val TAP_SLOP_PX = 34f

/** Inside this radius of the centre dot, captions flip above the blip. */
private const val CENTRE_CLEARANCE_PX = 26f

/** Node short names are four characters; waypoint names need reining in. */
private const val MAX_LABEL_CHARS = 9

/** Polar to canvas. Bearing is true; [rotation] is how far the map itself is turned. */
private fun project(
    blip: Blip,
    rotation: Float,
    radiusPx: Float,
    rangeMeters: Float,
    center: Offset,
): Offset {
    // Out-of-range blips pin just outside the outer ring rather than flying off screen.
    val ratio = (blip.distanceMeters / rangeMeters).coerceAtMost(1.06f)
    val angle = Math.toRadians(((blip.bearingDegrees - rotation) + 360f).toDouble() % 360.0)
    return Offset(
        center.x + (sin(angle) * ratio * radiusPx).toFloat(),
        center.y - (cos(angle) * ratio * radiusPx).toFloat(),
    )
}

private fun DrawScope.drawRangeRings(center: Offset, radius: Float, color: Color) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
    listOf(0.333f, 0.666f).forEach { fraction ->
        drawCircle(
            color = color,
            radius = radius * fraction,
            center = center,
            style = Stroke(width = 1.5f, pathEffect = dashed),
        )
    }
    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = 2f))

    // Cardinal ticks just outside the outer ring.
    repeat(4) { index ->
        val angle = Math.toRadians((index * 90).toDouble())
        val inner = radius * 1.04f
        val outer = radius * 1.11f
        drawLine(
            color = color,
            start = center + Offset(
                (sin(angle) * inner).toFloat(),
                (-cos(angle) * inner).toFloat(),
            ),
            end = center + Offset(
                (sin(angle) * outer).toFloat(),
                (-cos(angle) * outer).toFloat(),
            ),
            strokeWidth = 2f,
        )
    }
}

/**
 * The rotating sweep.
 *
 * A sweep gradient that is opaque at its leading edge and transparent a fraction of a
 * turn behind it, rotated as a whole — which is exactly the comet tail a radar display
 * leaves, and costs one draw call rather than a stack of fading wedges.
 */
private fun DrawScope.drawRadarSweep(center: Offset, radius: Float, degrees: Float) {
    rotate(degrees = degrees, pivot = center) {
        drawCircle(
            brush = Brush.sweepGradient(
                colorStops = arrayOf(
                    0.00f to MeshPalette.MeshGreen.copy(alpha = 0.26f),
                    0.12f to MeshPalette.MeshGreen.copy(alpha = 0.05f),
                    0.22f to Color.Transparent,
                    1.00f to Color.Transparent,
                ),
                center = center,
            ),
            radius = radius,
            center = center,
        )
        // Bright leading edge, so the direction of travel is unambiguous.
        drawLine(
            color = MeshPalette.MeshGreen.copy(alpha = 0.55f),
            start = center,
            end = center + Offset(0f, -radius),
            strokeWidth = 1.5f,
        )
    }
}

private fun DrawScope.drawNode(point: Offset, color: Color, clamped: Boolean) {
    if (clamped) {
        // Hollow: "this node exists, but it is off the current scale".
        drawCircle(color.copy(alpha = 0.7f), NODE_RADIUS_PX - 1f, point, style = Stroke(2f))
    } else {
        drawCircle(color.copy(alpha = 0.25f), NODE_RADIUS_PX + 3f, point)
        drawCircle(color, NODE_RADIUS_PX - 1.5f, point)
    }
}

private fun DrawScope.drawWaypoint(point: Offset, clamped: Boolean) {
    val size = if (clamped) 5f else 6.5f
    val path = Path().apply {
        moveTo(point.x, point.y - size)
        lineTo(point.x + size, point.y)
        lineTo(point.x, point.y + size)
        lineTo(point.x - size, point.y)
        close()
    }
    if (clamped) {
        drawPath(path, MeshPalette.Amber.copy(alpha = 0.7f), style = Stroke(2f))
    } else {
        drawPath(path, MeshPalette.Amber)
    }
}

private fun buildBlips(
    here: NodePosition?,
    nodes: Map<Int, MeshNode>,
    waypoints: List<com.watchtastic.mesh.model.Waypoint>,
    myNodeNum: Int,
): List<Blip> {
    if (here == null) return emptyList()

    val nodeBlips = nodes.values
        .filter { it.num != myNodeNum && !it.isIgnored }
        .mapNotNull { node ->
            val position = node.position ?: return@mapNotNull null
            val distance = here.distanceTo(position).toFloat()
            // A node sitting on top of us has no meaningful bearing to draw.
            if (distance < 1f) return@mapNotNull null
            Blip(
                label = node.displayShort,
                distanceMeters = distance,
                bearingDegrees = here.bearingTo(position),
                color = nodeColor(node.num),
                nodeNum = node.num,
                isWaypoint = false,
            )
        }

    val waypointBlips = waypoints.map { waypoint ->
        val position = NodePosition(waypoint.latitude, waypoint.longitude)
        Blip(
            label = waypoint.name.ifBlank { "Waypoint" },
            distanceMeters = here.distanceTo(position).toFloat(),
            bearingDegrees = here.bearingTo(position),
            color = MeshPalette.Amber,
            nodeNum = null,
            isWaypoint = true,
        )
    }

    // Farthest first so near blips paint on top when they overlap.
    return (nodeBlips + waypointBlips).sortedByDescending { it.distanceMeters }
}
