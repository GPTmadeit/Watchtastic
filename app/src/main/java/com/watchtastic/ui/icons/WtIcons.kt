package com.watchtastic.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Watchtastic's icon set.
 *
 * Drawn rather than imported: Material's core icon set doesn't have a vocabulary for
 * hops, SNR, traceroute or mesh nodes, and mixing filled Material glyphs with the
 * stroked app mark looks like two apps in one. Everything here is built on the same
 * 24 dp grid with a 2 dp round-capped stroke, matching the launcher icon's construction,
 * so the whole app reads as one drawing.
 *
 * Colours are placeholders — `Icon` tints the whole vector with the current content
 * colour, exactly as it does for Material's own icons.
 */
object WtIcons {

    // ------------------------------------------------------------- brand

    /** The app mark: twin chirp peaks with mesh nodes at the vertices. */
    val Mesh: ImageVector by lazy {
        icon("Mesh") {
            stroke { moveTo(3.4f, 19.2f); lineTo(8.6f, 5.6f); lineTo(12f, 12.6f); lineTo(15.4f, 5.6f); lineTo(20.6f, 19.2f) }
            fill { circle(8.6f, 5.6f, 2.1f) }
            fill { circle(15.4f, 5.6f, 2.1f) }
            fill { circle(12f, 12.6f, 1.9f) }
        }
    }

    // -------------------------------------------------------- navigation

    val Back: ImageVector by lazy {
        icon("Back") {
            stroke { moveTo(15f, 4.5f); lineTo(7.5f, 12f); lineTo(15f, 19.5f) }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            stroke { moveTo(6f, 6f); lineTo(18f, 18f) }
            stroke { moveTo(18f, 6f); lineTo(6f, 18f) }
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            stroke { moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f) }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            fill { circle(12f, 5f, 1.8f) }
            fill { circle(12f, 12f, 1.8f) }
            fill { circle(12f, 19f, 1.8f) }
        }
    }

    val Plus: ImageVector by lazy {
        icon("Plus") {
            stroke { moveTo(12f, 5f); lineTo(12f, 19f) }
            stroke { moveTo(5f, 12f); lineTo(19f, 12f) }
        }
    }

    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            stroke {
                moveTo(20f, 12f)
                arcToRelative(8f, 8f, 0f, true, true, -2.4f, -5.7f)
            }
            stroke { moveTo(20.5f, 2.8f); lineTo(20.5f, 7.2f); lineTo(16.1f, 7.2f) }
        }
    }

    // ------------------------------------------------------------- mesh

    /** Three peers joined by links — the node list. */
    val Nodes: ImageVector by lazy {
        icon("Nodes") {
            stroke { moveTo(12f, 6.4f); lineTo(5.6f, 17f) }
            stroke { moveTo(12f, 6.4f); lineTo(18.4f, 17f) }
            stroke { moveTo(5.6f, 17f); lineTo(18.4f, 17f) }
            fill { circle(12f, 5.2f, 2.6f) }
            fill { circle(5.4f, 17.6f, 2.6f) }
            fill { circle(18.6f, 17.6f, 2.6f) }
        }
    }

    /** A single radio node, broadcasting. */
    val Radio: ImageVector by lazy {
        icon("Radio") {
            fill { circle(12f, 12f, 2.4f) }
            stroke(1.8f) {
                moveTo(7.8f, 7.8f)
                arcToRelative(6f, 6f, 0f, false, false, 0f, 8.4f)
            }
            stroke(1.8f) {
                moveTo(16.2f, 16.2f)
                arcToRelative(6f, 6f, 0f, false, false, 0f, -8.4f)
            }
            stroke(1.6f) {
                moveTo(4.9f, 4.9f)
                arcToRelative(10f, 10f, 0f, false, false, 0f, 14.2f)
            }
            stroke(1.6f) {
                moveTo(19.1f, 19.1f)
                arcToRelative(10f, 10f, 0f, false, false, 0f, -14.2f)
            }
        }
    }

    /** Broadcast channel: a hash crossed by a transmission arc. */
    val Channel: ImageVector by lazy {
        icon("Channel") {
            stroke { moveTo(9.2f, 4.5f); lineTo(7.6f, 19.5f) }
            stroke { moveTo(15.6f, 4.5f); lineTo(14f, 19.5f) }
            stroke { moveTo(4.6f, 9f); lineTo(18.6f, 9f) }
            stroke { moveTo(4f, 15f); lineTo(18f, 15f) }
        }
    }

    /** Hop count between us and a node. */
    val Hops: ImageVector by lazy {
        icon("Hops") {
            stroke(1.8f) {
                moveTo(4f, 16f)
                quadTo(8f, 6f, 12f, 16f)
                quadTo(16f, 6f, 20f, 16f)
            }
            fill { circle(4f, 17.4f, 1.7f) }
            fill { circle(12f, 17.4f, 1.7f) }
            fill { circle(20f, 17.4f, 1.7f) }
        }
    }

    /** Traceroute: a path stepping through intermediate nodes. */
    val Route: ImageVector by lazy {
        icon("Route") {
            stroke { moveTo(5f, 18.5f); lineTo(5f, 12f); lineTo(19f, 12f); lineTo(19f, 6f) }
            fill { circle(5f, 19.6f, 2.2f) }
            fill { circle(19f, 4.6f, 2.2f) }
            fill { circle(12f, 12f, 1.9f) }
        }
    }

    /** Signal quality, drawn as ascending bars. */
    val Signal: ImageVector by lazy {
        icon("Signal") {
            stroke(2.4f, StrokeCap.Round) { moveTo(5f, 18f); lineTo(5f, 15.5f) }
            stroke(2.4f, StrokeCap.Round) { moveTo(10.3f, 18f); lineTo(10.3f, 12.5f) }
            stroke(2.4f, StrokeCap.Round) { moveTo(15.6f, 18f); lineTo(15.6f, 9f) }
            stroke(2.4f, StrokeCap.Round) { moveTo(20.9f, 18f); lineTo(20.9f, 5.5f) }
        }
    }

    val Bluetooth: ImageVector by lazy {
        icon("Bluetooth") {
            stroke { moveTo(7f, 7.6f); lineTo(17f, 16.4f); lineTo(12f, 20.5f); lineTo(12f, 3.5f); lineTo(17f, 7.6f); lineTo(7f, 16.4f) }
        }
    }

    val Battery: ImageVector by lazy {
        icon("Battery") {
            stroke(1.8f) {
                moveTo(4.6f, 7.6f)
                lineTo(16.4f, 7.6f)
                arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, 1.6f)
                lineTo(18f, 14.8f)
                arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, 1.6f)
                lineTo(4.6f, 16.4f)
                arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, -1.6f)
                lineTo(3f, 9.2f)
                arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, -1.6f)
                close()
            }
            stroke(2.2f) { moveTo(20.4f, 10.4f); lineTo(20.4f, 13.6f) }
            fill { moveTo(5.4f, 10f); lineTo(11.5f, 10f); lineTo(11.5f, 14f); lineTo(5.4f, 14f); close() }
        }
    }

    // ------------------------------------------------------------- chat

    val Chat: ImageVector by lazy {
        icon("Chat") {
            stroke(1.9f) {
                moveTo(20.5f, 15f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                lineTo(8f, 17f)
                lineTo(3.5f, 20.8f)
                lineTo(3.5f, 6f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                lineTo(18.5f, 4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                close()
            }
        }
    }

    val Send: ImageVector by lazy {
        icon("Send") {
            stroke { moveTo(20.5f, 3.5f); lineTo(10.4f, 13.6f) }
            stroke { moveTo(20.5f, 3.5f); lineTo(14f, 20.5f); lineTo(10.4f, 13.6f); lineTo(3.5f, 10f); close() }
        }
    }

    val Reply: ImageVector by lazy {
        icon("Reply") {
            stroke { moveTo(9f, 5.5f); lineTo(3.5f, 11f); lineTo(9f, 16.5f) }
            stroke {
                moveTo(3.5f, 11f)
                lineTo(15f, 11f)
                arcToRelative(5.5f, 5.5f, 0f, false, true, 5.5f, 5.5f)
                lineTo(20.5f, 19f)
            }
        }
    }

    /** Tapback / react. */
    val React: ImageVector by lazy {
        icon("React") {
            stroke(1.9f) { circle(12f, 12f, 8.6f) }
            fill { circle(9f, 10f, 1.3f) }
            fill { circle(15f, 10f, 1.3f) }
            stroke(1.8f) {
                moveTo(8f, 14.4f)
                quadTo(12f, 17.8f, 16f, 14.4f)
            }
        }
    }

    val Mic: ImageVector by lazy {
        icon("Mic") {
            stroke(1.9f) {
                moveTo(12f, 2.8f)
                arcToRelative(2.8f, 2.8f, 0f, false, true, 2.8f, 2.8f)
                lineTo(14.8f, 11f)
                arcToRelative(2.8f, 2.8f, 0f, false, true, -5.6f, 0f)
                lineTo(9.2f, 5.6f)
                arcToRelative(2.8f, 2.8f, 0f, false, true, 2.8f, -2.8f)
                close()
            }
            stroke {
                moveTo(5.5f, 10.6f)
                arcToRelative(6.5f, 6.5f, 0f, false, false, 13f, 0f)
            }
            stroke { moveTo(12f, 17.2f); lineTo(12f, 21f) }
        }
    }

    val Keyboard: ImageVector by lazy {
        icon("Keyboard") {
            stroke(1.8f) {
                moveTo(3.2f, 6.5f)
                lineTo(20.8f, 6.5f)
                lineTo(20.8f, 17.5f)
                lineTo(3.2f, 17.5f)
                close()
            }
            stroke(1.7f) { moveTo(7.2f, 10f); lineTo(7.3f, 10f) }
            stroke(1.7f) { moveTo(11.9f, 10f); lineTo(12.1f, 10f) }
            stroke(1.7f) { moveTo(16.7f, 10f); lineTo(16.8f, 10f) }
            stroke(1.9f) { moveTo(8.4f, 14f); lineTo(15.6f, 14f) }
        }
    }

    /** Canned / quick replies. */
    val QuickReply: ImageVector by lazy {
        icon("QuickReply") {
            stroke(1.8f) { moveTo(4f, 7f); lineTo(15f, 7f) }
            stroke(1.8f) { moveTo(4f, 12f); lineTo(12f, 12f) }
            stroke(1.8f) { moveTo(4f, 17f); lineTo(15f, 17f) }
            stroke { moveTo(16.5f, 14f); lineTo(20.5f, 10f); lineTo(16.5f, 6f) }
        }
    }

    // ------------------------------------------------------ position / misc

    val Location: ImageVector by lazy {
        icon("Location") {
            stroke(1.9f) {
                moveTo(12f, 21f)
                curveTo(16.5f, 15.5f, 19f, 12.2f, 19f, 9.4f)
                arcToRelative(7f, 7f, 0f, false, false, -14f, 0f)
                curveTo(5f, 12.2f, 7.5f, 15.5f, 12f, 21f)
                close()
            }
            fill { circle(12f, 9.3f, 2.5f) }
        }
    }

    /** Bearing to a node — a compass needle. */
    val Compass: ImageVector by lazy {
        icon("Compass") {
            stroke(1.9f) { circle(12f, 12f, 8.8f) }
            fill { moveTo(12f, 5.6f); lineTo(15.2f, 14f); lineTo(12f, 12.2f); lineTo(8.8f, 14f); close() }
        }
    }

    /** Folded paper map — deliberately unlike [Radio]'s concentric arcs. */
    val Map: ImageVector by lazy {
        icon("Map") {
            stroke(1.9f) {
                moveTo(3.2f, 6.6f)
                lineTo(9.1f, 4.1f)
                lineTo(14.9f, 6.6f)
                lineTo(20.8f, 4.1f)
                lineTo(20.8f, 17.4f)
                lineTo(14.9f, 19.9f)
                lineTo(9.1f, 17.4f)
                lineTo(3.2f, 19.9f)
                close()
            }
            stroke(1.7f) { moveTo(9.1f, 4.1f); lineTo(9.1f, 17.4f) }
            stroke(1.7f) { moveTo(14.9f, 6.6f); lineTo(14.9f, 19.9f) }
        }
    }

    val Waypoint: ImageVector by lazy {
        icon("Waypoint") {
            stroke { moveTo(6f, 21f); lineTo(6f, 3.4f); lineTo(18.6f, 7.6f); lineTo(6f, 11.8f) }
        }
    }

    val Star: ImageVector by lazy {
        icon("Star") {
            stroke(1.9f) {
                moveTo(12f, 3.4f)
                lineTo(14.7f, 9.1f)
                lineTo(20.8f, 9.9f)
                lineTo(16.4f, 14.3f)
                lineTo(17.5f, 20.6f)
                lineTo(12f, 17.6f)
                lineTo(6.5f, 20.6f)
                lineTo(7.6f, 14.3f)
                lineTo(3.2f, 9.9f)
                lineTo(9.3f, 9.1f)
                close()
            }
        }
    }

    val Alert: ImageVector by lazy {
        icon("Alert") {
            stroke(1.9f) {
                moveTo(6f, 16.4f)
                lineTo(6f, 10.6f)
                arcToRelative(6f, 6f, 0f, false, true, 12f, 0f)
                lineTo(18f, 16.4f)
                lineTo(20f, 18.6f)
                lineTo(4f, 18.6f)
                close()
            }
            stroke(1.8f) { moveTo(10.2f, 21f); lineTo(13.8f, 21f) }
        }
    }

    val Mute: ImageVector by lazy {
        icon("Mute") {
            stroke(1.9f) {
                moveTo(6f, 16.4f)
                lineTo(6f, 10.6f)
                arcToRelative(6f, 6f, 0f, false, true, 12f, 0f)
                lineTo(18f, 16.4f)
                lineTo(20f, 18.6f)
                lineTo(4f, 18.6f)
                close()
            }
            stroke(2.1f) { moveTo(3.6f, 3.6f); lineTo(20.4f, 20.4f) }
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            stroke(1.9f) { circle(12f, 12f, 3.2f) }
            stroke(1.8f) { moveTo(12f, 2.6f); lineTo(12f, 5.6f) }
            stroke(1.8f) { moveTo(12f, 18.4f); lineTo(12f, 21.4f) }
            stroke(1.8f) { moveTo(2.6f, 12f); lineTo(5.6f, 12f) }
            stroke(1.8f) { moveTo(18.4f, 12f); lineTo(21.4f, 12f) }
            stroke(1.8f) { moveTo(5.3f, 5.3f); lineTo(7.5f, 7.5f) }
            stroke(1.8f) { moveTo(16.5f, 16.5f); lineTo(18.7f, 18.7f) }
            stroke(1.8f) { moveTo(5.3f, 18.7f); lineTo(7.5f, 16.5f) }
            stroke(1.8f) { moveTo(16.5f, 7.5f); lineTo(18.7f, 5.3f) }
        }
    }

    val Info: ImageVector by lazy {
        icon("Info") {
            stroke(1.9f) { circle(12f, 12f, 8.8f) }
            stroke(2.1f) { moveTo(12f, 11f); lineTo(12f, 16.4f) }
            fill { circle(12f, 7.6f, 1.25f) }
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            stroke(1.9f) { moveTo(4.4f, 6.6f); lineTo(19.6f, 6.6f) }
            stroke(1.9f) {
                moveTo(6.4f, 6.6f)
                lineTo(7.3f, 19.6f)
                arcToRelative(1.6f, 1.6f, 0f, false, false, 1.6f, 1.5f)
                lineTo(15.1f, 21.1f)
                arcToRelative(1.6f, 1.6f, 0f, false, false, 1.6f, -1.5f)
                lineTo(17.6f, 6.6f)
            }
            stroke(1.8f) { moveTo(9.4f, 6.6f); lineTo(9.4f, 3.6f); lineTo(14.6f, 3.6f); lineTo(14.6f, 6.6f) }
        }
    }

    val Power: ImageVector by lazy {
        icon("Power") {
            stroke(2.1f) { moveTo(12f, 3f); lineTo(12f, 11.5f) }
            stroke(1.9f) {
                moveTo(7.2f, 6.4f)
                arcToRelative(7.8f, 7.8f, 0f, true, false, 9.6f, 0f)
            }
        }
    }
}

// ------------------------------------------------------------------ builders

private const val GRID = 24f

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = GRID.dp,
        defaultHeight = GRID.dp,
        viewportWidth = GRID,
        viewportHeight = GRID,
    ).apply(block).build()

private fun ImageVector.Builder.stroke(
    width: Float = 2f,
    cap: StrokeCap = StrokeCap.Round,
    join: StrokeJoin = StrokeJoin.Round,
    pathData: PathBuilder.() -> Unit,
): ImageVector.Builder = path(
    stroke = SolidColor(Color.White),
    strokeLineWidth = width,
    strokeLineCap = cap,
    strokeLineJoin = join,
    pathBuilder = pathData,
)

private fun ImageVector.Builder.fill(
    pathData: PathBuilder.() -> Unit,
): ImageVector.Builder = path(
    fill = SolidColor(Color.White),
    pathBuilder = pathData,
)

/** Circles as two half-arcs; the vector DSL has no primitive for them. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}
